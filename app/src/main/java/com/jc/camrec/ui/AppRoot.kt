package com.jc.camrec.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.jc.camrec.data.Store
import com.jc.camrec.rec.Recorder
import kotlin.system.exitProcess

sealed interface Screen {
    data object Tabs : Screen
    data class Watch(val site: String, val username: String) : Screen
    data class Play(val path: String) : Screen
    data class Browser(val url: String) : Screen
}

/** Simple back-stack navigation shared by all screens. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Tabs)
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val current: Screen get() = stack.last()
}

private data class Tab(val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Discover", Icons.Filled.Explore),
    Tab("Following", Icons.Filled.People),
    Tab("Recordings", Icons.Filled.VideoLibrary),
    Tab("Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val nav = remember { Nav() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings by Store.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val event by Recorder.events.collectAsState()

    LaunchedEffect(event) {
        val message = event ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        Recorder.events.value = null
    }

    if (!settings.adultConfirmed) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Adults only") },
            text = { Text("This app shows live adult webcam content. Confirm that you are 18 or older and that viewing it is legal where you are.\n\nOnly record streams you have the right to keep, and respect performers' privacy: don't redistribute recordings.") },
            confirmButton = { TextButton(onClick = { Store.updateSettings { it.copy(adultConfirmed = true) } }) { Text("I'm 18 or older") } },
            dismissButton = { TextButton(onClick = { exitProcess(0) }) { Text("Leave") } },
        )
        return
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    BackHandler(enabled = nav.stack.size == 1 && tab != 0) { tab = 0 }

    when (val screen = nav.current) {
        is Screen.Watch -> WatchScreen(screen.site, screen.username, nav)
        is Screen.Play -> PlayerScreen(screen.path, nav)
        is Screen.Browser -> BrowserScreen(screen.url, nav)
        Screen.Tabs -> Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = t.title) },
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> DiscoverScreen(nav)
                    1 -> FollowingScreen(nav)
                    2 -> RecordingsScreen(nav)
                    else -> SettingsScreen(nav)
                }
            }
        }
    }
}
