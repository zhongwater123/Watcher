package com.example.watcher.data.local.pose

/**
 * Hierarchical dance segmentation output.
 * Level 1: Atomic moves (1-3s each)
 * Level 2: Phrases (groups of atomic moves)
 */
data class DanceSegmentation(
    val sessionId: Long,
    val totalDurationMs: Long,
    val fps: Int,
    val atomicMoves: List<MoveSegment>,
    val phrases: List<PhraseSegment>,
    val velocityCurve: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DanceSegmentation) return false
        return sessionId == other.sessionId
    }
    override fun hashCode(): Int = sessionId.hashCode()
}

data class MoveSegment(
    val id: String,           // "move_01"
    val startMs: Long,
    val endMs: Long,
    val startFrame: Int,
    val endFrame: Int,
    val peakVelocity: Float,
    val boundaryType: String  // "motion" | "rhythm_snap" | "structure"
)

data class PhraseSegment(
    val id: String,           // "phrase_A"
    val startMs: Long,
    val endMs: Long,
    val moveIds: List<String>,
    val difficulty: Float     // 0.0 - 1.0, based on peak velocity relative to max
)
