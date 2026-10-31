package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackQueuePolicyTest {
    @Test
    fun `absolute network and Android URIs are never rewritten`() {
        val base = "https://rongjingmusic.com"
        listOf(
            "https://rongjingmusic.com/audio/a.wav",
            "http://example.test/a.flac",
            "content://com.tencent.mobileqq.fileprovider/a",
            "file:///sdcard/Music/a.wav",
            "android.resource://com.moodify.app/drawable/cover",
        ).forEach { path -> assertEquals(path, PlaybackQueuePolicy.resolvePath(path, base)) }
    }

    @Test
    fun `backend paths resolve once against api v1`() {
        assertEquals(
            "https://api.example.test/api/v1/audio/works/1",
            PlaybackQueuePolicy.resolvePath("/audio/works/1", "https://api.example.test/"),
        )
        assertEquals(
            "https://api.example.test/api/v1/audio/works/1",
            PlaybackQueuePolicy.resolvePath("audio/works/1", "https://api.example.test"),
        )
    }

    @Test
    fun `external handoff preserves order title and URI`() {
        val input = listOf(
            "content://provider/second" to "第二首",
            "content://provider/first" to "第一首",
        )
        val queue = PlaybackQueuePolicy.externalItems(input)
        assertEquals(listOf("第二首", "第一首"), queue.map { it.title })
        assertEquals(input.map { it.first }, queue.map { it.path })
        assertEquals(listOf("本地音乐", "本地音乐"), queue.map { it.subtitle })
    }

    @Test
    fun `requested index is safely clamped`() {
        assertEquals(0, PlaybackQueuePolicy.clampIndex(-5, 3))
        assertEquals(1, PlaybackQueuePolicy.clampIndex(1, 3))
        assertEquals(2, PlaybackQueuePolicy.clampIndex(99, 3))
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackQueuePolicy.clampIndex(0, 0)
        }
    }
}
