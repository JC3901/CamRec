package com.jc.camrec.sites

import java.io.IOException

data class Cam(
    val site: String,
    val username: String,
    val title: String,
    val thumbnail: String?,
    val viewers: Int,
    val gender: String? = null,
    val tags: List<String> = emptyList(),
    val age: Int? = null,
)

data class CamPage(val cams: List<Cam>, val total: Int, val hasMore: Boolean)

data class RoomStatus(
    val online: Boolean,
    /** public, offline, private, group, away, ... */
    val state: String,
    val hlsUrl: String?,
    val viewers: Int = 0,
    val title: String? = null,
    val thumbnail: String? = null,
    /** Query parameters every playlist/segment URL of this stream needs (Stripchat's pkey). */
    val urlParams: Map<String, String> = emptyMap(),
)

enum class Gender(val label: String) { ALL("All"), FEMALE("Women"), MALE("Men"), COUPLE("Couples"), TRANS("Trans") }

/** The site answered with a check page (Cloudflare, age gate) instead of data. */
class SiteBlockedException(val site: Site, message: String) : IOException(message)

/** The site asked us to slow down. */
class RateLimitedException(message: String) : IOException(message)

interface Site {
    val id: String
    val name: String
    val homeUrl: String
    /** Headers needed to fetch this site's playlists and segments. */
    val streamHeaders: Map<String, String>

    fun pageUrl(username: String): String
    fun thumbnail(username: String): String?
    fun isValidUsername(username: String): Boolean

    /** Blocking: one page of public live rooms. */
    fun list(gender: Gender, search: String, offset: Int, limit: Int): CamPage

    /** Blocking: current state of one room, with its HLS master URL when public. */
    fun status(username: String): RoomStatus

    companion object {
        val all: List<Site> by lazy { listOf(Chaturbate, Stripchat) }
        fun byId(id: String): Site = all.firstOrNull { it.id == id } ?: Chaturbate

        /** Accepts "name", "chaturbate.com/name", "https://stripchat.com/name", ... */
        fun parseRoom(input: String, fallback: Site): Pair<Site, String>? {
            val text = input.trim().removePrefix("@")
            if (text.isEmpty()) return null
            val lower = text.lowercase()
            val site = when {
                "chaturbate." in lower -> Chaturbate
                "stripchat." in lower -> Stripchat
                else -> fallback
            }
            val name = if ("/" in text || Regex("\\.(com|net|org)\\b").containsMatchIn(lower)) {
                text.substringAfter("://").substringAfter("/", "").split('/', '?', '#').firstOrNull { it.isNotBlank() } ?: return null
            } else text
            return if (site.isValidUsername(name)) site to name else null
        }
    }
}
