package com.moodify.app.data

/** Deterministic recovery rule: try every other queue item once, then stop. */
internal object PlaybackFailurePolicy {
    fun nextUnfailed(current: Int, queueSize: Int, failed: Set<Int>): Int? {
        if (queueSize <= 0) return null
        for (offset in 1..queueSize) {
            val candidate = (current + offset).mod(queueSize)
            if (candidate !in failed) return candidate
        }
        return null
    }
}
