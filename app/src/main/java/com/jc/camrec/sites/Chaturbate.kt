package com.jc.camrec.sites

import android.net.Uri
import com.jc.camrec.net.Http
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Ported from OpenEasyX plugins/chaturbate (room list + chatvideocontext status). */
object Chaturbate : Site {
    override val id = "chaturbate"
    override val name = "Chaturbate"
    override val homeUrl = "https://chaturbate.com/"
    override val streamHeaders = mapOf("Referer" to "https://chaturbate.com/", "Origin" to "https://chaturbate.com")

    private val usernameRe = Regex("^[a-z0-9_]+$", RegexOption.IGNORE_CASE)
    private val validStates = setOf("public", "offline", "private", "group", "away", "hidden", "password", "notconnected")

    // The room list and status endpoints share an IP limit: serialize them and apply one cooldown.
    private val lock = Any()
    @Volatile private var retryAt = 0L

    override fun pageUrl(username: String) = "https://chaturbate.com/${username.lowercase()}/"
    override fun thumbnail(username: String) = "https://roomimg.stream.highwebmedia.com/ri/${Uri.encode(username.lowercase())}.jpg"
    override fun isValidUsername(username: String) = usernameRe.matches(username)

    private fun call(url: String, headers: Map<String, String>): Pair<Int, String> = synchronized(lock) {
        val wait = retryAt - System.currentTimeMillis()
        if (wait > 0) throw RateLimitedException("Chaturbate is limiting requests. Retrying in ${wait / 1000 + 1}s.")
        val result = try { Http.getRaw(url, headers) } catch (e: Exception) {
            retryAt = System.currentTimeMillis() + 20_000
            throw e
        }
        if (result.first == 429 || result.first == 503) {
            retryAt = System.currentTimeMillis() + 90_000
            throw RateLimitedException("Chaturbate is limiting requests (HTTP ${result.first}). Retrying in 90s.")
        }
        Thread.sleep(400)
        result
    }

    private fun parseJson(code: Int, body: String, what: String): JSONObject {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) {
            if (code == 403 || trimmed.startsWith("<")) {
                throw SiteBlockedException(this, "Chaturbate showed a check page instead of $what. Open it once in the in-app browser, then retry.")
            }
            throw java.io.IOException("Chaturbate $what: HTTP $code")
        }
        return try { JSONObject(trimmed) } catch (e: JSONException) { throw java.io.IOException("Chaturbate returned invalid $what") }
    }

    override fun list(gender: Gender, search: String, offset: Int, limit: Int): CamPage {
        val b = Uri.parse("https://chaturbate.com/api/ts/roomlist/room-list/").buildUpon()
            .appendQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .appendQueryParameter("offset", offset.toString())
        when (gender) {
            Gender.FEMALE -> "f"; Gender.MALE -> "m"; Gender.COUPLE -> "c"; Gender.TRANS -> "t"; Gender.ALL -> null
        }?.let { b.appendQueryParameter("genders", it) }
        if (search.isNotBlank()) b.appendQueryParameter("keywords", search.trim())
        val (code, body) = call(b.build().toString(), mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://chaturbate.com/",
        ))
        val json = parseJson(code, body, "the room list")
        var roomsAny: Any? = json.opt("rooms")
        if (roomsAny is JSONObject) {
            roomsAny = roomsAny.optJSONArray("rooms") ?: roomsAny.optJSONArray("results") ?: roomsAny.optJSONArray("items")
        }
        val rooms = roomsAny as? JSONArray ?: throw java.io.IOException("Chaturbate returned an invalid room list")
        val cams = ArrayList<Cam>()
        for (i in 0 until rooms.length()) parseRoom(rooms.optJSONObject(i) ?: continue)?.let(cams::add)
        val total = listOf("total_count", "totalCount", "num_total", "total")
            .firstNotNullOfOrNull { if (json.has(it)) json.optInt(it, -1).takeIf { n -> n >= 0 } else null } ?: (offset + cams.size)
        return CamPage(cams, total, offset + rooms.length() < total && rooms.length() > 0)
    }

    private fun parseRoom(o: JSONObject): Cam? {
        val username = o.str("username") ?: o.str("room") ?: o.str("slug") ?: return null
        if (!isValidUsername(username)) return null
        val status = (o.str("current_show") ?: o.str("room_status") ?: "public").lowercase()
        if (status != "public") return null
        var thumb = o.str("img") ?: o.str("thumbnail") ?: o.str("thumbnail_url")
        if (thumb?.startsWith("//") == true) thumb = "https:$thumb"
        val tags = when (val t = o.opt("tags")) {
            is JSONArray -> (0 until t.length()).mapNotNull { t.optString(it).trim().ifEmpty { null } }
            is String -> t.split(Regex("[\\s,#]+")).filter { it.isNotBlank() }
            else -> emptyList()
        }
        val age = (o.opt("age") ?: o.opt("display_age"))?.toString()?.toIntOrNull()?.takeIf { it in 18..80 }
        return Cam(
            site = id, username = username,
            title = o.str("room_subject") ?: o.str("subject") ?: username,
            thumbnail = thumb ?: thumbnail(username),
            viewers = o.optInt("num_users", o.optInt("viewers", 0)),
            gender = o.str("gender"), tags = tags.distinct(), age = age,
        )
    }

    override fun status(username: String): RoomStatus {
        require(isValidUsername(username)) { "Invalid Chaturbate room name" }
        val (code, body) = call("https://chaturbate.com/api/chatvideocontext/${Uri.encode(username.lowercase())}/", mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to pageUrl(username),
        ))
        if (code == 404) return RoomStatus(false, "not found", null)
        val json = parseJson(code, body, "the room status")
        val state = json.str("room_status")?.lowercase()
        if (state == null || state !in validStates) throw java.io.IOException("Chaturbate did not return a room status")
        val online = state == "public"
        val hls = if (online) json.str("hls_source") else null
        return RoomStatus(
            online = online && hls != null, state = state, hlsUrl = hls,
            viewers = if (online) json.optInt("num_viewers", 0) else 0,
            title = json.str("room_title"), thumbnail = thumbnail(username),
        )
    }
}

internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return if (v is String) v.trim().ifEmpty { null } else null
}
