package com.jc.camrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.jc.camrec.data.Follow
import com.jc.camrec.data.Store
import com.jc.camrec.rec.Recorder
import com.jc.camrec.sites.RoomStatus
import com.jc.camrec.sites.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun WatchScreen(siteId: String, username: String, nav: Nav) {
    val site = remember(siteId) { Site.byId(siteId) }
    val context = LocalContext.current
    val key = Follow.key(siteId, username)
    var status by remember { mutableStateOf<RoomStatus?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val follows by Store.follows.collectAsState()
    val active by Recorder.active.collectAsState()
    val follow = follows.firstOrNull { it.key == key }
    val recording = active[key]

    LaunchedEffect(attempt) {
        error = null
        status = null
        try {
            val s = withContext(Dispatchers.IO) { site.status(username) }
            status = s
            val url = s.hlsUrl
            if (s.online && url != null) {
                player?.release()
                player = Players.live(context, url, site.streamHeaders, s.urlParams)
            }
        } catch (e: Exception) { error = e }
    }
    // Offline rooms are re-checked every 30 seconds while the screen is open.
    LaunchedEffect(status) {
        val s = status ?: return@LaunchedEffect
        if (!s.online) { delay(30_000); attempt++ }
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            if (fullscreen) setFullscreen(context.findActivity(), false)
        }
    }

    val p = player
    if (fullscreen && p != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(p, Modifier.fillMaxSize()) { full ->
                fullscreen = full; setFullscreen(context.findActivity(), full)
            }
        }
        androidx.activity.compose.BackHandler { fullscreen = false; setFullscreen(context.findActivity(), false) }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(site.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { nav.push(Screen.Browser(site.pageUrl(username))) }) { Icon(Icons.Filled.OpenInBrowser, "Open in browser") }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            val s = status
            val err = error
            when {
                p != null -> VideoSurface(p, Modifier.fillMaxSize()) { full ->
                    fullscreen = full; setFullscreen(context.findActivity(), full)
                }
                err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(err.message ?: "Could not load the room", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { attempt++ }) { Text("Retry") }
                        OutlinedButton(onClick = { nav.push(Screen.Browser(site.pageUrl(username))) }) { Text("Open in browser") }
                    }
                }
                s == null -> CircularProgressIndicator()
                else -> Text("Room is ${s.state}", color = Color.White)
            }
        }

        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.online) Badge("LIVE", LiveGreen) else Badge(s.state.uppercase(), Color.DarkGray)
                    if (s.online) Text("${formatViewers(s.viewers)} watching", style = MaterialTheme.typography.bodySmall)
                }
                s.title?.takeIf { it.isNotBlank() && it != username }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recording != null) {
                    Button(
                        onClick = { Recorder.stop(key) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop & save") }
                } else {
                    Button(onClick = { Recorder.start(site, username) }, enabled = status?.online == true) {
                        Icon(Icons.Filled.FiberManualRecord, null); Spacer(Modifier.width(6.dp)); Text("Record")
                    }
                }
                OutlinedButton(onClick = { if (follow == null) Store.follow(siteId, username) else Store.unfollow(key) }) {
                    Icon(if (follow != null) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = if (follow != null) Accent else LocalContentColorOr())
                    Spacer(Modifier.width(6.dp))
                    Text(if (follow != null) "Following" else "Follow")
                }
            }

            if (recording != null) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                Text(
                    "● ${recording.state} · ${formatDuration(now - recording.startedAt)} · ${formatBytes(recording.bytes)}" +
                        if (recording.height > 0) " · ${recording.height}p" else "",
                    color = Accent,
                )
            }

            if (follow != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-record")
                        Text(
                            "Record whenever this room goes live" + if (!Store.settings.value.monitor) " (turn on background auto-record in Settings)" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = follow.autoRecord, onCheckedChange = { Store.setAutoRecord(key, it) })
                }
            }
        }
    }
}

@Composable
private fun LocalContentColorOr(): Color = androidx.compose.material3.LocalContentColor.current
