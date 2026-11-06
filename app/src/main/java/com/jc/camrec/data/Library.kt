package com.jc.camrec.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.jc.camrec.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Recording(
    val file: File,
    val site: String,
    val username: String,
    val startedAt: Long,
    val bytes: Long,
    val durationMs: Long,
)

object Library {
    val root: File by lazy {
        val dir = App.ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(App.ctx.filesDir, "recordings")
        dir.mkdirs(); dir
    }

    /** In-progress captures live here until they are finalized into MP4s. */
    val partsRoot: File get() = File(root, ".parts").apply { mkdirs() }

    private val stampFormat get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val durations = ConcurrentHashMap<String, Long>()

    fun stamp(time: Long): String = stampFormat.format(Date(time))

    fun outputFile(site: String, username: String, startedAt: Long, suffix: String = "", ext: String = "mp4"): File {
        val dir = File(root, "$site/$username").apply { mkdirs() }
        val base = "$username-${stamp(startedAt)}$suffix"
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$base-${n++}.$ext")
        return f
    }

    suspend fun list(): List<Recording> = withContext(Dispatchers.IO) {
        val out = ArrayList<Recording>()
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { siteDir ->
            siteDir.listFiles()?.filter { it.isDirectory }?.forEach { userDir ->
                userDir.listFiles()?.filter { it.isFile && (it.extension == "mp4" || it.extension == "ts") }?.forEach { f ->
                    out += Recording(f, siteDir.name, userDir.name, parseStart(f), f.length(), duration(f))
                }
            }
        }
        out.sortedByDescending { it.startedAt }
    }

    private fun parseStart(f: File): Long {
        val m = Regex("(\\d{8}-\\d{6})").find(f.name) ?: return f.lastModified()
        return try { stampFormat.parse(m.value)?.time ?: f.lastModified() } catch (_: Exception) { f.lastModified() }
    }

    private fun duration(f: File): Long {
        val key = "${f.path}:${f.length()}"
        durations[key]?.let { return it }
        val ms = try {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(f.path)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { release() }
            }
        } catch (_: Exception) { 0L }
        durations[key] = ms
        return ms
    }

    fun thumbnail(f: File): Bitmap? = try {
        MediaMetadataRetriever().run {
            try {
                setDataSource(f.path)
                getFrameAtTime(5_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width).toInt().coerceAtLeast(1), true) }
            } finally { release() }
        }
    } catch (_: Exception) { null }

    fun delete(r: Recording) {
        r.file.delete()
        Store.clearPosition(r.file.path)
        r.file.parentFile?.let { if (it.listFiles().isNullOrEmpty()) it.delete() }
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun share(context: Context, r: Recording) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", r.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, r.file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies a recording into the phone's shared Movies/CamRec folder (visible in Gallery). */
    suspend fun saveToGallery(context: Context, r: Recording): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, r.file.name)
            put(MediaStore.Video.Media.MIME_TYPE, if (r.file.extension == "ts") "video/mp2t" else "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CamRec/${r.username}")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out, 1 shl 20) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
