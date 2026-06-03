package com.example.watcher.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.watcher.watcherApplication
import com.example.watcher.data.local.AppDatabase
import com.example.watcher.data.model.DeviceProvisionUiState
import com.example.watcher.data.model.HistoryRecordDetail
import com.example.watcher.data.model.HistoryRecordSelection
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorLogEntry
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.model.DeviceRuntimeInfo
import com.example.watcher.data.model.DiscoveredStreamDevice
import com.example.watcher.data.model.DiscoveredStreamDeviceKind
import com.example.watcher.data.model.StreamScanUiState
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.model.MonitorTaskTemplates
import com.example.watcher.data.model.MonitorTemplateEntity
import com.example.watcher.data.model.VideoTaskTemplates
import com.example.watcher.data.model.VideoTemplateEntity
import com.example.watcher.data.model.toMonitorTaskTemplate
import com.example.watcher.data.model.toVideoTaskTemplate
import com.example.watcher.data.gateway.GatewayRuntimeStatus
import com.example.watcher.data.remote.RetrofitClient
import com.example.watcher.data.repository.AndroidAlertNotifier
import com.example.watcher.data.repository.AppUpdatePrompt
import com.example.watcher.data.repository.AppUpdateRepository
import com.example.watcher.data.repository.CouncilExpertRepository
import com.example.watcher.data.repository.HistoryRepository
import com.example.watcher.data.repository.HistoryTemplateConverter
import com.example.watcher.data.repository.IntentRepository
import com.example.watcher.data.repository.LanStreamScanner
import com.example.watcher.data.repository.MonitorManager
import com.example.watcher.data.repository.SnapshotStore
import com.example.watcher.data.repository.StreamDeviceCoordinator
import com.example.watcher.data.repository.TemplateRepository
import com.example.watcher.data.model.AiAudienceEntity
import com.example.watcher.data.model.AgentAudienceDebugSnapshot
import com.example.watcher.data.model.AudienceEngineType
import com.example.watcher.data.model.CouncilConfig
import com.example.watcher.data.model.CouncilEntryDraft
import com.example.watcher.data.model.CouncilEntryUiState
import com.example.watcher.data.model.CouncilExpertEntity
import com.example.watcher.data.model.CouncilTemplateEntity
import com.example.watcher.data.model.CouncilUiState
import com.example.watcher.data.model.InteractionMode
import com.example.watcher.data.model.MemorySnapshot
import com.example.watcher.data.model.AiAudienceLiveState
import com.example.watcher.data.model.DanmakuItem
import com.example.watcher.data.model.LiveCommentaryState
import com.example.watcher.data.model.LiveSpeechState
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.repository.AiAudienceManager
import com.example.watcher.data.repository.LiveSpeechRecognitionManager
import com.example.watcher.data.remote.ArkStreamingClient
import com.example.watcher.data.repository.LiveCommentaryRepository
import com.example.watcher.data.repository.VideoProcessRepository
import com.example.watcher.data.repository.agent.AgentAudienceManager
import com.example.watcher.data.repository.council.CouncilEntryConfigGenerator
// Gateway imports moved to GatewayDelegate
import com.example.watcher.data.repository.council.CouncilManager
import com.example.watcher.ui.components.StreamSource
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class IntentViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = getApplication<Application>()
    private val database = AppDatabase.getDatabase(application)
    private val llmWalletRepository = appContext.watcherApplication().agentFrameworkContainer.llmWalletRepository
    private val repository = IntentRepository(
        apiService = RetrofitClient.doubaoApiService,
        taskDao = database.monitorTaskDao(),
        llmWalletRepository = llmWalletRepository
    )
    private val liveCommentaryRepository = LiveCommentaryRepository(
        apiService = RetrofitClient.doubaoApiService,
        streamingClient = ArkStreamingClient(),
        llmWalletRepository = llmWalletRepository
    )
    private val classicAudienceManager = AiAudienceManager(
        llmWalletRepository = llmWalletRepository,
        audienceDao = database.aiAudienceDao(),
        messageDao = database.aiAudienceMessageDao(),
        memoryManager = liveCommentaryRepository.memoryManager,
        sceneMemoryManager = liveCommentaryRepository.sceneMemoryManager
    )
    private val agentAudienceManager = AgentAudienceManager(
        llmWalletRepository = llmWalletRepository,
        audienceDao = database.aiAudienceDao(),
        messageDao = database.aiAudienceMessageDao(),
        memoryManager = liveCommentaryRepository.memoryManager,
        sceneMemoryManager = liveCommentaryRepository.sceneMemoryManager
    )
    private val councilManager = CouncilManager(
        llmWalletRepository = llmWalletRepository,
        councilExpertDao = database.councilExpertDao(),
        knowledgeDao = database.councilKnowledgeDao(),
        messageDao = database.aiAudienceMessageDao(),
        memoryManager = liveCommentaryRepository.memoryManager,
        sceneMemoryManager = liveCommentaryRepository.sceneMemoryManager
    )
    private val audienceTypeCache = MutableStateFlow<Map<Long, AudienceEngineType>>(emptyMap())
    private val liveSpeechManager = LiveSpeechRecognitionManager(
        context = appContext,
        memoryManager = liveCommentaryRepository.memoryManager
    )
    private val videoRepository = VideoProcessRepository(
        apiService = RetrofitClient.doubaoApiService,
        taskDao = database.videoProcessTaskDao(),
        runDao = database.videoProcessRunDao(),
        segmentRunDao = database.videoSegmentRunDao(),
        audioAssetDao = database.videoAudioAssetDao(),
        remoteFileBindingDao = database.videoRemoteFileBindingDao(),
        speechTranscriptDao = database.videoSpeechTranscriptDao(),
        timelineEventDao = database.timelineEventDao(),
        llmWalletRepository = llmWalletRepository
    )
    private val historyRepository = HistoryRepository(
        monitorRunDao = database.monitorRunDao(),
        monitorEventDao = database.monitorEventDao(),
        monitorMediaDao = database.monitorMediaDao(),
        monitorTaskDao = database.monitorTaskDao(),
        videoProcessTaskDao = database.videoProcessTaskDao(),
        videoRunDao = database.videoProcessRunDao(),
        videoSegmentRunDao = database.videoSegmentRunDao(),
        videoAudioAssetDao = database.videoAudioAssetDao(),
        videoRemoteFileBindingDao = database.videoRemoteFileBindingDao(),
        videoSpeechTranscriptDao = database.videoSpeechTranscriptDao(),
        timelineEventDao = database.timelineEventDao()
    )
    private val migrationPreferences = appContext.getSharedPreferences(
        "watcher_migrations",
        Context.MODE_PRIVATE
    )
    private val alertNotifier = AndroidAlertNotifier(appContext)
    private val appUpdateRepository = AppUpdateRepository(appContext)
    private val snapshotStore = SnapshotStore(appContext)
    private val lanStreamScanner = LanStreamScanner(appContext)
    private val templateRepository = TemplateRepository(database.templateDao())
    private val councilExpertRepository = CouncilExpertRepository(database.councilExpertDao())
    private val councilEntryGenerator = CouncilEntryConfigGenerator()

    init {
        viewModelScope.launch {
            database.aiAudienceDao().observeAll().collect { audiences ->
                audienceTypeCache.value = audiences.associate { it.id to it.audienceType }
            }
        }
    }

    private val _isStreamPlaying = MutableStateFlow(true)
    private val _streamReconnectToken = MutableStateFlow(0)
    private val _currentStreamSource = MutableStateFlow(StreamSource.None)
    // Device state delegated to DeviceDelegate
    // History state delegated to HistoryDelegate

    val isStreamPlaying: StateFlow<Boolean> = _isStreamPlaying.asStateFlow()
    val streamReconnectToken: StateFlow<Int> = _streamReconnectToken.asStateFlow()
    val currentStreamSource: StateFlow<StreamSource> = _currentStreamSource.asStateFlow()
    val streamScanUiState: StateFlow<StreamScanUiState> get() = deviceDelegate.streamScanUiState
    val deviceProvisionUiState: StateFlow<DeviceProvisionUiState> get() = deviceDelegate.deviceProvisionUiState
    val settingsNotice: StateFlow<String?> get() = deviceDelegate.settingsNotice
    val selectedHistoryRecord: StateFlow<HistoryRecordSelection?> get() = historyDelegate.selectedHistoryRecord
    val selectedHistoryDetail: StateFlow<HistoryRecordDetail?> get() = historyDelegate.selectedHistoryDetail

    val tasksFlow = repository.observeTasks()
    val videoTasksFlow = videoRepository.observeTasks()
    val recentVideoRunsFlow = videoRepository.observeRecentRuns()
    val historyRecordsFlow = historyRepository.observeHistoryRecords()
    val storageSummaryFlow = historyRepository.observeStorageSummary()
    val videoStreamSettings = database.videoStreamSettingsDao().getSettings()
    val monitorTemplatesFlow = templateRepository.monitorTemplates
    val videoTemplatesFlow = templateRepository.videoTemplates
    val councilTemplatesFlow = templateRepository.councilTemplates
    val councilExpertsFlow = councilExpertRepository.experts

    val monitorManager = MonitorManager(
        apiService = RetrofitClient.doubaoApiService,
        alertNotifier = alertNotifier,
        historyRepository = historyRepository,
        snapshotStore = snapshotStore,
        llmWalletRepository = llmWalletRepository
    )
    private val streamDeviceCoordinator = StreamDeviceCoordinator(monitorManager)
    private val deviceDelegate = DeviceDelegate(
        scope = viewModelScope,
        settingsDao = database.videoStreamSettingsDao(),
        lanStreamScanner = lanStreamScanner,
        streamDeviceCoordinator = streamDeviceCoordinator,
        migrationPreferences = migrationPreferences,
        onReconnectStream = ::reconnectStream
    )
    private val templateDelegate = TemplateDelegate(
        scope = viewModelScope,
        templateRepository = templateRepository,
        templateDao = database.templateDao(),
        councilExpertRepository = councilExpertRepository
    )
    private val monitorWorkflow = MonitorWorkflowController(
        scope = viewModelScope,
        appContext = appContext,
        repository = repository,
        historyRepository = historyRepository,
        monitorManager = monitorManager,
        snapshotStore = snapshotStore,
        appUpdateRepository = appUpdateRepository,
        streamSourceProvider = { _currentStreamSource.value },
        onReconnectStream = ::reconnectStream
    )
    private val videoWorkflow = VideoWorkflowController(
        scope = viewModelScope,
        appContext = appContext,
        videoRepository = videoRepository,
        latestFrameProvider = { monitorManager.currentFrame.value }
    )
    private val historyTemplateConverter = HistoryTemplateConverter(
        monitorTaskDao = database.monitorTaskDao(),
        videoProcessTaskDao = database.videoProcessTaskDao(),
        templateDao = database.templateDao()
    )
    private val historyDelegate = HistoryDelegate(
        scope = viewModelScope,
        historyRepository = historyRepository,
        videoRepository = videoRepository,
        historyTemplateConverter = historyTemplateConverter,
        selectedVideoRunId = videoWorkflow.selectedVideoRunIdState,
        selectedVideoRunEvents = videoWorkflow.selectedVideoRunEventsState
    )
    private val liveInteractionController = LiveInteractionController(
        scope = viewModelScope,
        appContext = appContext,
        database = database,
        llmWalletRepository = llmWalletRepository,
        templateRepository = templateRepository,
        liveCommentaryRepository = liveCommentaryRepository,
        classicAudienceManager = classicAudienceManager,
        agentAudienceManager = agentAudienceManager,
        councilManager = councilManager,
        liveSpeechManager = liveSpeechManager,
        councilEntryGenerator = councilEntryGenerator,
        currentFrameProvider = { monitorManager.currentFrame.value }
    )
    // ── Gateway delegation ────────────────────────────────────
    private val gatewayDelegate = GatewayDelegate(
        scope = viewModelScope,
        appContext = appContext,
        monitorManager = monitorManager,
        intentRepository = repository,
        historyRepository = historyRepository,
        videoRepository = videoRepository,
        agentService = appContext.watcherApplication().agentFrameworkContainer.service,
        liveCommentaryRepository = liveCommentaryRepository,
        liveSpeechManager = liveSpeechManager,
        streamUrlProvider = { monitorManager.currentStreamUrl },
        onStreamRelease = { _isStreamPlaying.value = false },
        onStreamReclaim = { reconnectStream() }
    )
    val gatewayRunning: StateFlow<Boolean> get() = gatewayDelegate.running
    val gatewayStatus: StateFlow<GatewayRuntimeStatus> get() = gatewayDelegate.status
    val gatewayApiKey: String get() = gatewayDelegate.apiKey
    val gatewayPort: Int get() = gatewayDelegate.port
    fun toggleGateway(enabled: Boolean) = gatewayDelegate.toggle(enabled)
    fun getLocalIpAddress(): String = gatewayDelegate.getLocalIpAddress()

    val monitorStatus: StateFlow<MonitorStatus> get() = monitorManager.monitorStatus
    val monitorLogs: StateFlow<List<MonitorLogEntry>> get() = monitorManager.monitorLogs
    val uiState: StateFlow<UiState> get() = monitorWorkflow.uiState
    val interactionMode: StateFlow<InteractionMode> get() = liveInteractionController.interactionMode
    val currentIntentResult: StateFlow<IntentResult?> get() = monitorWorkflow.currentIntentResult
    val pendingBaselineImagePath: StateFlow<String?> get() = monitorWorkflow.pendingBaselineImagePath
    val pendingBaselineBase64: StateFlow<String?> get() = monitorWorkflow.pendingBaselineBase64
    val videoPlanUiState: StateFlow<VideoPlanUiState> get() = videoWorkflow.videoPlanUiState
    val currentVideoTask: StateFlow<VideoProcessTaskDraft?> get() = videoWorkflow.currentVideoTask
    val videoProcessingStatus: StateFlow<VideoProcessingStatus> get() = videoWorkflow.videoProcessingStatus
    val selectedVideoRunId: StateFlow<Long?> get() = videoWorkflow.selectedVideoRunId
    val selectedVideoRunEvents: StateFlow<List<TimelineEventEntity>> get() = videoWorkflow.selectedVideoRunEvents
    val appUpdatePrompt: StateFlow<AppUpdatePrompt?> get() = monitorWorkflow.appUpdatePrompt

    init {
        liveSpeechManager.onSpeechResult = liveInteractionController::onSpeechResult
        initializeVideoSettings()
        observeVideoSettings()
        monitorWorkflow.initialize()
        historyDelegate.startObserving()
    }

    fun dismissAppUpdatePrompt() = monitorWorkflow.dismissAppUpdatePrompt()
    fun analyzeIntent(userInput: String) = monitorWorkflow.analyzeIntent(userInput)
    fun analyzeVideoIntent(userInput: String) = videoWorkflow.analyzeVideoIntent(userInput)
    fun loadTask(task: MonitorTask) = monitorWorkflow.loadTask(task)
    fun loadVideoTask(task: VideoProcessTask) = videoWorkflow.loadVideoTask(task)
    fun saveCurrentTask(result: IntentResult) = monitorWorkflow.saveCurrentTask(result)
    fun saveVideoTask(draft: VideoProcessTaskDraft) = videoWorkflow.saveVideoTask(draft)
    fun refreshBaselineFromCurrentFrame() = monitorWorkflow.refreshBaselineFromCurrentFrame()
    fun setBaselineFromPickedImage(uri: Uri) = monitorWorkflow.setBaselineFromPickedImage(uri)

    fun applyMonitorTemplate(templateId: String) {
        viewModelScope.launch {
            val entity = templateRepository.getMonitorTemplate(templateId)
            if (entity != null) {
                monitorWorkflow.showIntentResult(entity.toMonitorTaskTemplate().toIntentResult())
            } else {
                val fallback = MonitorTaskTemplates.findById(templateId) ?: return@launch
                monitorWorkflow.showIntentResult(fallback.toIntentResult())
            }
        }
    }

    fun applyVideoTemplate(templateId: String) {
        viewModelScope.launch {
            val entity = templateRepository.getVideoTemplate(templateId)
            if (entity != null) {
                videoWorkflow.showVideoDraft(
                    draft = entity.toVideoTaskTemplate().toDraft(),
                    message = "Template loaded. You can refine it before execution.",
                )
            } else {
                val fallback = VideoTaskTemplates.findById(templateId) ?: return@launch
                videoWorkflow.showVideoDraft(
                    draft = fallback.toDraft(),
                    message = "Template loaded. You can refine it before execution.",
                )
            }
        }
    }

    // ── Template delegation ─────────────────────────────────────
    fun updateMonitorTemplate(entity: MonitorTemplateEntity) = templateDelegate.updateMonitorTemplate(entity)
    fun updateVideoTemplate(entity: VideoTemplateEntity) = templateDelegate.updateVideoTemplate(entity)
    fun updateCouncilTemplate(entity: CouncilTemplateEntity) = templateDelegate.updateCouncilTemplate(entity)
    fun resetMonitorTemplate(templateId: String) = templateDelegate.resetMonitorTemplate(templateId)
    fun resetVideoTemplate(templateId: String) = templateDelegate.resetVideoTemplate(templateId)
    fun resetCouncilTemplate(templateId: String) = templateDelegate.resetCouncilTemplate(templateId)

    fun deleteTask(id: Long) = monitorWorkflow.deleteTask(id)
    fun deleteVideoTask(id: Long) = videoWorkflow.deleteVideoTask(id)

    fun setStreamPlaying(playing: Boolean) {
        _isStreamPlaying.value = playing
    }

    fun reconnectStream() {
        _isStreamPlaying.value = true
        _streamReconnectToken.value = _streamReconnectToken.value + 1
    }

    fun updateVideoFrame(bitmap: android.graphics.Bitmap?) {
        monitorManager.setCurrentFrame(bitmap)
    }

    fun updateStreamSource(source: StreamSource) {
        _currentStreamSource.value = source
    }

    fun startMonitoring(task: IntentResult): Boolean = monitorWorkflow.startMonitoring(task)
    fun pauseMonitoring() = monitorWorkflow.pauseMonitoring()
    fun resumeMonitoring() = monitorWorkflow.resumeMonitoring()
    fun stopMonitoring() = monitorWorkflow.stopMonitoring()
    fun startVideoProcessing(
        task: VideoProcessTaskDraft? = currentVideoTask.value,
        streamingOutputEnabled: Boolean = false
    ) = videoWorkflow.startVideoProcessing(task = task, streamingOutputEnabled = streamingOutputEnabled)

    fun stopVideoProcessing() = videoWorkflow.stopVideoProcessing()
    fun selectVideoRun(runId: Long?) = videoWorkflow.selectVideoRun(runId)
    fun launchVideoProcessing(
        task: VideoProcessTaskDraft? = currentVideoTask.value,
        streamingOutputEnabled: Boolean = false
    ) = videoWorkflow.launchVideoProcessing(task = task, streamingOutputEnabled = streamingOutputEnabled)

    // ── History delegation ──────────────────────────────────────
    fun selectHistoryRecord(selection: HistoryRecordSelection?) = historyDelegate.selectHistoryRecord(selection)
    fun deleteHistoryRecord(selection: HistoryRecordSelection) = historyDelegate.deleteHistoryRecord(selection)
    fun saveHistoryAsTemplate(detail: HistoryRecordDetail, onResult: (Boolean, String) -> Unit) =
        historyDelegate.saveAsTemplate(detail, onResult)
    fun shareHistoryAsTemplate(detail: HistoryRecordDetail, onResult: (String?) -> Unit) =
        historyDelegate.shareAsTemplate(detail, onResult)

    // ── Device delegation ───────────────────────────────────────
    fun saveVideoStreamSettings(settings: VideoStreamSettings) = deviceDelegate.saveVideoStreamSettings(settings)
    fun refreshDeviceProvisionInfo() = deviceDelegate.refreshDeviceProvisionInfo()
    fun scanProvisioningWifi() = deviceDelegate.scanProvisioningWifi()
    fun submitProvisioningWifi(ssid: String, password: String) = deviceDelegate.submitProvisioningWifi(ssid, password)
    fun clearProvisionedWifi() = deviceDelegate.clearProvisionedWifi()
    fun scanVideoStreamDevices() = deviceDelegate.scanVideoStreamDevices()
    fun clearStreamDeviceScan() = deviceDelegate.clearStreamDeviceScan()
    fun clearDeviceProvisionState() = deviceDelegate.clearDeviceProvisionState()
    fun recoverProvisionedDeviceAfterRuntimeDisconnect() =
        deviceDelegate.recoverProvisionedDeviceAfterRuntimeDisconnect()
    fun consumeSettingsNotice() = deviceDelegate.consumeSettingsNotice()

    fun saveSnapshot(bitmap: android.graphics.Bitmap): String? = monitorWorkflow.saveSnapshot(bitmap)

    val liveCommentaryState: StateFlow<LiveCommentaryState> get() = liveInteractionController.liveCommentaryState
    val aiAudienceLiveState: StateFlow<AiAudienceLiveState> get() = liveInteractionController.aiAudienceLiveState
    val danmakuFlow: SharedFlow<DanmakuItem> get() = liveInteractionController.danmakuFlow
    val liveSpeechState: StateFlow<LiveSpeechState> get() = liveInteractionController.liveSpeechState
    val councilState: StateFlow<CouncilUiState> get() = liveInteractionController.councilState
    val councilEntryUiState: StateFlow<CouncilEntryUiState> get() = liveInteractionController.councilEntryUiState

    fun startLiveCommentary() = liveInteractionController.startLiveCommentary()
    fun stopLiveCommentary() = liveInteractionController.stopLiveCommentary()
    fun startAiAudience() = liveInteractionController.startAiAudience()
    fun stopAiAudience() = liveInteractionController.stopAiAudience()
    fun startLiveSpeech() = liveInteractionController.startLiveSpeech()
    fun stopLiveSpeech() = liveInteractionController.stopLiveSpeech()
    fun startCouncilMode(config: CouncilConfig) = liveInteractionController.startCouncilMode(config)
    fun updateCouncilConfig(config: CouncilConfig) = liveInteractionController.updateCouncilConfig(config)
    fun triggerCouncilAnalysis(reason: String = "manual") = liveInteractionController.triggerCouncilAnalysis(reason)
    fun stopCouncilMode() = liveInteractionController.stopCouncilMode()
    fun updateCouncilEntryDraft(draft: CouncilEntryDraft) = liveInteractionController.updateCouncilEntryDraft(draft)
    fun applyCouncilEntryTemplate(templateId: String) = liveInteractionController.applyCouncilEntryTemplate(templateId)
    fun resetCouncilEntryDraft() = liveInteractionController.resetCouncilEntryDraft()
    fun generateCouncilEntryConfig(draft: CouncilEntryDraft) = liveInteractionController.generateCouncilEntryConfig(draft)
    fun saveGeneratedCouncilTemplate(label: String) = liveInteractionController.saveGeneratedCouncilTemplate(label)
    fun resetLiveRoom() = liveInteractionController.resetLiveRoom()
    fun setLiveSpeechMicEnabled(enabled: Boolean) = liveInteractionController.setLiveSpeechMicEnabled(enabled)

    val llmProvidersFlow get() = llmWalletRepository.observeProviders()
    val aiAudiencesFlow get() = database.aiAudienceDao().observeAll()

    fun saveProvider(provider: LlmProviderEntity) {
        viewModelScope.launch { llmWalletRepository.upsertProvider(provider, makeDefault = true) }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch { llmWalletRepository.deleteProvider(id) }
    }

    fun saveAudience(audience: AiAudienceEntity) {
        viewModelScope.launch { database.aiAudienceDao().upsert(audience) }
    }

    fun saveCouncilExpert(expert: CouncilExpertEntity) {
        viewModelScope.launch { councilExpertRepository.updateExpert(expert) }
    }

    fun addCouncilExpert() {
        viewModelScope.launch { councilExpertRepository.createExpert() }
    }

    fun duplicateCouncilExpert(expert: CouncilExpertEntity) {
        viewModelScope.launch { councilExpertRepository.duplicateExpert(expert) }
    }

    fun resetCouncilExpert(expertId: String) {
        viewModelScope.launch { councilExpertRepository.resetExpert(expertId) }
    }

    fun deleteCouncilExpert(expertId: String) {
        viewModelScope.launch { councilExpertRepository.deleteExpert(expertId) }
    }

    fun restoreMissingCouncilExperts() {
        viewModelScope.launch { councilExpertRepository.restoreMissingSystemPresets() }
    }

    fun deleteAudience(id: Long) {
        viewModelScope.launch { database.aiAudienceDao().deleteById(id) }
    }

    // --- Template Share/Import ---

    fun deleteMonitorTemplate(templateId: String) = templateDelegate.deleteMonitorTemplate(templateId)
    fun deleteVideoTemplate(templateId: String) = templateDelegate.deleteVideoTemplate(templateId)
    fun deleteCouncilTemplate(templateId: String) = templateDelegate.deleteCouncilTemplate(templateId)
    fun exportMonitorTemplate(template: MonitorTemplateEntity): String = templateDelegate.exportMonitorTemplate(template)
    fun exportVideoTemplate(template: VideoTemplateEntity): String = templateDelegate.exportVideoTemplate(template)
    fun exportCouncilTemplate(template: CouncilTemplateEntity): String = templateDelegate.exportCouncilTemplate(template)
    fun exportCouncilExpertTemplate(expert: CouncilExpertEntity): String = templateDelegate.exportCouncilExpertTemplate(expert)
    fun importTemplate(text: String, onResult: (String) -> Unit) = templateDelegate.importTemplate(text, onResult)

    // --- Management / Debug ---
    fun getAudienceLastPost(audienceId: Long): String? =
        audienceManagerFor(audienceId).getLastPost(audienceId)

    fun getAudienceLastResponse(audienceId: Long): String? =
        audienceManagerFor(audienceId).getLastResponse(audienceId)

    fun getAudienceWallet(audienceId: Long): Int =
        audienceManagerFor(audienceId).getWallet(audienceId)

    fun setAudienceWallet(audienceId: Long, budget: Int) =
        audienceManagerFor(audienceId).setInitialBudget(audienceId, budget)

    fun getAgentAudienceDebugSnapshot(audience: AiAudienceEntity): AgentAudienceDebugSnapshot? =
        if (audience.audienceType == AudienceEngineType.Agent) {
            agentAudienceManager.getRuntimeDebugSnapshot(audience)
        } else {
            null
        }

    fun getMemorySnapshot(): MemorySnapshot {
        val classic = classicAudienceManager.getMemorySnapshot()
        val agent = agentAudienceManager.getMemorySnapshot()
        return MemorySnapshot(
            memoryA = if (classic.memoryA.length >= agent.memoryA.length) classic.memoryA else agent.memoryA,
            memoryB = if (classic.memoryB.length >= agent.memoryB.length) classic.memoryB else agent.memoryB,
            recentVisual = if (classic.recentVisual.size >= agent.recentVisual.size) classic.recentVisual else agent.recentVisual,
            rawBufferSize = maxOf(classic.rawBufferSize, agent.rawBufferSize)
        )
    }

    fun getCouncilExpertLastPrompt(expertId: String): String? =
        councilManager.getLastPrompt(expertId)

    fun getCouncilExpertLastResponse(expertId: String): String? =
        councilManager.getLastResponse(expertId)

    fun getCouncilExpertSessionMemory(expertId: String): List<String> =
        councilManager.getSessionMemory(expertId)

    suspend fun getExpertKnowledge(expertId: String): List<com.example.watcher.data.model.CouncilKnowledgeEntity> {
        val dao = database.councilKnowledgeDao()
        // Expert's own calibration + any user_profile entries + session facts attributed to this expert
        val calibration = dao.getExpertCalibration(expertId, limit = 10)
        val userProfile = dao.getUserProfile(limit = 5)
        val bySource = dao.getBySource(expertId, limit = 5)
        return (calibration + userProfile + bySource)
            .distinctBy { it.id }
            .sortedByDescending { it.relevance }
            .take(20)
    }

    fun deleteKnowledgeEntry(id: Long) {
        viewModelScope.launch {
            database.councilKnowledgeDao().deleteById(id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        gatewayDelegate.release()
        deviceDelegate.release()
        videoWorkflow.release()
        monitorManager.release()
        streamDeviceCoordinator.release()
        liveCommentaryRepository.release()
        classicAudienceManager.release()
        agentAudienceManager.release()
        councilManager.release()
        liveSpeechManager.release()
    }

    private fun audienceManagerFor(audienceId: Long): AudienceManagerHandle =
        when (audienceTypeCache.value[audienceId]) {
            AudienceEngineType.Agent -> AgentHandle
            else -> ClassicHandle
        }

    private interface AudienceManagerHandle {
        fun getLastPost(audienceId: Long): String?
        fun getLastResponse(audienceId: Long): String?
        fun getWallet(audienceId: Long): Int
        fun setInitialBudget(audienceId: Long, budget: Int)
    }

    private val ClassicHandle = object : AudienceManagerHandle {
        override fun getLastPost(audienceId: Long): String? = classicAudienceManager.getLastPost(audienceId)
        override fun getLastResponse(audienceId: Long): String? = classicAudienceManager.getLastResponse(audienceId)
        override fun getWallet(audienceId: Long): Int = classicAudienceManager.getWallet(audienceId)
        override fun setInitialBudget(audienceId: Long, budget: Int) =
            classicAudienceManager.setInitialBudget(audienceId, budget)
    }

    private val AgentHandle = object : AudienceManagerHandle {
        override fun getLastPost(audienceId: Long): String? = agentAudienceManager.getLastPost(audienceId)
        override fun getLastResponse(audienceId: Long): String? = agentAudienceManager.getLastResponse(audienceId)
        override fun getWallet(audienceId: Long): Int = agentAudienceManager.getWallet(audienceId)
        override fun setInitialBudget(audienceId: Long, budget: Int) =
            agentAudienceManager.setInitialBudget(audienceId, budget)
    }

    private fun initializeVideoSettings() = deviceDelegate.initializeVideoSettings()

    private fun observeVideoSettings() {
        viewModelScope.launch {
            videoStreamSettings.collect { settings ->
                settings?.let { deviceDelegate.applySettings(it) }
            }
        }
    }
}
