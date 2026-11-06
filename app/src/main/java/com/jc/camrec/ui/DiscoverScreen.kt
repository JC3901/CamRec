package com.jc.camrec.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.jc.camrec.data.Follow
import com.jc.camrec.rec.Recorder
import com.jc.camrec.sites.Cam
import com.jc.camrec.sites.Gender
import com.jc.camrec.sites.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

/** Kept outside the composable so the list survives opening a room and coming back. */
private object DiscoverState {
    var site by mutableStateOf<Site>(com.jc.camrec.sites.Chaturbate)
    var gender by mutableStateOf(Gender.ALL)
    var query by mutableStateOf("")
    var appliedQuery by mutableStateOf("")
    var cams by mutableStateOf<List<Cam>>(emptyList())
    var hasMore by mutableStateOf(true)
    var total by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var loadedKey by mutableStateOf("")
    var job: Job? = null
}

private const val PAGE = 60

@Composable
fun DiscoverScreen(nav: Nav) {
    val s = DiscoverState
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    val active by Recorder.active.collectAsState()

    fun load(reset: Boolean) {
        if (s.loading && !reset) return
        s.job?.cancel()
        if (reset) { s.cams = emptyList(); s.hasMore = true; s.error = null }
        val site = s.site; val gender = s.gender; val query = s.appliedQuery
        val offset = s.cams.size
        s.loading = true
        s.job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { site.list(gender, query, offset, PAGE) }
                val known = s.cams.map { it.username.lowercase() }.toHashSet()
                s.cams = s.cams + page.cams.filter { known.add(it.username.lowercase()) }
                s.hasMore = page.hasMore
                s.total = page.total
                s.loadedKey = "${site.id}|$gender|$query"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                s.error = e
            } finally {
                s.loading = false
            }
        }
    }

    LaunchedEffect(s.site, s.gender, s.appliedQuery) {
        if (s.loadedKey != "${s.site.id}|${s.gender}|${s.appliedQuery}") load(reset = true)
    }
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= s.cams.size - 12 && s.hasMore && !s.loading && s.error == null && s.cams.isNotEmpty()) load(reset = false) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = s.query, onValueChange = { s.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Search ${s.site.name} or enter a room name") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (s.query.isNotEmpty()) IconButton(onClick = { s.query = ""; s.appliedQuery = "" }) { Icon(Icons.Filled.Clear, "Clear") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { s.appliedQuery = s.query.trim(); focus.clearFocus() }),
            shape = RoundedCornerShape(24.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Site.all) { site ->
                FilterChip(selected = s.site == site, onClick = { s.site = site }, label = { Text(site.name) })
            }
            item { Box(Modifier.size(width = 8.dp, height = 1.dp)) }
            items(Gender.entries.toList()) { g ->
                FilterChip(selected = s.gender == g, onClick = { s.gender = g }, label = { Text(g.label) })
            }
        }

        val direct = s.query.trim().takeIf { it.isNotEmpty() }?.let { Site.parseRoom(it, s.site) }
        val err = s.error
        if (err != null && s.cams.isEmpty()) {
            ErrorBox(err, onRetry = { load(reset = true) }, onOpenSite = { nav.push(Screen.Browser(it.homeUrl)) })
            return@Column
        }

        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (direct != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(Modifier.fillMaxWidth().clickable { nav.push(Screen.Watch(direct.first.id, direct.second)) }) {
                        Text("Open room “${direct.second}” on ${direct.first.name}", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (s.cams.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("${formatViewers(s.total)} rooms live", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(s.cams, key = { it.site + "/" + it.username }) { cam ->
                CamCard(cam, recording = active.containsKey(Follow.key(cam.site, cam.username))) {
                    nav.push(Screen.Watch(cam.site, cam.username))
                }
            }
            if (s.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (!s.loading && s.cams.isEmpty() && err == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("No public rooms found.", Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
fun CamCard(cam: Cam, recording: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(10.dp))) {
            AsyncImage(
                model = cam.thumbnail, contentDescription = cam.username,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (recording) Badge("REC", Accent)
                cam.age?.let { Badge("$it", Color(0x99000000)) }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge("👁 ${formatViewers(cam.viewers)}", Color(0x99000000))
            }
        }
        Text(cam.username, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (cam.title != cam.username) {
            Text(cam.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
