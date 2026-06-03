package com.example.watcher.data.repository

import android.graphics.Bitmap
import com.example.watcher.data.local.TimelineEventDao
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.local.VideoProcessRunDao
import com.example.watcher.data.local.VideoProcessTaskDao
import com.example.watcher.data.local.VideoRemoteFileBindingDao
import com.example.watcher.data.local.VideoSegmentRunDao
import com.example.watcher.data.local.VideoSpeechTranscriptDao
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoTaskPlan
import com.example.watcher.data.remote.ArkStreamingClient
import com.example.watcher.data.remote.DoubaoApiService
import kotlinx.coroutines.flow.Flow
import java.io.File

class VideoProcessRepository(
    private val apiService: DoubaoApiService,
    private val taskDao: VideoProcessTaskDao,
    private val runDao: VideoProcessRunDao,
    private val segmentRunDao: VideoSegmentRunDao,
    private val audioAssetDao: VideoAudioAssetDao,
    private val remoteFileBindingDao: VideoRemoteFileBindingDao,
    private val speechTranscriptDao: VideoSpeechTranscriptDao,
    private val timelineEventDao: TimelineEventDao,
    private val llmWalletRepository: LlmWalletRepository,
    private val recorder: MjpegVideoRecorder = MjpegVideoRecorder(),
    private val segmentMerger: VideoSegmentMerger = VideoSegmentMerger(),
    private val audioAssetBuilder: VideoAudioAssetBuilder = VideoAudioAssetBuilder(),
    private val streamingClient: ArkStreamingClient = ArkStreamingClient()
) {
    private val planner = VideoTaskPlanner(
        apiService = apiService,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey
    )

    private val remoteFileResolver = VideoRemoteFileResolver(
        apiService = apiService,
        bindingDao = remoteFileBindingDao,
        apiKey = ArkConfig.apiKey
    )

    private val segmentAnalyzer = VideoSegmentAnalyzer(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey
    )

    private val segmentProcessor = VideoSegmentProcessor(
        apiService = apiService,
        segmentRunDao = segmentRunDao,
        remoteFileResolver = remoteFileResolver,
        segmentAnalyzer = segmentAnalyzer,
        apiKey = ArkConfig.apiKey
    )

    private val segmentRecorder = VideoSegmentRecorder(
        recorder = recorder,
        audioAssetBuilder = audioAssetBuilder,
        remoteFileResolver = remoteFileResolver,
        segmentRunDao = segmentRunDao,
        audioAssetDao = audioAssetDao
    )

    private val mediaAssembler = VideoMediaAssembler(
        segmentMerger = segmentMerger,
        remoteFileResolver = remoteFileResolver
    )

    private val reportSummarizer = VideoReportSummarizer(
        apiService = apiService,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey
    )

    private val chunkAnalyzer = VideoEvidenceChunkAnalyzer(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey
    )

    private val reportRefiner = VideoReportRefiner(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey
    )

    private val audioOutlineProcessor = AudioOutlineProcessor(
        apiService = apiService,
        audioAssetDao = audioAssetDao,
        remoteFileResolver = remoteFileResolver,
        audioAssetBuilder = audioAssetBuilder,
        audioModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey
    )

    private val executionOrchestrator = VideoExecutionOrchestrator(
        taskDao = taskDao,
        runDao = runDao,
        timelineEventDao = timelineEventDao,
        saveTask = ::saveTask,
        segmentProcessor = segmentProcessor,
        segmentRecorder = segmentRecorder,
        mediaAssembler = mediaAssembler,
        reportSummarizer = reportSummarizer,
        chunkAnalyzer = chunkAnalyzer,
        reportRefiner = reportRefiner,
        audioOutlineProcessor = audioOutlineProcessor,
        remoteFileResolver = remoteFileResolver
    )

    fun observeTasks(): Flow<List<VideoProcessTask>> = taskDao.observeTasks()

    fun observeRecentRuns(): Flow<List<VideoProcessRun>> = runDao.observeRecentRuns()

    fun observeTimelineForRun(runId: Long) = timelineEventDao.observeEventsForRun(runId)

    fun observeSpeechForRun(runId: Long) = speechTranscriptDao.observeForRun(runId)

    suspend fun saveTask(draft: VideoProcessTaskDraft): VideoProcessTaskDraft {
        val normalized = draft.normalized()
        val existing = normalized.taskId?.let { taskDao.getTaskById(it) }
        val entity = normalized.toEntity(existing)
        val taskId = taskDao.upsert(entity)
        return VideoProcessTaskDraft.fromEntity(
            entity.copy(id = if (entity.id == 0L) taskId else entity.id)
        )
    }

    suspend fun deleteTask(id: Long) {
        taskDao.deleteById(id)
    }

    suspend fun planVideoTask(userInput: String, frame: Bitmap?): Result<VideoTaskPlan> {
        return planner.planVideoTask(userInput, frame)
    }

    suspend fun executeTask(
        draft: VideoProcessTaskDraft,
        streamingOutputEnabled: Boolean,
        latestFrameProvider: () -> Bitmap?,
        outputRoot: File,
        shouldStopRequested: () -> Boolean = { false },
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): VideoExecutionResult {
        return executionOrchestrator.executeTask(
            draft = draft,
            streamingOutputEnabled = streamingOutputEnabled,
            latestFrameProvider = latestFrameProvider,
            outputRoot = outputRoot,
            shouldStopRequested = shouldStopRequested,
            onStatus = onStatus
        )
    }

    suspend fun markRunFailed(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        error: Throwable,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        executionOrchestrator.markRunFailed(
            runId = runId,
            segmentIndex = segmentIndex,
            segmentCount = segmentCount,
            streamingEnabled = streamingEnabled,
            error = error,
            onStatus = onStatus
        )
    }

    suspend fun markRunCancelled(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        executionOrchestrator.markRunCancelled(
            runId = runId,
            segmentIndex = segmentIndex,
            segmentCount = segmentCount,
            streamingEnabled = streamingEnabled,
            onStatus = onStatus
        )
    }
}
