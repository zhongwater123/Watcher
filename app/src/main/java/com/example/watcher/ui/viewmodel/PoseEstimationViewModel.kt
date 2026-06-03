package com.example.watcher.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.watcher.data.local.pose.DelegateType
import com.example.watcher.data.local.pose.ModelComplexity
import com.example.watcher.data.local.pose.PoseDetectionResult
import com.example.watcher.data.local.pose.PoseDetectorConfig
import com.example.watcher.data.local.pose.PoseDetectorState
import com.example.watcher.data.local.pose.PoseEstimationEngine
import com.example.watcher.data.local.pose.PosePerformanceStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PoseEstimationViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = PoseEstimationEngine(application)

    private val _detectorState = MutableStateFlow(PoseDetectorState.Idle)
    val detectorState: StateFlow<PoseDetectorState> = _detectorState.asStateFlow()

    private val _detectorConfig = MutableStateFlow(PoseDetectorConfig())
    val detectorConfig: StateFlow<PoseDetectorConfig> = _detectorConfig.asStateFlow()

    private val _poseResult = MutableStateFlow<PoseDetectionResult?>(null)
    val poseResult: StateFlow<PoseDetectionResult?> = _poseResult.asStateFlow()

    private val _performanceStats = MutableStateFlow(PosePerformanceStats())
    val performanceStats: StateFlow<PosePerformanceStats> = _performanceStats.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Frame processing gate: only one frame processed at a time
    private val processing = AtomicBoolean(false)

    // FPS tracking
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var currentFps = 0

    fun initDetector(config: PoseDetectorConfig = _detectorConfig.value) {
        _detectorConfig.value = config
        _detectorState.value = PoseDetectorState.Initializing
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.Default) {
            val result = engine.initialize(config)
            result.fold(
                onSuccess = { actualDelegate ->
                    _detectorState.value = PoseDetectorState.Ready
                    _performanceStats.value = _performanceStats.value.copy(
                        delegateInUse = actualDelegate.label,
                        modelComplexity = config.modelComplexity.label
                    )
                },
                onFailure = { error ->
                    _detectorState.value = PoseDetectorState.Error
                    _errorMessage.value = error.message ?: "Unknown initialization error"
                }
            )
        }
    }

    fun processFrame(bitmap: Bitmap) {
        if (_detectorState.value != PoseDetectorState.Ready) return
        // Skip frame if previous detection is still running
        if (!processing.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = runCatching { engine.detect(bitmap) }.getOrNull()
                if (result != null) {
                    _poseResult.value = result

                    // Update FPS
                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsTimestamp
                    if (elapsed >= 1000L) {
                        currentFps = (frameCount * 1000L / elapsed).toInt()
                        frameCount = 0
                        lastFpsTimestamp = now
                    }

                    _performanceStats.value = _performanceStats.value.copy(
                        fps = currentFps,
                        inferenceTimeMs = result.inferenceTimeMs,
                        detectedPoseCount = result.landmarks.size,
                        frameResolution = "${bitmap.width}x${bitmap.height}"
                    )
                }
            } finally {
                processing.set(false)
            }
        }
    }

    fun updateConfig(config: PoseDetectorConfig) {
        if (config == _detectorConfig.value && _detectorState.value == PoseDetectorState.Ready) return
        initDetector(config)
    }

    fun updateModelComplexity(complexity: ModelComplexity) {
        updateConfig(_detectorConfig.value.copy(modelComplexity = complexity))
    }

    fun updateDelegateType(delegate: DelegateType) {
        updateConfig(_detectorConfig.value.copy(delegateType = delegate))
    }

    fun updateMaxNumPoses(maxPoses: Int) {
        updateConfig(_detectorConfig.value.copy(maxNumPoses = maxPoses.coerceIn(1, 5)))
    }

    fun updateDetectionConfidence(confidence: Float) {
        updateConfig(_detectorConfig.value.copy(minDetectionConfidence = confidence))
    }

    override fun onCleared() {
        super.onCleared()
        engine.release()
    }
}
