package com.jc.camrec.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jc.camrec.App
import com.jc.camrec.data.Library
import com.jc.camrec.data.Store
import com.jc.camrec.rec.RecorderService
import com.jc.camrec.sites.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: Nav) {
    val s by Store.settings.collectAsState()
    val context = LocalContext.current
    val used by produceState(0L) { value = withContext(Dispatchers.IO) { Library.usedBytes() } }
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Auto-record")
        Toggle("Background auto-record", "Watch followed rooms and record them when they go live, even with the app closed.", s.monitor) { on ->
            Store.updateSettings { it.copy(monitor = on) }
            if (on) RecorderService.ensureRunning(App.ctx)
        }
        Choice("Check followed rooms every", s.pollSeconds, listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")) { v ->
            Store.updateSettings { it.copy(pollSeconds = v) }
        }
        Toggle("Wi-Fi only", "Only start automatic recordings on unmetered networks.", s.wifiOnly) { on -> Store.updateSettings { it.copy(wifiOnly = on) } }
        Toggle("Notify when a recording starts", null, s.notifyOnStart) { on -> Store.updateSettings { it.copy(notifyOnStart = on) } }

        Section("Recording")
        Choice("Maximum quality", s.maxHeight, listOf(0 to "Best", 1080 to "1080p", 720 to "720p", 480 to "480p")) { v ->
            Store.updateSettings { it.copy(maxHeight = v) }
        }
        Choice("If a room drops out, keep waiting", s.mergeGapMinutes, listOf(0 to "No", 5 to "5 min", 10 to "10 min", 30 to "30 min")) { v ->
            Store.updateSettings { it.copy(mergeGapMinutes = v) }
        }
        Hint("Short breaks (going private, reconnecting) are joined into the same video.")
        Choice("Split files every", s.splitMinutes, listOf(0 to "Never", 30 to "30 min", 60 to "1 h", 120 to "2 h")) { v ->
            Store.updateSettings { it.copy(splitMinutes = v) }
        }

        Section("Background running")
        Hint(
            if (unrestricted) "Battery optimization is off for CamRec, so recordings keep running in the background."
            else "Android may stop long recordings to save battery. Allow CamRec to run unrestricted."
        )
        if (!unrestricted) OutlinedButton(onClick = { requestUnrestricted(context) }) { Text("Allow background running") }

        Section("Sites")
        Hint("If a site shows an error or a check page, open it here once (accept the age notice / pass the check); the app reuses that browser session.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Site.all.forEach { site -> OutlinedButton(onClick = { nav.push(Screen.Browser(site.homeUrl)) }) { Text(site.name) } }
        }

        Section("Storage")
        Hint("Recordings: ${formatBytes(used)}\n${Library.root.path}\nUse “Save to Gallery” on a recording to copy it to Movies/CamRec.")

        Section("About")
        Hint("CamRec is a native Android rewrite of the live-cam part of Open EasyX (github.com/raccommode/OpenEasyX). Streams are recorded as-is, without re-encoding.")
    }
}

@SuppressLint("BatteryLife")
private fun requestUnrestricted(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    } catch (_: Exception) {
        context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(title: String, value: Int, options: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    Column {
        Text(title)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (v, label) -> FilterChip(selected = v == value, onClick = { onPick(v) }, label = { Text(label) }) }
        }
    }
}
