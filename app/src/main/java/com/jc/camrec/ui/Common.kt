package com.jc.camrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jc.camrec.sites.Site
import com.jc.camrec.sites.SiteBlockedException

val Accent = Color(0xFFE5484D)
val LiveGreen = Color(0xFF3DD68C)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFFB8BCC6),
    background = Color(0xFF101114),
    surface = Color(0xFF16171B),
    surfaceVariant = Color(0xFF22242A),
    surfaceContainer = Color(0xFF1B1D22),
    surfaceContainerHigh = Color(0xFF23252B),
    surfaceContainerHighest = Color(0xFF2A2D33),
    onBackground = Color(0xFFECEDEF),
    onSurface = Color(0xFFECEDEF),
    onSurfaceVariant = Color(0xFFA2A6B0),
    secondaryContainer = Color(0xFF3A2224),
    onSecondaryContainer = Color(0xFFFFD9DA),
)

@Composable
fun CamRecTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ErrorBox(error: Throwable, onRetry: () -> Unit, onOpenSite: (Site) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(error.message ?: error.javaClass.simpleName, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
        if (error is SiteBlockedException) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onOpenSite(error.site) }) { Text("Open ${error.site.name} in browser") }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatViewers(n: Int): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()
