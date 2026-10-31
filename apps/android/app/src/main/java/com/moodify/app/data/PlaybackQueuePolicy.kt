package com.moodify.app.data

/** Pure queue/path rules kept outside ExoPlayer so playback behavior is unit-testable. */
object PlaybackQueuePolicy {
    private val absoluteScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun resolvePath(path: String, baseUrl: String): String {
        if (absoluteScheme.containsMatchIn(path)) return path
        val base = baseUrl.trimEnd('/')
        val endpoint = if (path.startsWith('/')) path else "/$path"
        return "$base/api/v1$endpoint"
    }

    fun clampIndex(requested: Int, queueSize: Int): Int {
        require(queueSize > 0) { "queue must not be empty" }
        return requested.coerceIn(0, queueSize - 1)
    }

    fun externalItems(items: List<Pair<String, String>>): List<QueueItem> = items.map { (uri, title) ->
        QueueItem(title = title, subtitle = "本地音乐", path = uri)
    }
}
