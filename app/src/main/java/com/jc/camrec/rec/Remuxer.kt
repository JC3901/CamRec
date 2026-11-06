package com.jc.camrec.rec

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins captured parts into MP4 files without re-encoding (Android's MediaExtractor +
 * MediaMuxer, in place of OpenEasyX's `ffmpeg -c copy` concat step). Separate video and
 * audio captures are interleaved into one file; parts with matching formats are joined.
 */
object Remuxer {
    private const val TAG = "Remuxer"

    private class Source(val extractor: MediaExtractor, val tracks: Map<Int, Boolean /* isVideo */>)

    private fun MediaFormat.int(key: String): Int = if (containsKey(key)) getInteger(key) else 0

    private fun signature(video: MediaFormat, audio: MediaFormat?): String =
        listOf(
            video.getString(MediaFormat.KEY_MIME), video.int(MediaFormat.KEY_WIDTH), video.int(MediaFormat.KEY_HEIGHT),
            audio?.getString(MediaFormat.KEY_MIME), audio?.int(MediaFormat.KEY_SAMPLE_RATE), audio?.int(MediaFormat.KEY_CHANNEL_COUNT),
        ).joinToString("|")

    /** Opens a part; returns its sources plus its video and audio formats, or null when it has no video. */
    private fun open(part: PartFiles): Triple<List<Source>, MediaFormat, MediaFormat?>? {
        val sources = ArrayList<Source>()
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        val files = listOf(part.video) + (if (part.audio.length() > 0) listOf(part.audio) else emptyList())
        try {
            for (file in files) {
                val ex = MediaExtractor()
                try { ex.setDataSource(file.path) } catch (e: Exception) {
                    ex.release(); Log.w(TAG, "Cannot read ${file.name}: $e"); continue
                }
                val tracks = HashMap<Int, Boolean>()
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoFormat == null) { videoFormat = f; tracks[i] = true }
                    else if (mime.startsWith("audio/") && audioFormat == null) { audioFormat = f; tracks[i] = false }
                }
                if (tracks.isEmpty()) { ex.release(); continue }
                tracks.keys.forEach { ex.selectTrack(it) }
                sources += Source(ex, tracks)
            }
        } catch (e: Exception) {
            sources.forEach { it.extractor.release() }
            throw e
        }
        val vf = videoFormat
        if (vf == null) { sources.forEach { it.extractor.release() }; return null }
        return Triple(sources, vf, audioFormat)
    }

    /**
     * @param newOutput called for every output file needed (a format change starts a new file)
     * @return the MP4 files written
     */
    fun remux(parts: List<PartFiles>, newOutput: () -> File): List<File> {
        val outputs = ArrayList<File>()
        var muxer: MediaMuxer? = null
        var outFile: File? = null
        var videoTrack = -1
        var audioTrack = -1
        var currentSig: String? = null
        var offsetUs = 0L
        var written = 0L
        val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()

        fun close() {
            val m = muxer ?: return
            try { if (written > 0) m.stop() } catch (e: Exception) { Log.w(TAG, "stop failed: $e"); written = 0 }
            try { m.release() } catch (_: Exception) {}
            val f = outFile
            if (f != null) { if (written > 0 && f.length() > 0) outputs += f else f.delete() }
            muxer = null; outFile = null; written = 0; offsetUs = 0; currentSig = null
        }

        try {
            for (part in parts) {
                if (!part.hasData()) continue
                val opened = try { open(part) } catch (e: Exception) { Log.w(TAG, "open failed: $e"); null } ?: continue
                val (sources, vf, af) = opened
                try {
                    val sig = signature(vf, af)
                    if (muxer != null && sig != currentSig) close()
                    if (muxer == null) {
                        val f = newOutput()
                        val m = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        videoTrack = m.addTrack(vf)
                        audioTrack = if (af != null) m.addTrack(af) else -1
                        m.start()
                        muxer = m; outFile = f; currentSig = sig
                    }
                    val m = muxer!!
                    var baseUs = -1L
                    var lastUs = offsetUs
                    while (true) {
                        // Next sample in time order across the part's files.
                        val src = sources.filter { it.extractor.sampleTime >= 0 }.minByOrNull { it.extractor.sampleTime } ?: break
                        val ex = src.extractor
                        val isVideo = src.tracks[ex.sampleTrackIndex]
                        val time = ex.sampleTime
                        val flags = ex.sampleFlags
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        ex.advance()
                        if (size <= 0 || isVideo == null) continue
                        if (baseUs < 0) {
                            // Start on the first video key frame so the file opens cleanly.
                            if (!isVideo || (flags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) continue
                            baseUs = time
                        }
                        val outUs = time - baseUs + offsetUs
                        if (outUs < offsetUs) continue
                        if (!isVideo && audioTrack < 0) continue
                        info.set(0, size, outUs, if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        m.writeSampleData(if (isVideo) videoTrack else audioTrack, buffer, info)
                        written++
                        if (outUs > lastUs) lastUs = outUs
                    }
                    offsetUs = lastUs + 40_000
                } finally {
                    sources.forEach { try { it.extractor.release() } catch (_: Exception) {} }
                }
            }
        } finally {
            close()
        }
        return outputs
    }
}
