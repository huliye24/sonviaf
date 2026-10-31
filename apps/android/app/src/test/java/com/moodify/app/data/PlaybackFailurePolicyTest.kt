package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFailurePolicyTest {
    @Test
    fun `skips failed tracks and wraps once`() {
        assertEquals(3, PlaybackFailurePolicy.nextUnfailed(0, 5, setOf(0, 1, 2)))
        assertEquals(0, PlaybackFailurePolicy.nextUnfailed(4, 5, setOf(4)))
    }

    @Test
    fun `stops after every queue item failed`() {
        assertNull(PlaybackFailurePolicy.nextUnfailed(1, 3, setOf(0, 1, 2)))
        assertNull(PlaybackFailurePolicy.nextUnfailed(0, 0, emptySet()))
    }
}
