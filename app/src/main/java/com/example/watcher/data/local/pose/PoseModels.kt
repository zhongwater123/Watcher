package com.example.watcher.data.local.pose

data class PoseDetectorConfig(
    val modelComplexity: ModelComplexity = ModelComplexity.LITE,
    val maxNumPoses: Int = 1,
    val minDetectionConfidence: Float = 0.5f,
    val minTrackingConfidence: Float = 0.5f,
    val delegateType: DelegateType = DelegateType.GPU
)

enum class ModelComplexity(val label: String) {
    LITE("Lite"),
    FULL("Full"),
    HEAVY("Heavy")
}

enum class DelegateType(val label: String) {
    GPU("GPU"),
    CPU("CPU")
}

enum class PoseDetectorState {
    Idle, Initializing, Ready, Error
}

data class PoseDetectionResult(
    val landmarks: List<PoseLandmarkSet>,
    val timestampMs: Long,
    val inferenceTimeMs: Long
)

data class PoseLandmarkSet(
    val normalizedLandmarks: List<NormalizedLandmark>,
    val worldLandmarks: List<WorldLandmark>?
)

data class NormalizedLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float
)

data class WorldLandmark(
    val x: Float,
    val y: Float,
    val z: Float
)

data class PosePerformanceStats(
    val fps: Int = 0,
    val inferenceTimeMs: Long = 0L,
    val detectedPoseCount: Int = 0,
    val delegateInUse: String = "",
    val modelComplexity: String = "",
    val frameResolution: String = ""
)

/**
 * BlazePose 33-point skeleton connections for rendering.
 * Each pair is (startIndex, endIndex) referencing the 33 landmark indices.
 */
object PoseSkeleton {
    val connections: List<Pair<Int, Int>> = listOf(
        // Face
        0 to 1, 1 to 2, 2 to 3, 3 to 7,
        0 to 4, 4 to 5, 5 to 6, 6 to 8,
        9 to 10,
        // Torso
        11 to 12, 11 to 23, 12 to 24, 23 to 24,
        // Left arm
        11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21, 17 to 19,
        // Right arm
        12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 18 to 20,
        // Left leg
        23 to 25, 25 to 27, 27 to 29, 27 to 31, 29 to 31,
        // Right leg
        24 to 26, 26 to 28, 28 to 30, 28 to 32, 30 to 32
    )
}
