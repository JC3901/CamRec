package com.jc.camrec.rec

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jc.camrec.App
import com.jc.camrec.MainActivity
import com.jc.camrec.R
import com.jc.camrec.data.Follow
import com.jc.camrec.data.Library
import com.jc.camrec.data.Store
import com.jc.camrec.hls.Hls
import com.jc.camrec.sites.RoomStatus
import com.jc.camrec.sites.Site
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ActiveRecording(
    val key: String,
    val site: String,
    val username: String,
    val startedAt: Long,
    val bytes: Long = 0,
    val state: String = "Starting…",
    val height: Int = 0,
    val auto: Boolean = false,
)

data class CheckedStatus(val status: RoomStatus?, val error: String?, val at: Long)

object Recorder {
    private const val TAG = "Recorder"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val finalizeLock = Mutex()

    private val _active = MutableStateFlow<Map<String, ActiveRecording>>(emptyMap())
    val active: StateFlow<Map<String, ActiveRecording>> = _active.asStateFlow()

    /** Last result per room, from the monitor or a manual refresh. */
    private val _statuses = MutableStateFlow<Map<String, CheckedStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, CheckedStatus>> = _statuses.asStateFlow()

    /** Short messages for the UI (recording failed, saved, ...). */
    val events = MutableStateFlow<String?>(null)

    /** Number of recordings being converted to MP4. */
    val finalizing = MutableStateFlow(0)

    fun isRecording(key: String) = jobs.containsKey(key)

    private fun setState(key: String, change: (ActiveRecording) -> ActiveRecording) =
        _active.update { m -> m[key]?.let { m + (key to change(it)) } ?: m }

