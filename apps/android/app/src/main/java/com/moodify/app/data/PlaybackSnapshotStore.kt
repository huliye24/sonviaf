package com.moodify.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class PlaybackSnapshot(
    val queue: List<QueueItem>,
    val queueIndex: Int,
    val positionMs: Long,
)

internal object PlaybackSnapshotCodec {
    private const val SCHEMA = 1
    private const val MAX_QUEUE_ITEMS = 500

    fun encode(snapshot: PlaybackSnapshot): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("queue_index", snapshot.queueIndex)
        put("position_ms", snapshot.positionMs.coerceAtLeast(0))
        put("queue", JSONArray().apply {
            snapshot.queue.take(MAX_QUEUE_ITEMS).forEach { item ->
                put(JSONObject().apply {
                    put("title", item.title)
                    put("subtitle", item.subtitle)
                    put("path", item.path)
                    put("is_original", item.isOriginal)
                    put("preset", item.preset)
                    put("mrs_delta", item.mrsDelta)
                    put("gate_passed", item.gatePassed)
                })
            }
        })
    }.toString()

    fun decode(raw: String): PlaybackSnapshot? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schema") != SCHEMA) return null
        val items = json.optJSONArray("queue") ?: return null
        if (items.length() !in 1..MAX_QUEUE_ITEMS) return null
        val queue = buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val path = item.optString("path")
                if (path.isBlank()) return null
                add(
                    QueueItem(
                        title = item.optString("title"),
                        subtitle = item.optString("subtitle"),
                        path = path,
                        isOriginal = item.optBoolean("is_original"),
                        preset = item.optString("preset"),
                        mrsDelta = if (item.has("mrs_delta") && !item.isNull("mrs_delta")) item.optDouble("mrs_delta") else null,
                        gatePassed = item.optBoolean("gate_passed"),
                    )
                )
            }
        }
        PlaybackSnapshot(
            queue = queue,
            queueIndex = json.optInt("queue_index").coerceIn(queue.indices),
            positionMs = json.optLong("position_ms").coerceAtLeast(0),
        )
    }.getOrNull()
}

internal class PlaybackSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences("rongjing_playback_state", Context.MODE_PRIVATE)

    fun read(): PlaybackSnapshot? = preferences.getString("snapshot", null)?.let(PlaybackSnapshotCodec::decode)

    fun write(snapshot: PlaybackSnapshot) {
        preferences.edit().putString("snapshot", PlaybackSnapshotCodec.encode(snapshot)).apply()
    }

    fun clear() {
        preferences.edit().remove("snapshot").apply()
    }
}
