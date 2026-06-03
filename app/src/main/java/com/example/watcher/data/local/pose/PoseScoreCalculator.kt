package com.example.watcher.data.local.pose

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Calculates pose matching score between user's live pose and reference pose.
 * Uses 12 joint angles comparison — independent of distance, position, and body type.
 */
object PoseScoreCalculator {

    /**
     * Joint angle definition: each is computed as the angle at the middle point
     * formed by three landmarks (a → b → c), where b is the joint.
     */
    private data class JointDef(val a: Int, val b: Int, val c: Int, val name: String)

    // 12 body joints: left/right × elbow, knee, shoulder, hip, wrist, ankle
    private val LEFT_JOINTS = listOf(
        JointDef(11, 13, 15, "L_Elbow"),   // shoulder → elbow → wrist
        JointDef(23, 25, 27, "L_Knee"),    // hip → knee → ankle
        JointDef(13, 11, 23, "L_Shoulder"),// elbow → shoulder → hip
        JointDef(11, 23, 25, "L_Hip"),     // shoulder → hip → knee
        JointDef(13, 15, 19, "L_Wrist"),   // elbow → wrist → index
        JointDef(25, 27, 31, "L_Ankle")    // knee → ankle → foot
    )

    private val RIGHT_JOINTS = listOf(
        JointDef(12, 14, 16, "R_Elbow"),
        JointDef(24, 26, 28, "R_Knee"),
        JointDef(14, 12, 24, "R_Shoulder"),
        JointDef(12, 24, 26, "R_Hip"),
        JointDef(14, 16, 20, "R_Wrist"),
        JointDef(26, 28, 32, "R_Ankle")
    )

    private val ALL_JOINTS = LEFT_JOINTS + RIGHT_JOINTS
    private val MIRROR_PAIRS = LEFT_JOINTS.zip(RIGHT_JOINTS)

    // Total scoring dimensions: 12 body joints + 1 head position = 13
    private const val TOTAL_SCORES = 13
    private const val HEAD_SCORE_IDX = 12

    /** Tolerance: ±30° (π/6) for dance-level precision */
    private const val ANGLE_TOLERANCE = Math.PI.toFloat() / 6f
    /** Only score joints where ALL 3 landmarks are confidently detected */
    private const val MIN_VISIBILITY = 0.65f

    data class ScoreResult(
        val frameScore: Float,          // 0.0-1.0 overall
        val jointScores: FloatArray     // per-joint scores (12 entries)
    )

    /**
     * Calculate frame-level matching score.
     *
     * @param userLandmarks User's live pose landmarks (33 points)
     * @param refLandmarks Reference pose landmarks (33 points)
     * @param mirrorMode If true, compare user's left with ref's right (照镜子)
     * @return ScoreResult with overall score and per-joint breakdown
     */
    fun calculateFrameScore(
        userLandmarks: List<NormalizedLandmark>,
        refLandmarks: List<NormalizedLandmark>,
        mirrorMode: Boolean = true
    ): ScoreResult {
        if (userLandmarks.size < 33 || refLandmarks.size < 33) {
            return ScoreResult(0f, FloatArray(TOTAL_SCORES))
        }

        val jointScores = FloatArray(TOTAL_SCORES)
        var scoredCount = 0

        if (mirrorMode) {
            MIRROR_PAIRS.forEachIndexed { i, (leftJoint, rightJoint) ->
                val userAngle = computeAngle(userLandmarks, leftJoint)
                val refAngle = computeAngle(refLandmarks, rightJoint)
                if (userAngle != null && refAngle != null) {
                    jointScores[i] = angleToScore(userAngle, refAngle)
                    scoredCount++
                }
            }
            MIRROR_PAIRS.forEachIndexed { i, (leftJoint, rightJoint) ->
                val userAngle = computeAngle(userLandmarks, rightJoint)
                val refAngle = computeAngle(refLandmarks, leftJoint)
                if (userAngle != null && refAngle != null) {
                    jointScores[i + 6] = angleToScore(userAngle, refAngle)
                    scoredCount++
                }
            }
        } else {
            ALL_JOINTS.forEachIndexed { i, joint ->
                val userAngle = computeAngle(userLandmarks, joint)
                val refAngle = computeAngle(refLandmarks, joint)
                if (userAngle != null && refAngle != null) {
                    jointScores[i] = angleToScore(userAngle, refAngle)
                    scoredCount++
                }
            }
        }

        // Head position: unified single parameter (nose relative to shoulder midpoint)
        val headScore = computeHeadPositionScore(userLandmarks, refLandmarks, mirrorMode)
        if (headScore != null) {
            jointScores[HEAD_SCORE_IDX] = headScore
            scoredCount++
        }

        // If fewer than 6 dimensions scoreable → not enough body visible
        val frameScore = if (scoredCount >= 6) {
            jointScores.sum() / TOTAL_SCORES.toFloat()
        } else {
            jointScores.sum() / TOTAL_SCORES.toFloat() * (scoredCount.toFloat() / 6f)
        }
        return ScoreResult(frameScore.coerceIn(0f, 1f), jointScores)
    }

