package com.jc.camrec.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jc.camrec.data.Follow
import com.jc.camrec.data.Store
import com.jc.camrec.rec.Recorder
import com.jc.camrec.sites.Site
import kotlinx.coroutines.launch

@Composable
fun FollowingScreen(nav: Nav) {
    val follows by Store.follows.collectAsState()
    val statuses by Recorder.statuses.collectAsState()
    val active by Recorder.active.collectAsState()
    val settings by Store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try { Recorder.checkFollows(autoStart = false) } finally { refreshing = false }
        }
    }

    LaunchedEffect(follows.size) {
        val stale = follows.any { f -> (statuses[f.key]?.at ?: 0L) < System.currentTimeMillis() - 90_000 }
        if (stale && follows.isNotEmpty()) refresh()
    }

    val sorted = follows.sortedWith(
        compareByDescending<Follow> { active.containsKey(it.key) }
            .thenByDescending { statuses[it.key]?.status?.online == true }
            .thenBy { it.username.lowercase() }
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Following", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = ::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add room") }
        }

        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Background auto-record", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (settings.monitor) "Checking every ${settings.pollSeconds}s. Rooms with Auto on are recorded when they go live."
                                else "Off. Turn on to record followed rooms automatically, even with the app closed.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.monitor, onCheckedChange = { on ->
                            Store.updateSettings { it.copy(monitor = on) }
                            if (on) com.jc.camrec.rec.RecorderService.ensureRunning(com.jc.camrec.App.ctx)
                        })
                    }
                }
            }
            if (follows.isEmpty()) item {
                Text(
                    "You're not following anyone yet. Open a room from Discover and tap Follow, or add one with +.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(sorted, key = { it.key }) { f -> FollowRow(f, nav) }
        }
    }

    if (adding) AddRoomDialog(onDismiss = { adding = false }) { site, name ->
        Store.follow(site.id, name)
        adding = false
        scope.launch { Recorder.checkFollows(autoStart = false) }
    }
}

@Composable
private fun FollowRow(f: Follow, nav: Nav) {
    val statuses by Recorder.statuses.collectAsState()
    val active by Recorder.active.collectAsState()
    val site = Site.byId(f.site)
    val checked = statuses[f.key]
    val rec = active[f.key]
    var menu by remember { mutableStateOf(false) }
    val online = checked?.status?.online == true

    Card(Modifier.fillMaxWidth().clickable { nav.push(Screen.Watch(f.site, f.username)) }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 88.dp, height = 66.dp).clip(RoundedCornerShape(8.dp))) {
                AsyncImage(
                    model = checked?.status?.thumbnail ?: site.thumbnail(f.username),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                if (rec != null) Badge("REC", Accent, Modifier.align(Alignment.TopStart).padding(4.dp))
                else if (online) Badge("LIVE", LiveGreen, Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.username, fontWeight = FontWeight.SemiBold)
                val line = when {
                    rec != null -> "${rec.state} · ${formatBytes(rec.bytes)}"
                    checked == null -> "${site.name} · checking…"
                    checked.error != null -> "${site.name} · ${checked.error}"
                    online -> "${site.name} · live · ${formatViewers(checked.status?.viewers ?: 0)} watching"
                    else -> "${site.name} · ${checked.status?.state ?: "offline"}"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    color = if (rec != null) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = f.autoRecord, onCheckedChange = { Store.setAutoRecord(f.key, it) }, modifier = Modifier.height(32.dp))
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (rec != null) DropdownMenuItem(text = { Text("Stop & save") }, onClick = { Recorder.stop(f.key); menu = false })
                    else DropdownMenuItem(text = { Text("Record now") }, onClick = { Recorder.start(site, f.username); menu = false })
                    DropdownMenuItem(text = { Text("Open in browser") }, onClick = { nav.push(Screen.Browser(site.pageUrl(f.username))); menu = false })
                    DropdownMenuItem(text = { Text("Unfollow", color = Color(0xFFFF6B6B)) }, onClick = { Store.unfollow(f.key); menu = false })
                }
            }
        }
    }
}

@Composable
fun AddRoomDialog(onDismiss: () -> Unit, onAdd: (Site, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var site by remember { mutableStateOf(Site.all.first()) }
    val parsed = Site.parseRoom(text, site)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Follow a room") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Site.all.forEach { s -> FilterChip(selected = site == s, onClick = { site = s }, label = { Text(s.name) }) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Room name or link") })
                if (parsed != null && parsed.first != site) {
                    Text("Detected ${parsed.first.name} from the link", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { onAdd(it.first, it.second) } }) { Text("Follow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
