package com.jc.camrec.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.jc.camrec.data.Store
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun PlayerScreen(path: String, nav: Nav) {
    val context = LocalContext.current
    val player = remember(path) { Players.file(context, path, Store.position(path)) }
    var fullscreen by remember { mutableStateOf(false) }

    fun savePosition() {
        val pos = player.currentPosition
        val dur = player.duration
        if (dur > 0 && pos > dur - 5_000) Store.clearPosition(path) else Store.savePosition(path, pos)
    }

    LaunchedEffect(player) { while (true) { delay(5_000); savePosition() } }
    DisposableEffect(player) {
        onDispose {
            savePosition()
            player.release()
            setFullscreen(context.findActivity(), false)
        }
    }

    val onFull: (Boolean) -> Unit = { full -> fullscreen = full; setFullscreen(context.findActivity(), full) }

    if (fullscreen) {
        BackHandler { onFull(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) { VideoSurface(player, Modifier.fillMaxSize(), onFull) }
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(File(path).nameWithoutExtension, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            VideoSurface(player, Modifier.fillMaxSize(), onFull)
        }
    }
}
