package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSnapshotCodecTest {
    @Test
    fun `round trips queue index and position without credentials`() {
        val snapshot = PlaybackSnapshot(
            queue = listOf(
                QueueItem("第一首", "荣景音乐", "https://rongjingmusic.com/audio/a.m4a?v=1"),
                QueueItem("第二首", path = "/tracks/2/audio", isOriginal = true, mrsDelta = 0.25),
            ),
            queueIndex = 1,
            positionMs = 42_500,
        )

        val encoded = PlaybackSnapshotCodec.encode(snapshot)
        val decoded = PlaybackSnapshotCodec.decode(encoded)!!

        assertEquals(2, decoded.queue.size)
        assertEquals(1, decoded.queueIndex)
        assertEquals(42_500, decoded.positionMs)
        assertEquals("/tracks/2/audio", decoded.queue[1].path)
    }

    @Test
    fun `rejects unsupported schema and empty queue`() {
        assertNull(PlaybackSnapshotCodec.decode("""{"schema":2,"queue":[]}"""))
        assertNull(PlaybackSnapshotCodec.decode("""{"schema":1,"queue":[]}"""))
    }
}
