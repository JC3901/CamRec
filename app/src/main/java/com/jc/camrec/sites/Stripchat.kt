package com.jc.camrec.sites

import android.net.Uri
import com.jc.camrec.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ported from OpenEasyX plugins/stripchat + live-cam-discovery:
 * catalogue via /api/front/v2/models/get-list, room state and stream hosts from the
 * room page's __PRELOADED_STATE__, and the public playback key from the player module.
 */
object Stripchat : Site {
    override val id = "stripchat"
    override val name = "Stripchat"
    override val homeUrl = "https://stripchat.com/"
    override val streamHeaders = mapOf("Referer" to "https://stripchat.com/", "Origin" to "https://stripchat.com")

    private val usernameRe = Regex("^[a-z0-9_.-]{2,64}$", RegexOption.IGNORE_CASE)
    private val pkeyRe = Regex("\\.set\\(\\s*[\"']pkey[\"']\\s*,\\s*[\"']([a-z0-9_-]{12,128})[\"']\\s*\\)", RegexOption.IGNORE_CASE)
    private val nonPublic = setOf("private", "p2p", "groupshow", "virtualprivate", "idle", "off", "offline")

    private class Catalogue(val tag: String, val at: Long, val models: MutableList<Cam>, val ids: MutableList<Long>, var exhausted: Boolean)
    @Volatile private var catalogue: Catalogue? = null
    private val pkeys = HashMap<String, String>()

    override fun pageUrl(username: String) = "https://stripchat.com/${Uri.encode(username)}"
    override fun thumbnail(username: String): String? = null
    override fun isValidUsername(username: String) = usernameRe.matches(username)

    private fun primaryTag(g: Gender) = when (g) {
        Gender.MALE -> "men"; Gender.COUPLE -> "couples"; Gender.TRANS -> "trans"; else -> "girls"
    }

