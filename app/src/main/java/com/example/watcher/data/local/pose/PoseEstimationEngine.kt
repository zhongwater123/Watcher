package com.example.watcher.data.local.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseEstimationEngine(private val context: Context) {

    private var poseLandmarker: PoseLandmarker? = null
    private var currentConfig: PoseDetectorConfig? = null
    private var actualDelegate: DelegateType = DelegateType.CPU
    private var currentMode: RunningMode = RunningMode.IMAGE

    val isReady: Boolean get() = poseLandmarker != null

    /**
     * Initialize for IMAGE mode (single images, real-time camera).
     */
    fun initialize(config: PoseDetectorConfig): Result<DelegateType> {
        return initializeWithMode(config, RunningMode.IMAGE)
    }

    /**
     * Initialize for VIDEO mode (sequential frames with tracking optimization).
     * Faster for offline video processing — uses temporal tracking between frames.
     */
    fun initializeForVideo(config: PoseDetectorConfig): Result<DelegateType> {
        return initializeWithMode(config, RunningMode.VIDEO)
    }

    /**
     * Detect pose in a single image (IMAGE mode).
     */
    fun detect(bitmap: Bitmap): PoseDetectionResult {
        val landmarker = poseLandmarker
            ?: return PoseDetectionResult(emptyList(), System.currentTimeMillis(), 0L)

        val startTime = System.nanoTime()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: PoseLandmarkerResult = landmarker.detect(mpImage)
        val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000L

        return mapResult(result, inferenceTimeMs)
    }

    /**
     * Detect pose in a video frame (VIDEO mode).
     * timestampMs must be strictly increasing between calls.
     */
    fun detectForVideo(bitmap: Bitmap, timestampMs: Long): PoseDetectionResult {
        val landmarker = poseLandmarker
            ?: return PoseDetectionResult(emptyList(), timestampMs, 0L)

        val startTime = System.nanoTime()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: PoseLandmarkerResult = landmarker.detectForVideo(mpImage, timestampMs)
        val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000L

        return mapResult(result, inferenceTimeMs)
    }

    fun release() {
        poseLandmarker?.close()
        poseLandmarker = null
        currentConfig = null
    }

    fun getActualDelegate(): DelegateType = actualDelegate

    private fun initializeWithMode(config: PoseDetectorConfig, mode: RunningMode): Result<DelegateType> {
        release()
        currentConfig = config
        currentMode = mode

        if (config.delegateType == DelegateType.GPU) {
            val gpuResult = tryCreateLandmarker(config, Delegate.GPU, mode)
            if (gpuResult.isSuccess) {
                poseLandmarker = gpuResult.getOrNull()
                actualDelegate = DelegateType.GPU
                Log.i(TAG, "PoseLandmarker initialized: mode=$mode, delegate=GPU")
                return Result.success(DelegateType.GPU)
            }
            Log.w(TAG, "GPU delegate failed, falling back to CPU: ${gpuResult.exceptionOrNull()?.message}")
        }

        val cpuResult = tryCreateLandmarker(config, Delegate.CPU, mode)
        if (cpuResult.isSuccess) {
            poseLandmarker = cpuResult.getOrNull()
            actualDelegate = DelegateType.CPU
            Log.i(TAG, "PoseLandmarker initialized: mode=$mode, delegate=CPU")
            return Result.success(DelegateType.CPU)
        }

        val error = cpuResult.exceptionOrNull() ?: IllegalStateException("Failed to create PoseLandmarker")
        Log.e(TAG, "Failed to initialize PoseLandmarker", error)
        return Result.failure(error)
    }

    private fun tryCreateLandmarker(
        config: PoseDetectorConfig,
        delegate: Delegate,
        mode: RunningMode
    ): Result<PoseLandmarker> {
        return runCatching {
            val modelAssetName = when (config.modelComplexity) {
                ModelComplexity.LITE -> MODEL_LITE
                ModelComplexity.FULL -> MODEL_FULL
                ModelComplexity.HEAVY -> MODEL_HEAVY
            }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetName)
                .setDelegate(delegate)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumPoses(config.maxNumPoses)
                .setMinPoseDetectionConfidence(config.minDetectionConfidence)
                .setMinTrackingConfidence(config.minTrackingConfidence)
                .setMinPosePresenceConfidence(config.minDetectionConfidence)
                .setRunningMode(mode)
                .build()

            PoseLandmarker.createFromOptions(context, options)
        }
    }

    private fun mapResult(result: PoseLandmarkerResult, inferenceTimeMs: Long): PoseDetectionResult {
        val landmarks = result.landmarks().mapIndexed { index, poseLandmarks ->
            val normalized = poseLandmarks.map { landmark ->
                NormalizedLandmark(
                    x = landmark.x(),
                    y = landmark.y(),
                    z = landmark.z(),
                    visibility = landmark.visibility().orElse(0f),
                    presence = landmark.presence().orElse(0f)
                )
            }
            val world = result.worldLandmarks().getOrNull(index)?.map { wl ->
                WorldLandmark(x = wl.x(), y = wl.y(), z = wl.z())
            }
            PoseLandmarkSet(normalizedLandmarks = normalized, worldLandmarks = world)
        }

        return PoseDetectionResult(
            landmarks = landmarks,
            timestampMs = System.currentTimeMillis(),
            inferenceTimeMs = inferenceTimeMs
        )
    }

    companion object {
        private const val TAG = "PoseEstimationEngine"
        private const val MODEL_LITE = "pose_landmarker_lite.task"
        private const val MODEL_FULL = "pose_landmarker_full.task"
        private const val MODEL_HEAVY = "pose_landmarker_heavy.task"
    }
}
