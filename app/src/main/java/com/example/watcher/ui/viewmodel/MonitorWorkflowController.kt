package com.example.watcher.ui.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.watcher.R
import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.repository.AppUpdatePrompt
import com.example.watcher.data.repository.AppUpdateRepository
import com.example.watcher.data.repository.BitmapEncoding
import com.example.watcher.data.repository.HistoryRepository
import com.example.watcher.data.repository.IntentRepository
import com.example.watcher.data.repository.MonitorManager
import com.example.watcher.data.repository.SnapshotStore
import com.example.watcher.ui.components.StreamSource
import com.example.watcher.WatcherForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class MonitorWorkflowController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val repository: IntentRepository,
    private val historyRepository: HistoryRepository,
    private val monitorManager: MonitorManager,
    private val snapshotStore: SnapshotStore,
    private val appUpdateRepository: AppUpdateRepository,
    private val streamSourceProvider: () -> StreamSource,
    private val onReconnectStream: () -> Unit
) {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    private val _currentIntentResult = MutableStateFlow<IntentResult?>(null)
    private val _pendingBaselineImagePath = MutableStateFlow<String?>(null)
    private val _pendingBaselineBase64 = MutableStateFlow<String?>(null)
    private val _appUpdatePrompt = MutableStateFlow<AppUpdatePrompt?>(null)

    private var lastPersistedCheckTime = 0L

    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val currentIntentResult: StateFlow<IntentResult?> = _currentIntentResult.asStateFlow()
    val pendingBaselineImagePath: StateFlow<String?> = _pendingBaselineImagePath.asStateFlow()
    val pendingBaselineBase64: StateFlow<String?> = _pendingBaselineBase64.asStateFlow()
    val appUpdatePrompt: StateFlow<AppUpdatePrompt?> = _appUpdatePrompt.asStateFlow()

    fun initialize() {
        observeMonitorStatusPersistence()
        checkForAppUpdate()
    }

    fun dismissAppUpdatePrompt() {
        _appUpdatePrompt.value = null
    }

    fun analyzeIntent(userInput: String) {
        if (userInput.isBlank()) {
            _uiState.value = UiState.Error(appContext.getString(R.string.error_empty_request))
            return
        }

        stopMonitoringIfRunning()

        scope.launch {
            _uiState.value = UiState.Loading
            val selectedBaselineBase64 = _pendingBaselineBase64.value
            val selectedBaselinePath = _pendingBaselineImagePath.value
            val baselineBitmap = selectedBaselineBase64?.let(::decodeBase64Bitmap)
            val fallbackFrame = monitorManager.currentFrame.value
            val effectiveBitmap = baselineBitmap ?: fallbackFrame
            val effectiveSource = if (selectedBaselineBase64 != null) {
                BaselineSource.UploadedImage
            } else {
                BaselineSource.CapturedFrame
            }
            repository.analyzeIntent(
                userInput = userInput,
                frame = effectiveBitmap,
                baselineSource = effectiveSource,
                baselineImagePath = if (effectiveSource == BaselineSource.UploadedImage) {
                    selectedBaselinePath
                } else {
                    null
                }
            )
                .onSuccess(::showMonitorIntentResult)
                .onFailure { error ->
                    _uiState.value = UiState.Error(
                        error.message ?: appContext.getString(R.string.error_analyze_request_failed)
                    )
                }
        }
    }

    fun loadTask(task: MonitorTask) {
        stopMonitoringIfRunning()
        showMonitorIntentResult(IntentResult.fromTask(task))
    }

    fun showIntentResult(result: IntentResult) {
        stopMonitoringIfRunning()
        showMonitorIntentResult(result)
    }

    fun saveCurrentTask(result: IntentResult) {
        scope.launch {
            val shouldRestartMonitoring = shouldRestartMonitoring(result.taskId)
            runCatching { repository.saveTask(result) }
                .onSuccess { saved ->
                    showMonitorIntentResult(saved)
                    if (shouldRestartMonitoring) {
                        restartMonitoring(saved)
                    }
                }
                .onFailure { error ->
                    _uiState.value = UiState.Error(
                        error.message ?: appContext.getString(R.string.error_save_task_failed)
                    )
                }
        }
    }

    fun refreshBaselineFromCurrentFrame() {
        val frame = monitorManager.currentFrame.value ?: run {
            _uiState.value = UiState.Error(
                appContext.getString(R.string.error_refresh_baseline_no_stream)
            )
            return
        }
        val currentResult = _currentIntentResult.value ?: run {
            _uiState.value = UiState.Error(
                appContext.getString(R.string.error_refresh_baseline_no_task)
            )
            return
        }

        scope.launch {
            val shouldRestartMonitoring = shouldRestartMonitoring(currentResult.taskId)
            runCatching {
                val reParsed = repository.analyzeIntent(
                    userInput = currentResult.userInput,
                    frame = frame,
                    baselineSource = BaselineSource.CapturedFrame,
                    baselineImagePath = null,
                    persist = false
                )
                    .getOrThrow()
                    .copy(taskId = currentResult.taskId, createdAt = currentResult.createdAt)
                _pendingBaselineImagePath.value = null
                _pendingBaselineBase64.value = null
                repository.saveTask(reParsed)
            }.onSuccess { updated ->
                showMonitorIntentResult(updated)
                if (shouldRestartMonitoring) {
                    restartMonitoring(updated)
                }
            }.onFailure { error ->
                _uiState.value = UiState.Error(
                    error.message ?: appContext.getString(R.string.error_refresh_baseline_failed)
                )
            }
        }
    }

    fun setBaselineFromPickedImage(uri: Uri) {
        scope.launch {
            android.util.Log.d("ImageUpload", "setBaselineFromPickedImage called, uri=$uri")
            val currentResult = _currentIntentResult.value
            val shouldRestartMonitoring = shouldRestartMonitoring(currentResult?.taskId)
            runCatching {
                android.util.Log.d("ImageUpload", "Importing image...")
                val importedPath = importBaselineImage(uri)
                    ?: error("Unable to import the selected image.")
                android.util.Log.d("ImageUpload", "Imported to: $importedPath")
                val bitmap = decodeBitmap(uri)
                    ?: error("Unable to read the selected image.")
                android.util.Log.d("ImageUpload", "Decoded bitmap: ${bitmap.width}x${bitmap.height}")
                _pendingBaselineImagePath.value = importedPath
                _pendingBaselineBase64.value = BitmapEncoding.toBase64(bitmap)
                if (currentResult == null) {
                    return@runCatching null
                }
                val reParsed = repository.analyzeIntent(
                    userInput = currentResult.userInput,
                    frame = bitmap,
                    baselineSource = BaselineSource.UploadedImage,
                    baselineImagePath = importedPath,
                    persist = false
                )
                    .getOrThrow()
                    .copy(taskId = currentResult.taskId, createdAt = currentResult.createdAt)
                return@runCatching repository.saveTask(reParsed)
            }.onSuccess { updated ->
                updated?.let {
                    showMonitorIntentResult(it)
                    if (shouldRestartMonitoring) {
                        restartMonitoring(it)
                    }
                }
            }.onFailure { error ->
                android.util.Log.e("ImageUpload", "Failed: ${error.message}", error)
                _uiState.value = UiState.Error(error.message ?: "导入基准图片失败")
            }
        }
    }

    fun deleteTask(id: Long) {
        scope.launch {
            if (_currentIntentResult.value?.taskId == id) {
                monitorManager.stopMonitoring()
                _currentIntentResult.value = null
                _pendingBaselineImagePath.value = null
                _pendingBaselineBase64.value = null
                _uiState.value = UiState.Idle
            }
            repository.deleteTask(id)
        }
    }

    fun startMonitoring(task: IntentResult): Boolean {
        monitoringStartBlockMessage()?.let { message ->
            onReconnectStream()
            _uiState.value = UiState.Error(message)
            return false
        }
        WatcherForegroundService.start(appContext, "实时监控运行中")
        scope.launch {
            startMonitoringInternal(task)
        }
        return true
    }

    fun pauseMonitoring() {
        monitorManager.pauseMonitoring()
    }

    fun resumeMonitoring() {
        monitorManager.resumeMonitoring()
    }

    fun stopMonitoring() {
        monitorManager.stopMonitoring()
        WatcherForegroundService.stop(appContext)
    }

    fun saveSnapshot(bitmap: Bitmap): String? {
        return snapshotStore.save(bitmap)?.also(monitorManager::attachSnapshot)
    }

    private fun observeMonitorStatusPersistence() {
        scope.launch {
            monitorManager.monitorStatus.collect { status ->
                val taskId = _currentIntentResult.value?.taskId
                if (taskId != null && status.lastCheckTime > lastPersistedCheckTime) {
                    lastPersistedCheckTime = status.lastCheckTime
                    repository.updateTaskOutcome(
                        taskId = taskId,
                        lastStatus = status.lastResult.name,
                        lastSummary = status.lastSummary.ifBlank { status.lastReason }
                    )
                }
            }
        }
    }

    private fun showMonitorIntentResult(result: IntentResult) {
        _currentIntentResult.value = result
        if (result.baselineSource == BaselineSource.UploadedImage) {
            _pendingBaselineImagePath.value = result.baselineImagePath
            _pendingBaselineBase64.value = result.baseFrameBase64
        } else {
            _pendingBaselineImagePath.value = null
            _pendingBaselineBase64.value = null
        }
        _uiState.value = UiState.Success(result)
    }

    private fun stopMonitoringIfRunning() {
        if (monitorManager.monitorStatus.value.isRunning) {
            monitorManager.stopMonitoring()
            WatcherForegroundService.stop(appContext)
        }
    }

    private fun shouldRestartMonitoring(taskId: Long?): Boolean {
        return monitorManager.monitorStatus.value.isRunning &&
            _currentIntentResult.value?.taskId == taskId
    }

    private suspend fun restartMonitoring(task: IntentResult) {
        lastPersistedCheckTime = 0L
        startMonitoringInternal(task)
    }

    private suspend fun startMonitoringInternal(task: IntentResult) {
        monitoringStartBlockMessage()?.let { message ->
            onReconnectStream()
            _uiState.value = UiState.Error(message)
            return
        }
        val normalized = task.normalized()
        _currentIntentResult.value = normalized
        lastPersistedCheckTime = 0L
        normalized.taskId?.let {
            repository.touchTask(it, null, null)
        }
        val runId = historyRepository.startMonitorRun(normalized)
        monitorManager.startMonitoring(normalized, runId)
    }

    private fun monitoringStartBlockMessage(): String? {
        val source = streamSourceProvider()
        return when {
            source.isCameraFallback && monitorManager.currentFrame.value != null -> null
            source != StreamSource.RemoteMjpeg && !source.isCameraFallback ||
                monitorManager.currentFrame.value == null ->
                "当前未连接到视频流。已尝试重新连接，请等待画面恢复后再启动实时监控。"
            else -> null
        }
    }

    private fun checkForAppUpdate() {
        scope.launch {
            appUpdateRepository.checkForUpdate()
                .onSuccess { prompt ->
                    if (prompt != null) {
                        _appUpdatePrompt.value = prompt
                    }
                }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return appContext.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    }

    private fun decodeBase64Bitmap(base64: String): Bitmap? {
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun importBaselineImage(uri: Uri): String? {
        val contentResolver = appContext.contentResolver
        val extension = resolveImageExtension(contentResolver, uri)
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            snapshotStore.importImage(
                inputStream = inputStream,
                directory = "MonitorBaselines",
                prefix = "BASELINE_IMPORT",
                extension = extension
            )
        }
    }

    private fun resolveImageExtension(contentResolver: ContentResolver, uri: Uri): String {
        val mimeType = contentResolver.getType(uri)
        val fromMimeType = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val displayName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
        val fromName = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
        return fromName ?: fromMimeType
    }
}