    private fun parseModel(o: JSONObject): Cam? {
        val username = o.str("username") ?: o.str("login") ?: return null
        val status = o.optString("status").lowercase()
        val online = o.optBoolean("isOnline") || o.optBoolean("isLive") || status == "public"
        if (!online || status in nonPublic) return null
        val idText = o.str("streamName") ?: o.optLong("id", -1).takeIf { it >= 0 }?.toString()
        val stamp = when {
            o.has("snapshotTimestamp") -> o.opt("snapshotTimestamp")?.toString()
            o.has("verifiedSnapshotTimestamp") -> o.opt("verifiedSnapshotTimestamp")?.toString()
            else -> null
        }
        var thumb = if (idText != null && stamp != null) "https://img.doppiocdn.net/snapshot/$idText/$stamp"
        else o.str("previewUrlThumbBig") ?: o.str("previewUrl") ?: o.str("avatarUrl")
        if (thumb?.startsWith("//") == true) thumb = "https:$thumb"
        else if (thumb?.startsWith("/") == true) thumb = "https://img.doppiocdn.net$thumb"
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } } ?: emptyList()
        return Cam(
            site = id, username = username,
            title = o.str("groupShowTopic") ?: username,
            thumbnail = thumb,
            viewers = o.optInt("viewersCount", o.optInt("viewers", 0)),
            gender = o.str("broadcastGender") ?: o.str("gender"),
            tags = tags, age = o.optInt("age", 0).takeIf { it in 18..80 },
        )
    }

    /** Loads one more batch of the live catalogue, excluding rooms already seen (Stripchat's own cursor). */
    private fun fetchBatch(c: Catalogue) {
        val body = JSONObject()
            .put("primaryTag", c.tag).put("limit", 60).put("topLimit", 60)
            .put("blockId", "topStreamsModels").put("blockUrl", "")
            .put("excludeModelIds", JSONArray(c.ids))
        val (code, text) = Http.postJson(
            "https://stripchat.com/api/front/v2/models/get-list", body.toString(),
            mapOf("Accept" to "application/json", "Origin" to "https://stripchat.com", "Referer" to "https://stripchat.com/${c.tag}"),
        )
        if (code == 403 || text.trimStart().startsWith("<")) {
            throw SiteBlockedException(this, "Stripchat showed a check page. Open it once in the in-app browser, then retry.")
        }
        if (code !in 200..299) throw java.io.IOException("Stripchat live rooms: HTTP $code")
        val models = JSONObject(text).optJSONArray("models") ?: JSONArray()
        var added = 0
        val known = c.models.map { it.username.lowercase() }.toHashSet()
        for (i in 0 until models.length()) {
            val o = models.optJSONObject(i) ?: continue
            val modelId = o.optLong("id", -1)
            if (modelId < 0 || modelId in c.ids) continue
            c.ids += modelId
            added++
            val cam = parseModel(o)
            if (cam != null && known.add(cam.username.lowercase())) c.models.add(cam)
        }
        if (added == 0 || models.length() < 60) c.exhausted = true
    }

    override fun list(gender: Gender, search: String, offset: Int, limit: Int): CamPage = synchronized(this) {
        val tag = primaryTag(gender)
        val existing = catalogue
        val c: Catalogue =
            if (existing == null || existing.tag != tag || (offset == 0 && System.currentTimeMillis() - existing.at > 60_000)) {
                Catalogue(tag, System.currentTimeMillis(), ArrayList(), ArrayList(), false).also { catalogue = it }
            } else existing
        val needle = search.trim().lowercase()
        fun matching() = if (needle.isEmpty()) c.models else c.models.filter { needle in it.username.lowercase() || it.tags.any { t -> needle in t.lowercase() } }
        var guard = 0
        while (!c.exhausted && matching().size < offset + limit && guard++ < (if (needle.isEmpty()) 2 else 12)) fetchBatch(c)
        val list = matching()
        val slice = list.drop(offset).take(limit)
        CamPage(slice, if (c.exhausted) list.size else maxOf(list.size, offset + slice.size + 1), offset + slice.size < list.size || !c.exhausted)
    }

    private fun preloadedState(html: String): JSONObject? {
        val marker = html.indexOf("window.__PRELOADED_STATE__")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                continue
            }
            when (ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return try { JSONObject(html.substring(start, i + 1)) } catch (_: Exception) { null }
            }
        }
        return null
    }

    private fun playbackKey(scriptUrl: String): String? {
        synchronized(pkeys) { pkeys[scriptUrl]?.let { return it } }
        val js = Http.getText(scriptUrl, streamHeaders)
        val key = pkeyRe.find(js)?.groupValues?.get(1) ?: return null
        synchronized(pkeys) { pkeys[scriptUrl] = key }
        return key
    }

    override fun status(username: String): RoomStatus {
        require(isValidUsername(username)) { "Invalid Stripchat room name" }
        val (code, html) = Http.getRaw(pageUrl(username), mapOf("Accept" to "text/html,*/*;q=0.8"))
        if (code == 404) return RoomStatus(false, "not found", null)
        if (code == 403 || code == 503) throw SiteBlockedException(this, "Stripchat showed a check page. Open it once in the in-app browser, then retry.")
        if (code !in 200..299) throw java.io.IOException("Stripchat room page: HTTP $code")
        val state = preloadedState(html)
            ?: throw SiteBlockedException(this, "Stripchat did not return the room data. Open it once in the in-app browser, then retry.")
        val model = state.optJSONObject("viewCam")?.optJSONObject("model") ?: return RoomStatus(false, "offline", null)
        val roomState = model.optString("status").lowercase().ifBlank { "offline" }
        val live = model.optBoolean("isLive") || model.optBoolean("isOnline")
        if (!live || roomState in nonPublic) return RoomStatus(false, roomState, null)

        val modelId = model.opt("id")?.toString() ?: return RoomStatus(false, roomState, null)
        val config = state.optJSONObject("configV3")
        val common = config?.optJSONObject("initialCommon")
        val featureSettings = config?.optJSONObject("static")?.optJSONObject("featureSettings")
        val features = state.optJSONObject("featuresConfig")?.optJSONObject("features")
        val playerModule = features?.optJSONObject("playerModuleExternalLoading") ?: featureSettings?.optJSONObject("playerModuleExternalLoading")
        val domains = LinkedHashSet<String>()
        featureSettings?.optJSONObject("hlsFallback")?.optJSONArray("fallbackDomains")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).ifBlank { null }?.let(domains::add)
        }
        listOf(model.str("hlsStreamHost"), common?.str("hlsStreamHost"), common?.str("defaultHlsStreamHost")).forEach { it?.let(domains::add) }
        common?.optJSONObject("hlsStreamHosts")?.let { hosts -> hosts.keys().forEach { k -> hosts.optString(k).ifBlank { null }?.let(domains::add) } }
        val origin = featureSettings?.str("MMPExternalUnitedSourceOrigin") ?: featureSettings?.str("MMPExternalSourceOrigin")
        val version = playerModule?.str("mmpVersion")
        if (domains.isEmpty() || origin == null || version == null) throw java.io.IOException("Stripchat did not expose this room's stream")
        val pkey = playbackKey("${origin.trimEnd('/')}/${Uri.encode(version)}/main.js")
            ?: throw java.io.IOException("Stripchat's player did not expose a playback key")
        val params = mapOf("pkey" to pkey)

        for (domain in domains) {
            val master = "https://edge-hls.$domain/hls/${Uri.encode(modelId)}/master/${Uri.encode(modelId)}_auto.m3u8"
            val ok = try {
                val text = Http.getText(Http.withParams(master, params), streamHeaders)
                text.trimStart().startsWith("#EXTM3U") && "#EXT-X-MOUFLON-ADVERT" !in text
            } catch (_: Exception) { false }
            if (ok) return RoomStatus(
                online = true, state = "public", hlsUrl = Http.withParams(master, params),
                viewers = model.optInt("viewersCount", 0), title = model.str("groupShowTopic"),
                urlParams = params,
            )
        }
        return RoomStatus(false, "starting", null)
    }
}
