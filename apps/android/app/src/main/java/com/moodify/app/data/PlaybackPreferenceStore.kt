package com.moodify.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class PlaybackMode { SEQUENTIAL, RANDOM, REPEAT_ONE }

internal data class PlaybackPreferences(
    val mode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val history: List<QueueItem> = emptyList(),
)

internal class PlaybackPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("rongjing_playback_preferences", Context.MODE_PRIVATE)

    fun read(): PlaybackPreferences = runCatching {
        val savedMode = preferences.getString("mode", null)
        val mode = if (savedMode == "RECOMMENDED") PlaybackMode.SEQUENTIAL else {
            PlaybackMode.valueOf(savedMode ?: PlaybackMode.SEQUENTIAL.name)
        }
        val history = buildList {
            val items = JSONArray(preferences.getString("history", "[]"))
            for (index in 0 until minOf(items.length(), 50)) {
                val item = items.getJSONObject(index)
                val path = item.optString("path")
                if (path.isNotBlank()) add(QueueItem(item.optString("title"), item.optString("subtitle"), path))
            }
        }
        PlaybackPreferences(mode, history)
    }.getOrDefault(PlaybackPreferences())

    fun writeMode(mode: PlaybackMode) {
        preferences.edit().putString("mode", mode.name).apply()
    }

    fun writeHistory(history: List<QueueItem>) {
        val encoded = JSONArray().apply {
            history.take(50).forEach { item ->
                put(JSONObject().put("title", item.title).put("subtitle", item.subtitle).put("path", item.path))
            }
        }
        preferences.edit().putString("history", encoded.toString()).apply()
    }
}