    /**
     * Calculate move-level rating from accumulated frame scores.
     */
    fun rateMove(frameScores: List<Float>): MoveRating {
        if (frameScores.isEmpty()) return MoveRating.MISS
        val avg = frameScores.average().toFloat()
        return when {
            avg >= 0.85f -> MoveRating.PERFECT
            avg >= 0.70f -> MoveRating.GREAT
            avg >= 0.50f -> MoveRating.GOOD
            else -> MoveRating.MISS
        }
    }

    enum class MoveRating(val label: String, val scoreThreshold: Float) {
        PERFECT("Perfect!", 0.85f),
        GREAT("Great!", 0.70f),
        GOOD("Good", 0.50f),
        MISS("Miss", 0f)
    }

    // ── Internal ──

    /**
     * Head position score: compares the angle of (nose → shoulder midpoint) vector.
     * Captures head tilt, lean, and relative vertical position.
     */
    private fun computeHeadPositionScore(
        userLm: List<NormalizedLandmark>,
        refLm: List<NormalizedLandmark>,
        mirrorMode: Boolean
    ): Float? {
        val userNose = userLm[0]  // nose
        val userLShoulder = userLm[11]
        val userRShoulder = userLm[12]
        val refNose = refLm[0]
        val refLShoulder = refLm[11]
        val refRShoulder = refLm[12]

        // Check visibility
        if (userNose.visibility < MIN_VISIBILITY ||
            userLShoulder.visibility < MIN_VISIBILITY ||
            userRShoulder.visibility < MIN_VISIBILITY ||
            refNose.visibility < MIN_VISIBILITY ||
            refLShoulder.visibility < MIN_VISIBILITY ||
            refRShoulder.visibility < MIN_VISIBILITY) {
            return null
        }

        // Shoulder midpoint
        val userMidX = (userLShoulder.x + userRShoulder.x) / 2f
        val userMidY = (userLShoulder.y + userRShoulder.y) / 2f
        val refMidX = (refLShoulder.x + refRShoulder.x) / 2f
        val refMidY = (refLShoulder.y + refRShoulder.y) / 2f

        // Vector from shoulder midpoint to nose (head direction)
        val userDx = if (mirrorMode) -(userNose.x - userMidX) else (userNose.x - userMidX)
        val userDy = userNose.y - userMidY
        val refDx = refNose.x - refMidX
        val refDy = refNose.y - refMidY

        // Compare angles of head vectors
        val userAngle = kotlin.math.atan2(userDy, userDx)
        val refAngle = kotlin.math.atan2(refDy, refDx)
        val diff = abs(userAngle - refAngle)
        return (1f - diff / ANGLE_TOLERANCE).coerceIn(0f, 1f)
    }

    private fun computeAngle(landmarks: List<NormalizedLandmark>, joint: JointDef): Float? {
        if (joint.a >= landmarks.size || joint.b >= landmarks.size || joint.c >= landmarks.size) {
            return null
        }
        val a = landmarks[joint.a]
        val b = landmarks[joint.b]
        val c = landmarks[joint.c]

        // Skip if any landmark has low visibility
        if (a.visibility < MIN_VISIBILITY || b.visibility < MIN_VISIBILITY || c.visibility < MIN_VISIBILITY) {
            return null
        }

        val baX = a.x - b.x; val baY = a.y - b.y
        val bcX = c.x - b.x; val bcY = c.y - b.y

        val dot = baX * bcX + baY * bcY
        val magBA = sqrt(baX * baX + baY * baY).coerceAtLeast(0.0001f)
        val magBC = sqrt(bcX * bcX + bcY * bcY).coerceAtLeast(0.0001f)

        val cosAngle = (dot / (magBA * magBC)).coerceIn(-1f, 1f)
        return acos(cosAngle)
    }

    private fun angleToScore(userAngle: Float, refAngle: Float): Float {
        val diff = abs(userAngle - refAngle)
        return (1f - diff / ANGLE_TOLERANCE).coerceIn(0f, 1f)
    }
}
