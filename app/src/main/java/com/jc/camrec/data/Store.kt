package com.jc.camrec.data

import android.content.Context
import com.jc.camrec.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Follow(
    val site: String,
    val username: String,
    val autoRecord: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val key get() = key(site, username)

    companion object {
        fun key(site: String, username: String) = "$site/${username.lowercase()}"
    }
}

data class Settings(
    /** Watch followed rooms in the background and record them when they go live. */
    val monitor: Boolean = false,
    val pollSeconds: Int = 60,
    /** 0 = best available. */
    val maxHeight: Int = 0,
    /** Keep waiting this long for a room that dropped out, so short breaks end up in one video. */
    val mergeGapMinutes: Int = 10,
    val wifiOnly: Boolean = false,
    /** Split long recordings into files of at most this many minutes (0 = never). */
    val splitMinutes: Int = 0,
    val notifyOnStart: Boolean = true,
    val adultConfirmed: Boolean = false,
)

object Store {
    private val prefs by lazy { App.ctx.getSharedPreferences("camrec", Context.MODE_PRIVATE) }
    private val followsFile by lazy { File(App.ctx.filesDir, "follows.json") }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _follows = MutableStateFlow(loadFollows())
    val follows: StateFlow<List<Follow>> = _follows.asStateFlow()

    private fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            monitor = prefs.getBoolean("monitor", d.monitor),
            pollSeconds = prefs.getInt("pollSeconds", d.pollSeconds),
            maxHeight = prefs.getInt("maxHeight", d.maxHeight),
            mergeGapMinutes = prefs.getInt("mergeGapMinutes", d.mergeGapMinutes),
            wifiOnly = prefs.getBoolean("wifiOnly", d.wifiOnly),
            splitMinutes = prefs.getInt("splitMinutes", d.splitMinutes),
            notifyOnStart = prefs.getBoolean("notifyOnStart", d.notifyOnStart),
            adultConfirmed = prefs.getBoolean("adultConfirmed", d.adultConfirmed),
        )
    }

    fun updateSettings(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        prefs.edit()
            .putBoolean("monitor", s.monitor)
            .putInt("pollSeconds", s.pollSeconds)
            .putInt("maxHeight", s.maxHeight)
            .putInt("mergeGapMinutes", s.mergeGapMinutes)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putInt("splitMinutes", s.splitMinutes)
            .putBoolean("notifyOnStart", s.notifyOnStart)
            .putBoolean("adultConfirmed", s.adultConfirmed)
            .apply()
        _settings.value = s
    }

    private fun loadFollows(): List<Follow> = try {
        val arr = JSONArray(followsFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Follow(o.getString("site"), o.getString("username"), o.optBoolean("autoRecord", true), o.optLong("addedAt"))
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    private fun saveFollows(list: List<Follow>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("site", it.site).put("username", it.username)
                .put("autoRecord", it.autoRecord).put("addedAt", it.addedAt))
        }
        val tmp = File(followsFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(followsFile)
        _follows.value = list
    }

    fun follow(site: String, username: String) {
        val list = _follows.value
        if (list.any { it.key == Follow.key(site, username) }) return
        saveFollows(list + Follow(site, username))
    }

    fun unfollow(key: String) = saveFollows(_follows.value.filterNot { it.key == key })

    fun setAutoRecord(key: String, enabled: Boolean) =
        saveFollows(_follows.value.map { if (it.key == key) it.copy(autoRecord = enabled) else it })

    fun findFollow(site: String, username: String): Follow? = _follows.value.firstOrNull { it.key == Follow.key(site, username) }

    // Playback positions for the library player.
    fun position(path: String): Long = prefs.getLong("pos:$path", 0)
    fun savePosition(path: String, ms: Long) = prefs.edit().putLong("pos:$path", ms).apply()
    fun clearPosition(path: String) = prefs.edit().remove("pos:$path").apply()
}
