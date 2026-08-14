package com.example.watcher.data.repository

internal object ClassroomKnowledgeTreeUpdatePolicy {
    const val INTERVAL_MS = 60_000L
    const val MIN_ADDED_CHARS = 450
    const val MAX_CONTEXT_CHARS = 4_000

    fun shouldUpdate(
        nowMs: Long,
        lastUpdateAtMs: Long,
        currentTranscriptLength: Int,
        lastTranscriptLength: Int,
        jobActive: Boolean
    ): Boolean {
        val addedChars = currentTranscriptLength - lastTranscriptLength
        return addedChars >= MIN_ADDED_CHARS &&
            nowMs - lastUpdateAtMs >= INTERVAL_MS &&
            !jobActive
    }

    fun shouldFlushOnStop(
        currentTranscriptLength: Int,
        lastTranscriptLength: Int,
        hasKnowledgeTree: Boolean
    ): Boolean {
        return currentTranscriptLength > 0 &&
            (!hasKnowledgeTree || currentTranscriptLength > lastTranscriptLength)
    }
}
