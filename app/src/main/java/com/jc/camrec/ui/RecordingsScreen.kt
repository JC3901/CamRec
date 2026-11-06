package com.jc.camrec.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jc.camrec.data.Library
import com.jc.camrec.data.Recording
import com.jc.camrec.rec.ActiveRecording
import com.jc.camrec.rec.Recorder
import com.jc.camrec.sites.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun RecordingsScreen(nav: Nav) {
    val active by Recorder.active.collectAsState()
    val finalizing by Recorder.finalizing.collectAsState()
    var recordings by remember { mutableStateOf<List<Recording>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(reload, finalizing, active.size) { recordings = Library.list() }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val list = recordings
    val people = list?.map { it.username }?.distinct()?.sorted() ?: emptyList()
    val shown = list?.filter { filter == null || it.username == filter } ?: emptyList()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Recordings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        if (active.isNotEmpty()) {
            items(active.values.toList(), key = { "active:" + it.key }) { rec -> ActiveCard(rec, now, nav) }
        }
        if (finalizing > 0) item {
            Text("Saving $finalizing recording(s) to MP4…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        if (people.size > 1) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") }) }
                items(people) { name -> FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) }) }
            }
        }
        if (list != null && list.isEmpty() && active.isEmpty()) item {
            Text("No recordings yet. Open a live room and tap Record, or follow rooms with Auto on.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shown, key = { it.file.path }) { r ->
            RecordingRow(r, onOpen = { nav.push(Screen.Play(r.file.path)) }, onChanged = { reload++ })
        }
        if (list != null && list.isNotEmpty()) item {
            Text("${list.size} recordings · ${formatBytes(list.sumOf { it.bytes })}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun ActiveCard(rec: ActiveRecording, now: Long, nav: Nav) {
    Card(Modifier.fillMaxWidth().clickable { nav.push(Screen.Watch(rec.site, rec.username)) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Accent, RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.username, fontWeight = FontWeight.SemiBold)
                Text(
                    "${Site.byId(rec.site).name} · ${rec.state} · ${formatDuration(now - rec.startedAt)} · ${formatBytes(rec.bytes)}" +
                        (if (rec.height > 0) " · ${rec.height}p" else "") + (if (rec.auto) " · auto" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { Recorder.stop(rec.key) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop")
            }
        }
    }
}

@Composable
private fun RecordingRow(r: Recording, onOpen: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, r.file.path) { value = withContext(Dispatchers.IO) { Library.thumbnail(r.file) } }

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                val b = thumb
                if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                if (r.durationMs > 0) Badge(formatDuration(r.durationMs), Color(0xAA000000), Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.username, fontWeight = FontWeight.SemiBold)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Site.byId(r.site).name} · ${formatBytes(r.bytes)}" + if (r.file.extension == "ts") " · raw" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; Library.share(context, r) })
                    DropdownMenuItem(text = { Text("Save to Gallery") }, onClick = {
                        menu = false
                        scope.launch {
                            val ok = Library.saveToGallery(context, r)
                            Recorder.events.value = if (ok) "Saved to Movies/CamRec" else "Could not save to Gallery"
                        }
                    })
                    DropdownMenuItem(text = { Text("Delete", color = Color(0xFFFF6B6B)) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete recording?") },
        text = { Text("${r.file.name} (${formatBytes(r.bytes)}) will be removed from this phone.") },
        confirmButton = { TextButton(onClick = { Library.delete(r); confirmDelete = false; onChanged() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
