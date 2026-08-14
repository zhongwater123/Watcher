package com.example.watcher.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorDecision
import com.example.watcher.data.model.MonitorLogAction
import com.example.watcher.data.model.MonitorLogEntry
import com.example.watcher.data.model.MonitorMediaType
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.MonitorRunStatus
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.TargetTrigger
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoImageRequest
import com.example.watcher.data.remote.ImageContentItem
import com.example.watcher.data.remote.ImageMessage
import com.example.watcher.data.remote.extractOutputText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class MonitorManager(
    private val apiService: DoubaoApiService,
    private val alertNotifier: AlertNotifier = NoOpAlertNotifier,
    private val historyRepository: HistoryRepository? = null,
    private val snapshotStore: SnapshotStore? = null,
    private var ledController: LedController? = null,
    private val llmWalletRepository: LlmWalletRepository
) {
    private val tag = "MonitorManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val apiKey = ArkConfig.apiKey
    private val model = ArkConfig.monitorModel

    private var monitorJob: Job? = null
    private var autoLightJob: Job? = null
    private var currentTask: IntentResult? = null
    private var currentSettings = VideoStreamSettings()
    private var lastAnalyzedFrame: Bitmap? = null
    private var encodedCurrentFrame: EncodedFrame? = null
    private var lastLogFingerprint: String? = null
    private var lastLogAt = 0L
    private var lastNotifiedResult = CheckResult.NONE
    private var lastNotificationAt = 0L
    private var currentRunId: Long? = null
    private var currentRunDirectory: String? = null
    private var baselineImagePath: String? = null
    private var sessionVideoPath: String? = null
    private var sessionRecorder = MonitorSessionRecorder()

    private val _monitorStatus = MutableStateFlow(MonitorStatus())
    val monitorStatus: StateFlow<MonitorStatus> = _monitorStatus.asStateFlow()

    private val _monitorLogs = MutableStateFlow<List<MonitorLogEntry>>(emptyList())
    val monitorLogs: StateFlow<List<MonitorLogEntry>> = _monitorLogs.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    val currentStreamUrl: String?
        get() = currentSettings.streamUrl.takeIf { _currentFrame.value != null }

    fun updateSettings(settings: VideoStreamSettings) {
        currentSettings = settings
    }

    fun setCurrentFrame(bitmap: Bitmap?) {
        _currentFrame.value = bitmap
    }

    fun startMonitoring(task: IntentResult, runId: Long? = null) {
        stopMonitoring()
        currentTask = task.normalized()
        currentRunId = runId
        currentRunDirectory = runId?.let { "MonitorHistory/run_$it" }
        baselineImagePath = currentTask?.baselineImagePath
        sessionVideoPath = null
        val intervalMs = currentTask?.checkInterval?.times(1_000L) ?: 15_000L
        _monitorStatus.value = MonitorStatus(
            isRunning = true,
            nextCheckTime = System.currentTimeMillis() + intervalMs,
            effectiveBaselineImagePath = baselineImagePath
        )

        syncExistingBaselinePathIfNeeded()
        captureBaselineIfNeeded()
        startSessionRecordingIfPossible()
        addLog(
            result = CheckResult.NONE,
            message = "开始监控：${currentTask?.title.orEmpty()}",
            action = MonitorLogAction.TASK
        )

        if (currentSettings.ledControlEnabled && currentSettings.ledAutoLightEnabled) {
            autoLightJob = scope.launch {
                ledController?.startAutoLight { _currentFrame.value }
            }
        }

        monitorJob = scope.launch {
            while (isActive && _monitorStatus.value.isRunning) {
                if (!_monitorStatus.value.isPaused) {
                    performCheckWithWallet()
                }

                _monitorStatus.value = _monitorStatus.value.copy(
                    nextCheckTime = System.currentTimeMillis() + intervalMs
                )
                delay(intervalMs)
            }
        }
    }

    private suspend fun performCheckWithWallet() {
        captureBaselineIfNeeded()

        val llmConfig = runCatching {
            llmWalletRepository.resolveArkResponsesConfig(ArkConfig.monitorModel)
        }.getOrElse {
            recordFailure("未配置全局 LLM 钱包，且 API_KEY 为空。")
            return
        }

        val frame = _currentFrame.value
        if (frame == null) {
            recordFailure("当前没有可用的视频帧。")
            return
        }

        val task = currentTask
        if (task == null) {
            recordFailure("当前未加载监控任务。")
            return
        }

        val changeScore = lastAnalyzedFrame?.let { calculateFrameChange(it, frame) }
        if (task.monitorMode == MonitorMode.SceneBaseline &&
            currentSettings.changeDetectionEnabled &&
            changeScore != null &&
            changeScore < currentSettings.changeThresholdPercent
        ) {
            _monitorStatus.value = _monitorStatus.value.copy(
                skippedCount = _monitorStatus.value.skippedCount + 1
            )
            addLog(
                result = _monitorStatus.value.lastResult,
                message = "已跳过模型调用，场景变化 ${changeScore}%，阈值 ${currentSettings.changeThresholdPercent}%。",
                action = MonitorLogAction.SKIP
            )
            return
        }

        val eventFramePath = saveEventFrame(frame)
        if (eventFramePath != null) {
            _monitorStatus.value = _monitorStatus.value.copy(lastAnalyzedImagePath = eventFramePath)
        }
        try {
            val currentDataUri = encodeCurrentFrame(frame)
            val response = apiService.analyzeImage(
                authorization = llmConfig.bearerToken(),
                request = buildDetectionRequest(task, currentDataUri, llmConfig.modelName)
            )

            val content = response.extractOutputText()
                ?: throw IllegalStateException("接口返回内容为空。")

            val decision = ModelOutputParser.parseMonitorDecision(content)
            lastAnalyzedFrame = snapshotBitmap(frame)
            applyDecision(decision, eventFramePath)
        } catch (error: Exception) {
            Log.e(tag, "Detection failed", error)
            recordFailure(error.message ?: "监控请求失败。", eventFramePath)
        }
    }

    fun pauseMonitoring() {
        alertNotifier.stopAlerting()
        _monitorStatus.value = _monitorStatus.value.copy(isPaused = true)
        addLog(CheckResult.NONE, "监控已暂停", MonitorLogAction.TASK)
    }

    fun resumeMonitoring() {
        _monitorStatus.value = _monitorStatus.value.copy(isPaused = false)
        addLog(CheckResult.NONE, "监控已继续", MonitorLogAction.TASK)
    }

    fun stopMonitoring() {
        val wasRunning = _monitorStatus.value.isRunning
        val endingRunId = currentRunId
        val endingTask = currentTask
        val endingStatus = _monitorStatus.value.copy(
            isRunning = false,
            isPaused = false,
            nextCheckTime = 0L
        )
        val recorderToStop = sessionRecorder
        sessionRecorder = MonitorSessionRecorder()

        monitorJob?.cancel()
        monitorJob = null
        autoLightJob?.cancel()
        autoLightJob = null
        lastAnalyzedFrame = null
        encodedCurrentFrame = null
        lastLogFingerprint = null
        lastNotificationAt = 0L
        lastNotifiedResult = CheckResult.NONE
        alertNotifier.stopAlerting()

        scope.launch {
            ledController?.turnOff()
        }

        if (wasRunning) {
            addLog(CheckResult.NONE, "监控已停止", MonitorLogAction.TASK)
            scope.launch {
                val recordedVideoPath = recorderToStop.stop()
                historyRepository?.syncMonitorRunState(
                    runId = endingRunId ?: return@launch,
                    task = endingTask,
                    status = endingStatus,
                    runStatus = MonitorRunStatus.Completed,
                    baselineImagePath = baselineImagePath,
                    sessionVideoPath = recordedVideoPath ?: sessionVideoPath,
                    endedAt = System.currentTimeMillis()
                )
            }
        }

        _monitorStatus.value = MonitorStatus()
        currentTask = null
        currentRunId = null
        currentRunDirectory = null
        baselineImagePath = null
        sessionVideoPath = null
    }

    fun setLedController(controller: LedController?) {
        ledController = controller
    }

    fun release() {
        stopMonitoring()
        scope.cancel()
    }

    fun attachSnapshot(path: String) {
        val runId = currentRunId ?: return
        scope.launch {
            historyRepository?.addMonitorMedia(
                runId = runId,
                localFilePath = path,
                mediaType = MonitorMediaType.Snapshot
            )
            historyRepository?.syncMonitorRunState(
                runId = runId,
                task = currentTask,
                status = _monitorStatus.value,
                runStatus = currentRunLifecycleStatus(),
                baselineImagePath = baselineImagePath,
                sessionVideoPath = sessionVideoPath
            )
        }
    }

    private suspend fun performCheck() {
        captureBaselineIfNeeded()

        if (apiKey.isBlank()) {
            recordFailure("未配置 API_KEY，请先在 local.properties 中设置后再开始监控。")
            return
        }

        val frame = _currentFrame.value
        if (frame == null) {
            recordFailure("当前没有可用的视频帧。")
            return
        }

        val task = currentTask
        if (task == null) {
            recordFailure("当前未加载监控任务。")
            return
        }

        val changeScore = lastAnalyzedFrame?.let { calculateFrameChange(it, frame) }
        if (task.monitorMode == MonitorMode.SceneBaseline &&
            currentSettings.changeDetectionEnabled &&
            changeScore != null &&
            changeScore < currentSettings.changeThresholdPercent
        ) {
            _monitorStatus.value = _monitorStatus.value.copy(
                skippedCount = _monitorStatus.value.skippedCount + 1
            )
            addLog(
                result = _monitorStatus.value.lastResult,
                message = "已跳过模型调用，场景变化 ${changeScore}%，阈值 ${currentSettings.changeThresholdPercent}%。",
                action = MonitorLogAction.SKIP
            )
            return
        }

        val eventFramePath = saveEventFrame(frame)
        if (eventFramePath != null) {
            _monitorStatus.value = _monitorStatus.value.copy(lastAnalyzedImagePath = eventFramePath)
        }
        try {
            val currentDataUri = encodeCurrentFrame(frame)
            val response = apiService.analyzeImage(
                authorization = "Bearer $apiKey",
                request = buildDetectionRequest(task, currentDataUri)
            )

            val content = response.extractOutputText()
                ?: throw IllegalStateException("接口返回内容为空。")

            val decision = ModelOutputParser.parseMonitorDecision(content)
            lastAnalyzedFrame = snapshotBitmap(frame)
            applyDecision(decision, eventFramePath)
        } catch (error: Exception) {
            Log.e(tag, "Detection failed", error)
            recordFailure(error.message ?: "监控请求失败。", eventFramePath)
        }
    }

    private fun applyDecision(
        decision: MonitorDecision,
        eventFramePath: String?
    ) {
        val now = System.currentTimeMillis()
        val current = _monitorStatus.value
        val nextStatus = current.copy(
            lastCheckTime = now,
            lastResult = decision.result,
            lastSummary = decision.summary,
            lastReason = decision.reason,
            lastRemark = decision.remark,
            lastConfidence = decision.confidence,
            totalCheckCount = current.totalCheckCount + 1,
            alertCount = current.alertCount + if (decision.result == CheckResult.ALERT) 1 else 0,
            warningCount = current.warningCount + if (decision.result == CheckResult.WARNING) 1 else 0,
            normalCount = current.normalCount + if (decision.result == CheckResult.NORMAL) 1 else 0,
            unknownCount = current.unknownCount + if (decision.result == CheckResult.UNKNOWN) 1 else 0
        )
        _monitorStatus.value = nextStatus

        if (decision.result != CheckResult.ALERT) {
            alertNotifier.stopAlerting()
        }

        val shouldNotify = when (decision.result) {
            CheckResult.ALERT,
            CheckResult.WARNING -> {
                decision.result != lastNotifiedResult ||
                    now - lastNotificationAt >= currentSettings.notificationCooldownSeconds * 1_000L
            }

            CheckResult.NORMAL -> current.lastResult != CheckResult.NORMAL
            else -> false
        }

        if (shouldNotify) {
            lastNotifiedResult = decision.result
            lastNotificationAt = now
            alertNotifier.notify(
                taskTitle = currentTask?.title.orEmpty(),
                decision = decision
            )
        }

        if (currentSettings.ledControlEnabled) {
            scope.launch {
                when (decision.result) {
                    CheckResult.ALERT -> if (shouldNotify) ledController?.setModeByMonitorStatus("alert")
                    CheckResult.WARNING -> if (shouldNotify) ledController?.setModeByMonitorStatus("warning")
                    CheckResult.NORMAL -> ledController?.setModeByMonitorStatus("normal")
                    else -> Unit
                }
            }
        }

        addLog(
            result = decision.result,
            message = buildResultMessage(decision),
            action = MonitorLogAction.RESULT,
            confidence = decision.confidence,
            eventFramePath = eventFramePath
        )
    }

    private fun buildDetectionRequest(
        task: IntentResult,
        currentImageDataUri: String,
        modelName: String = model
    ): DoubaoImageRequest {
        val instructions = when (task.monitorMode) {
            MonitorMode.SceneBaseline -> buildSceneBaselineInstructions(task)
            MonitorMode.ReferenceTarget -> buildReferenceTargetInstructions(task)
        }
        val requestContentItems = mutableListOf<ImageContentItem>()
        requestContentItems += ImageContentItem(
            type = "input_text",
            text = if (task.monitorMode == MonitorMode.ReferenceTarget) {
                "Reference target description: ${task.originalSceneDescription}"
            } else {
                "Baseline scene description: ${task.originalSceneDescription}"
            }
        )
        task.baseFrameBase64?.let {
            requestContentItems += ImageContentItem(
                type = "input_image",
                imageUrl = "data:image/jpeg;base64,$it"
            )
        }
        requestContentItems += ImageContentItem(
            type = "input_text",
            text = if (task.monitorMode == MonitorMode.ReferenceTarget) {
                "Current scene image:"
            } else {
                "Current frame:"
            }
        )
        requestContentItems += ImageContentItem(type = "input_image", imageUrl = currentImageDataUri)
        return DoubaoImageRequest(
            model = modelName,
            input = listOf(
                ImageMessage(
                    role = "system",
                    content = listOf(ImageContentItem(type = "input_text", text = instructions))
                ),
                ImageMessage(role = "user", content = requestContentItems)
            )
        )
        /*
        val sharedInstructions = buildString {
            appendLine("你是一个视觉监控分类器。")
            appendLine("监控目标：${task.userRequirement}")
            appendLine("任务提示词：${task.promptTemplate}")
            appendLine("只返回 JSON，字段为 status、summary、reason、confidence、remark。")
            appendLine("status 只能是 ALERT、WARNING、NORMAL、UNKNOWN。")
            appendLine("summary 要简短明确。")
            appendLine("confidence 优先使用 0 到 1 之间的数字；如果无法量化，也可以使用“高”“中”“低”。")
        }

        val contentItems = mutableListOf<ImageContentItem>()
        contentItems += ImageContentItem(
            type = "input_text",
            text = "参考场景描述：${task.originalSceneDescription}"
        )
        task.baseFrameBase64?.let {
            contentItems += ImageContentItem(
                type = "input_image",
                imageUrl = "data:image/jpeg;base64,$it"
            )
        }
        contentItems += ImageContentItem(type = "input_text", text = "当前画面：")
        contentItems += ImageContentItem(type = "input_image", imageUrl = currentImageDataUri)

        return DoubaoImageRequest(
            model = model,
            input = listOf(
                ImageMessage(
                    role = "system",
                    content = listOf(ImageContentItem(type = "input_text", text = sharedInstructions))
                ),
                ImageMessage(role = "user", content = contentItems)
            )
        )
        */
    }

    private fun buildSceneBaselineInstructions(task: IntentResult): String {
        return buildString {
            appendLine("You are a visual monitoring classifier.")
            appendLine("Task requirement: ${task.userRequirement}")
            appendLine("Mode: compare the current frame against the baseline scene image.")
            appendLine("Task prompt: ${task.promptTemplate}")
            appendLine("Return JSON only with fields status, summary, reason, confidence, remark.")
            appendLine("status must be one of ALERT, WARNING, NORMAL, UNKNOWN.")
            appendLine("remark must be a short cyber-poetic, dramatic one-liner for the user. It may be expressive, but must not contradict status, summary, or reason, and must not invent unsupported visual facts.")
            appendLine("Use ALERT or WARNING when the current scene clearly deviates from the baseline in a way that matters to the task.")
            appendLine("Use NORMAL when the current scene still matches the baseline scene.")
        }
    }

    private fun buildReferenceTargetInstructions(task: IntentResult): String {
        val triggerRule = when (task.targetTrigger) {
            TargetTrigger.OnAppear ->
                "The reference image shows the target to detect. Return ALERT or WARNING when the target appears in the current scene."
            TargetTrigger.OnDisappear ->
                "The reference image shows the target that should remain present. Return ALERT or WARNING when the target is missing, gone, or no longer visible in the current scene."
        }
        return buildString {
            appendLine("You are a visual target-matching classifier.")
            appendLine("Task requirement: ${task.userRequirement}")
            appendLine("Mode: determine whether the target shown in the reference image is present in the current scene.")
            appendLine(triggerRule)
            appendLine("Task prompt: ${task.promptTemplate}")
            appendLine("Return JSON only with fields status, summary, reason, confidence, remark.")
            appendLine("status must be one of ALERT, WARNING, NORMAL, UNKNOWN.")
            appendLine("remark must be a short cyber-poetic, dramatic one-liner for the user. It may be expressive, but must not contradict status, summary, or reason, and must not invent unsupported visual facts.")
            appendLine("reason must focus on target appearance, absence, or partial match, not generic scene drift.")
        }
    }

    private fun buildResultMessage(decision: MonitorDecision): String {
        return buildString {
            append(decision.result.toDisplayLabel())
            append("：")
            append(decision.summary)
            if (decision.reason.isNotBlank()) {
                append(" | ")
                append(decision.reason)
            }
            decision.confidence?.let {
                append(" | 置信度 ")
                append(ModelOutputParser.fractionToPercent(it))
                append("%")
            }
        }
    }

    private fun recordFailure(
        message: String,
        eventFramePath: String? = null
    ) {
        alertNotifier.stopAlerting()
        _monitorStatus.value = _monitorStatus.value.copy(
            failureCount = _monitorStatus.value.failureCount + 1,
            lastResult = CheckResult.UNKNOWN,
            lastSummary = "最近一次监控检查失败",
            lastReason = message,
            lastRemark = ""
        )
        addLog(CheckResult.UNKNOWN, message, MonitorLogAction.ERROR, eventFramePath = eventFramePath)
    }

    private fun addLog(
        result: CheckResult,
        message: String,
        action: MonitorLogAction,
        confidence: Float? = null,
        eventFramePath: String? = null
    ) {
        val now = System.currentTimeMillis()
        val fingerprint = "$action|$result|$message"
        val dedupeWindowMs = when (action) {
            MonitorLogAction.RESULT -> maxOf(currentSettings.notificationCooldownSeconds * 1_000L, 30_000L)
            MonitorLogAction.SKIP -> 15_000L
            else -> 5_000L
        }
        if (fingerprint == lastLogFingerprint && now - lastLogAt < dedupeWindowMs) {
            return
        }

        lastLogFingerprint = fingerprint
        lastLogAt = now
        val entry = MonitorLogEntry(
            result = result,
            message = message,
            action = action,
            confidence = confidence,
            imagePath = eventFramePath
        )
        _monitorLogs.value = listOf(entry) + _monitorLogs.value.take(79)
        persistLog(entry, eventFramePath)
    }

    private fun persistLog(entry: MonitorLogEntry, eventFramePath: String?) {
        val runId = currentRunId ?: return
        scope.launch {
            historyRepository?.appendMonitorEvent(
                runId = runId,
                result = entry.result,
                message = entry.message,
                action = entry.action,
                frameImagePath = eventFramePath,
                confidence = entry.confidence,
                timestamp = entry.timestamp
            )
            historyRepository?.syncMonitorRunState(
                runId = runId,
                task = currentTask,
                status = _monitorStatus.value,
                runStatus = currentRunLifecycleStatus(),
                baselineImagePath = baselineImagePath,
                sessionVideoPath = sessionVideoPath
            )
        }
    }

    private fun captureBaselineIfNeeded() {
        val runId = currentRunId ?: return
        if (currentTask?.baselineSource == BaselineSource.UploadedImage) return
        if (baselineImagePath != null) return
        val frame = _currentFrame.value ?: return
        val directory = currentRunDirectory ?: return
        val path = snapshotStore?.save(
            bitmap = frame,
            directory = directory,
            prefix = "BASELINE",
            quality = 92
        ) ?: return
        baselineImagePath = path
        _monitorStatus.value = _monitorStatus.value.copy(effectiveBaselineImagePath = path)
        scope.launch {
            historyRepository?.syncMonitorRunState(
                runId = runId,
                task = currentTask,
                status = _monitorStatus.value,
                runStatus = currentRunLifecycleStatus(),
                baselineImagePath = path,
                sessionVideoPath = sessionVideoPath
            )
        }
    }

    private fun syncExistingBaselinePathIfNeeded() {
        val runId = currentRunId ?: return
        val path = baselineImagePath ?: return
        _monitorStatus.value = _monitorStatus.value.copy(effectiveBaselineImagePath = path)
        scope.launch {
            historyRepository?.syncMonitorRunState(
                runId = runId,
                task = currentTask,
                status = _monitorStatus.value,
                runStatus = currentRunLifecycleStatus(),
                baselineImagePath = path,
                sessionVideoPath = sessionVideoPath
            )
        }
    }

    private fun saveEventFrame(frame: Bitmap): String? {
        val directory = currentRunDirectory ?: return null
        return snapshotStore?.save(
            bitmap = frame,
            directory = directory,
            prefix = "EVENT",
            quality = 88
        )
    }

    private fun encodeCurrentFrame(frame: Bitmap): String {
        val cached = encodedCurrentFrame
        if (cached?.bitmap === frame) {
            return cached.dataUri
        }
        val dataUri = BitmapEncoding.toDataUri(frame)
        encodedCurrentFrame = EncodedFrame(frame, dataUri)
        return dataUri
    }

    private fun startSessionRecordingIfPossible() {
        val directory = currentRunDirectory ?: return
        val file = snapshotStore?.createFile(directory, "session.mp4") ?: return
        sessionRecorder.start(
            scope = scope,
            file = file,
            samplingFps = MONITOR_ARCHIVE_VIDEO_FPS,
            frameProvider = { _currentFrame.value }
        )
    }

    private fun calculateFrameChange(previous: Bitmap, current: Bitmap): Int {
        val sampleColumns = 18
        val sampleRows = 14
        var totalDiff = 0f
        var sampleCount = 0

        for (xIndex in 0 until sampleColumns) {
            for (yIndex in 0 until sampleRows) {
                val previousX = xIndex * (previous.width - 1) / (sampleColumns - 1)
                val previousY = yIndex * (previous.height - 1) / (sampleRows - 1)
                val currentX = xIndex * (current.width - 1) / (sampleColumns - 1)
                val currentY = yIndex * (current.height - 1) / (sampleRows - 1)
                val previousPixel = previous.getPixel(previousX, previousY)
                val currentPixel = current.getPixel(currentX, currentY)
                totalDiff += abs(brightness(previousPixel) - brightness(currentPixel)) / 255f
                sampleCount += 1
            }
        }

        return ModelOutputParser.fractionToPercent(if (sampleCount == 0) 0f else totalDiff / sampleCount)
    }

    private fun brightness(pixel: Int): Float {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (0.299f * red) + (0.587f * green) + (0.114f * blue)
    }

    private fun snapshotBitmap(bitmap: Bitmap): Bitmap {
        return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    }

    private fun CheckResult.toDisplayLabel(): String {
        return when (this) {
            CheckResult.NONE -> "未开始"
            CheckResult.ALERT -> "警报"
            CheckResult.WARNING -> "预警"
            CheckResult.NORMAL -> "正常"
            CheckResult.UNKNOWN -> "未知"
        }
    }

    private fun currentRunLifecycleStatus(): MonitorRunStatus {
        return when {
            !_monitorStatus.value.isRunning -> MonitorRunStatus.Completed
            _monitorStatus.value.isPaused -> MonitorRunStatus.Paused
            else -> MonitorRunStatus.Running
        }
    }

    companion object {
        private const val MONITOR_ARCHIVE_VIDEO_FPS = 2
    }

    private data class EncodedFrame(
        val bitmap: Bitmap,
        val dataUri: String
    )
}