    fun start(site: Site, username: String, auto: Boolean = false) {
        val key = Follow.key(site.id, username)
        if (jobs.containsKey(key)) return
        val stop = AtomicBoolean(false)
        stopFlags[key] = stop
        val startedAt = System.currentTimeMillis()
        _active.update { it + (key to ActiveRecording(key, site.id, username, startedAt, auto = auto)) }
        RecorderService.ensureRunning(App.ctx)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                record(site, username, key, startedAt, stop)
            } catch (e: Exception) {
                Log.w(TAG, "Recording $key failed", e)
                events.value = "$username: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                jobs.remove(key)
                stopFlags.remove(key)
                _active.update { it - key }
            }
        }
        jobs[key] = job
        job.start()
    }

    fun stop(key: String) {
        stopFlags[key]?.set(true)
        setState(key) { it.copy(state = "Saving…") }
    }

    fun stopAll() = active.value.keys.forEach { stop(it) }

    private suspend fun waitOrStop(ms: Long, stop: AtomicBoolean) {
        var left = ms
        while (left > 0 && !stop.get()) { delay(minOf(left, 1000)); left -= 1000 }
    }

    private suspend fun record(site: Site, username: String, key: String, startedAt: Long, stop: AtomicBoolean) {
        val dir = File(Library.partsRoot, "${site.id}_${username}_$startedAt").apply { mkdirs() }
        File(dir, "meta.json").writeText(
            JSONObject().put("site", site.id).put("username", username).put("startedAt", startedAt).toString()
        )
        var group = ArrayList<PartFiles>()
        var groupStart = startedAt
        var index = 0
        var lastData = System.currentTimeMillis()
        var notified = false
        try {
            while (!stop.get()) {
                val status = try { site.status(username) } catch (e: Exception) {
                    if (index == 0) throw e
                    null
                }
                if (status != null) _statuses.update { it + (key to CheckedStatus(status, null, System.currentTimeMillis())) }
                val hls = status?.hlsUrl
                if (status?.online != true || hls == null) {
                    if (index == 0) {
                        events.value = "$username is not live right now (${status?.state ?: "unknown"})"
                        break
                    }
                    val gapMs = Store.settings.value.mergeGapMinutes * 60_000L
                    if (System.currentTimeMillis() - lastData >= gapMs) break
                    setState(key) { it.copy(state = "Room is ${status?.state ?: "unreachable"} – waiting for it to return") }
                    waitOrStop(20_000, stop)
                    continue
                }

                val settings = Store.settings.value
                val selection = Hls.select(hls, site.streamHeaders, settings.maxHeight, status?.urlParams ?: emptyMap())
                val part = PartFiles(dir, ++index)
                setState(key) { it.copy(state = "Recording", height = selection.height) }
                if (!notified && settings.notifyOnStart) { notify("Recording $username", site.name); notified = true }

                val splitAt = if (settings.splitMinutes > 0) groupStart + settings.splitMinutes * 60_000L else null
                val reason = Capture(selection, site.streamHeaders, part, stop, splitAt) { n ->
                    setState(key) { it.copy(bytes = it.bytes + n) }
                }.run()
                Log.i(TAG, "$key part $index ended: $reason")

                if (part.hasData()) { group.add(part); lastData = System.currentTimeMillis() } else part.delete()
                if (reason == EndReason.STOPPED) break
                if (reason == EndReason.SPLIT) {
                    val done = group
                    val doneStart = groupStart
                    group = ArrayList()
                    groupStart = System.currentTimeMillis()
                    scope.launch { finalize(site.id, username, doneStart, done, null) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                setState(key) { it.copy(state = "Saving…") }
                finalize(site.id, username, groupStart, group, dir)
            }
        }
    }

    /** Converts captured parts into MP4(s) in the library and removes the raw files. */
    private suspend fun finalize(siteId: String, username: String, startedAt: Long, parts: List<PartFiles>, dirToRemove: File?) {
        finalizing.update { it + 1 }
        try {
            finalizeLock.withLock {
                val withData = parts.filter { it.hasData() }
                if (withData.isNotEmpty()) {
                    val outputs = try {
                        Remuxer.remux(withData) { Library.outputFile(siteId, username, startedAt) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Remux failed", e); emptyList()
                    }
                    if (outputs.isEmpty()) keepRaw(siteId, username, startedAt, withData)
                    withData.forEach { it.delete() }
                    val total = outputs.sumOf { it.length() }
                    events.value = "Saved $username" + if (total > 0) " (${total / (1024 * 1024)} MB)" else ""
                    notify("Saved recording of $username", "Open CamRec to watch it")
                }
                parts.forEach { it.delete() }
                dirToRemove?.let { d ->
                    if (PartFiles.existing(d).isEmpty()) d.deleteRecursively()
                }
            }
        } finally {
            finalizing.update { it - 1 }
        }
    }

    /** Remuxing failed: keep the captured stream as-is so nothing is lost. */
    private fun keepRaw(siteId: String, username: String, startedAt: Long, parts: List<PartFiles>) {
        for (p in parts) {
            val isTs = p.video.inputStream().use { it.read() } == 0x47
            val target = Library.outputFile(siteId, username, startedAt, "-raw", if (isTs) "ts" else "mp4")
            if (!p.video.renameTo(target)) p.video.copyTo(target, overwrite = true)
            if (p.audio.length() > 0) {
                val audio = Library.outputFile(siteId, username, startedAt, "-raw-audio", "mp4")
                if (!p.audio.renameTo(audio)) p.audio.copyTo(audio, overwrite = true)
            }
        }
    }

    /** Captures left behind when the app was killed mid-recording are saved on the next start. */
    fun recoverLeftovers() {
        scope.launch {
            val dirs = Library.partsRoot.listFiles()?.filter { it.isDirectory } ?: return@launch
            for (dir in dirs) {
                val meta = try { JSONObject(File(dir, "meta.json").readText()) } catch (_: Exception) { null }
                if (meta == null) { dir.deleteRecursively(); continue }
                val site = meta.getString("site"); val user = meta.getString("username")
                if (isRecording(Follow.key(site, user))) continue
                finalize(site, user, meta.optLong("startedAt", dir.lastModified()), PartFiles.existing(dir), dir)
            }
        }
    }

    // ---- Room status checks (monitor + manual refresh) -------------------------------

    private val checkLock = Mutex()

    fun isUnmetered(): Boolean {
        val cm = App.ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Checks every followed room once; starts auto-recordings when [autoStart] is set. */
    suspend fun checkFollows(autoStart: Boolean) = checkLock.withLock {
        for (f in Store.follows.value) {
            if (isRecording(f.key)) continue
            val site = Site.byId(f.site)
            val checked = try {
                CheckedStatus(site.status(f.username), null, System.currentTimeMillis())
            } catch (e: Exception) {
                CheckedStatus(null, e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            }
            _statuses.update { it + (f.key to checked) }
            val s = Store.settings.value
            if (autoStart && f.autoRecord && checked.status?.online == true && (!s.wifiOnly || isUnmetered())) {
                start(site, f.username, auto = true)
            }
        }
    }

    private var notificationId = 1000

    private fun notify(title: String, text: String) {
        val ctx = App.ctx
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, App.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(notificationId++, n) } catch (_: SecurityException) {}
    }
}
