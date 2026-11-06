package com.jc.camrec.hls

import com.jc.camrec.net.Http

data class Variant(val url: String, val bandwidth: Long, val height: Int, val audioGroup: String?)
data class AudioRendition(val group: String, val url: String?, val isDefault: Boolean)

data class Selection(val videoUrl: String, val audioUrl: String?, val height: Int, val params: Map<String, String> = emptyMap())

data class Segment(val seq: Long, val url: String, val duration: Double)

data class MediaPlaylist(
    val targetDuration: Double,
    val segments: List<Segment>,
    val mapUrl: String?,
    val ended: Boolean,
    val encrypted: Boolean,
)

/** HLS parsing and quality selection, ported from OpenEasyX plugins/hls-quality.ts. */
object Hls {
    private val attrRe = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    private fun attrs(line: String): Map<String, String> =
        attrRe.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun isMediaPlaylist(text: String) = "#EXTINF:" in text || "#EXT-X-PART:" in text

    fun parseMaster(text: String, base: String): Pair<List<Variant>, List<AudioRendition>> {
        val variants = ArrayList<Variant>()
        val audio = ArrayList<AudioRendition>()
        var info: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attrs(line)
                    if (a["TYPE"] == "AUDIO") audio += AudioRendition(
                        group = a["GROUP-ID"] ?: "", url = a["URI"]?.let { Http.resolve(base, it) },
                        isDefault = a["DEFAULT"] == "YES",
                    )
                }
                line.startsWith("#EXT-X-STREAM-INF") -> info = attrs(line)
                line.isEmpty() || line.startsWith("#") -> Unit
                info != null -> {
                    val a = info!!
                    val url = Http.resolve(base, line)
                    val height = a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()
                        ?: Regex("(\\d{3,4})p").find(a["NAME"] ?: line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    variants += Variant(url, a["BANDWIDTH"]?.toLongOrNull() ?: 0, height, a["AUDIO"])
                    info = null
                }
            }
        }
        return variants to audio
    }

    /** Best variant at or below maxHeight; if none fits, the closest larger one. 0 = highest. */
    fun order(variants: List<Variant>, maxHeight: Int): List<Variant> {
        if (maxHeight <= 0 || variants.none { it.height > 0 }) {
            return variants.sortedWith(compareByDescending<Variant> { it.bandwidth }.thenByDescending { it.height })
        }
        val fitting = variants.filter { it.height in 1..maxHeight }
            .sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
        val larger = variants.filter { it.height > maxHeight }
            .sortedWith(compareBy<Variant> { it.height }.thenByDescending { it.bandwidth })
        val unknown = variants.filter { it.height <= 0 }.sortedByDescending { it.bandwidth }
        return fitting + larger + unknown
    }

    /** Fetches the master playlist and picks the video (and separate audio, if any) playlists to record. */
    fun select(masterUrl: String, headers: Map<String, String>, maxHeight: Int, params: Map<String, String> = emptyMap()): Selection {
        val text = Http.getText(Http.withParams(masterUrl, params), headers)
        if (!text.trimStart().startsWith("#EXTM3U")) throw java.io.IOException("The stream address did not return a playlist")
        if (isMediaPlaylist(text)) return Selection(Http.withParams(masterUrl, params), null, 0, params)
        if ("#EXT-X-MOUFLON" in text) throw java.io.IOException("This room uses an obfuscated stream format that can't be recorded yet")
        val (variants, audio) = parseMaster(text, masterUrl)
        // Prefer a variant that actually answers with a live playlist (OpenEasyX does the same).
        val ordered = order(variants, maxHeight)
        val best = ordered.firstOrNull { v ->
            try { isMediaPlaylist(Http.getText(Http.withParams(v.url, params), headers)) } catch (_: Exception) { false }
        } ?: ordered.firstOrNull() ?: throw java.io.IOException("The stream has no playable quality")
        val group = best.audioGroup
        val audioUrl = if (group == null) null else {
            val inGroup = audio.filter { it.group == group }
            (inGroup.firstOrNull { it.isDefault && it.url != null } ?: inGroup.firstOrNull { it.url != null })?.url
        }
        return Selection(Http.withParams(best.url, params), audioUrl?.let { Http.withParams(it, params) }, best.height, params)
    }

    fun parseMedia(text: String, base: String): MediaPlaylist {
        var target = 4.0
        var seq = 0L
        var mapUrl: String? = null
        var ended = false
        var encrypted = false
        var pendingDuration: Double? = null
        val segments = ArrayList<Segment>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').toDoubleOrNull() ?: target
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-MAP:") -> attrs(line)["URI"]?.let { mapUrl = Http.resolve(base, it) }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attrs(line)["METHOD"]
                    if (method != null && method != "NONE") encrypted = true
                }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXTINF:") -> pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                line.isEmpty() || line.startsWith("#") -> Unit
                pendingDuration != null -> {
                    segments += Segment(seq++, Http.resolve(base, line), pendingDuration!!)
                    pendingDuration = null
                }
            }
        }
        return MediaPlaylist(target, segments, mapUrl, ended, encrypted)
    }
}
