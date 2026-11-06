package com.jc.camrec.rec

import com.jc.camrec.hls.Hls
import com.jc.camrec.hls.Selection
import com.jc.camrec.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class EndReason { STOPPED, ENDED, STALLED, FAILED, SPLIT }

/** One continuous capture of a stream: a video file and, for streams with separate audio, an audio file. */
class PartFiles(dir: File, val index: Int) {
    val video = File(dir, "part-%03d-v.bin".format(index))
    val audio = File(dir, "part-%03d-a.bin".format(index))

    fun hasData() = video.length() > 0
    fun delete() { video.delete(); audio.delete() }

    companion object {
        /** Parts already on disk in a capture folder, in order. */
        fun existing(dir: File): List<PartFiles> =
            (dir.listFiles() ?: emptyArray())
                .mapNotNull { Regex("part-(\\d+)-v\\.bin").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                .sorted().map { PartFiles(dir, it) }
    }
}

/**
 * Native replacement for OpenEasyX's FFmpeg capture: polls the live media playlist(s)
 * and appends every new segment to the part files, without re-encoding.
 */
class Capture(
    private val selection: Selection,
    private val headers: Map<String, String>,
    private val part: PartFiles,
    private val stop: AtomicBoolean,
    private val splitAtMs: Long?,
    private val onBytes: (Long) -> Unit,
) {
    private val done = AtomicBoolean(false)
    val skippedSegments = AtomicLong(0)

    suspend fun run(): EndReason = coroutineScope {
        val video = async { track(selection.videoUrl, part.video, isVideo = true).also { done.set(true) } }
        val audio = selection.audioUrl?.let { url -> async { track(url, part.audio, isVideo = false).also { done.set(true) } } }
        val v = video.await()
        val a = audio?.await()
        if (v == EndReason.STOPPED || a == EndReason.STOPPED) EndReason.STOPPED
        else if (v == EndReason.SPLIT) EndReason.SPLIT
        else if (a != null && a != EndReason.ENDED && v == EndReason.ENDED) a
        else v
    }

    private fun fetch(url: String): ByteArray? {
        repeat(3) { attempt ->
            try { return Http.getBytes(Http.withParams(url, selection.params), headers) } catch (e: IOException) {
                if (attempt < 2) Thread.sleep(700L * (attempt + 1))
            }
        }
        return null
    }

    private fun stripQuery(url: String) = url.substringBefore('?')

    private suspend fun track(url: String, file: File, isVideo: Boolean): EndReason {
        return FileOutputStream(file, true).use { out ->
            var lastSeq = -1L
            var mapWritten: String? = null
            var lastGrowth = System.currentTimeMillis()
            var failures = 0
            while (!stop.get()) {
                // The other track finished: end this part together with it.
                if (done.get()) return EndReason.ENDED

                val playlist = try {
                    Hls.parseMedia(Http.getText(url, headers), url).also { failures = 0 }
                } catch (e: IOException) {
                    if (++failures >= 5) return EndReason.FAILED
                    delay(2000)
                    continue
                }
                if (playlist.encrypted) throw IOException("This stream is encrypted and cannot be recorded")

                playlist.mapUrl?.let { map ->
                    if (mapWritten == null) {
                        val init = fetch(map) ?: return EndReason.FAILED
                        out.write(init)
                        mapWritten = stripQuery(map)
                    } else if (stripQuery(map) != mapWritten) {
                        return EndReason.ENDED // The stream changed format: start a new part.
                    }
                }

                val segments = playlist.segments
                if (lastSeq >= 0 && segments.isNotEmpty() && segments.last().seq + 10 < lastSeq) {
                    return EndReason.ENDED // The sequence restarted: the broadcast was restarted.
                }
                val fresh = if (lastSeq < 0) segments.takeLast(3) else segments.filter { it.seq > lastSeq }
                if (lastSeq >= 0 && fresh.isNotEmpty() && fresh.first().seq > lastSeq + 1) {
                    skippedSegments.addAndGet(fresh.first().seq - lastSeq - 1)
                }
                for (segment in fresh) {
                    if (stop.get()) break
                    val bytes = fetch(segment.url)
                    if (bytes != null && bytes.isNotEmpty()) {
                        out.write(bytes)
                        lastGrowth = System.currentTimeMillis()
                        onBytes(bytes.size.toLong())
                    } else skippedSegments.incrementAndGet()
                    lastSeq = segment.seq
                }
                out.flush()

                if (playlist.ended) return EndReason.ENDED
                val now = System.currentTimeMillis()
                if (now - lastGrowth > 60_000) return EndReason.STALLED
                if (isVideo && splitAtMs != null && now >= splitAtMs) return EndReason.SPLIT
                delay((playlist.targetDuration * 500).toLong().coerceIn(1000, 5000))
            }
            EndReason.STOPPED
        }
    }
}
