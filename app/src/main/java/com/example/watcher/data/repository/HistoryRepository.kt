package com.example.watcher.data.repository

import android.content.Context
import com.example.watcher.data.local.ClassroomNoteFollowupDao
import com.example.watcher.data.local.MonitorEventDao
import com.example.watcher.data.local.MonitorEventRunSummary
import com.example.watcher.data.local.MonitorMediaDao
import com.example.watcher.data.local.MonitorMediaRunSummary
import com.example.watcher.data.local.MonitorRunDao
import com.example.watcher.data.local.MonitorTaskDao
import com.example.watcher.data.local.TimelineEventDao
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.local.VideoProcessRunDao
import com.example.watcher.data.local.VideoProcessTaskDao
import com.example.watcher.data.local.VideoRemoteFileBindingDao
import com.example.watcher.data.local.VideoSegmentRunDao
import com.example.watcher.data.local.VideoSegmentRunSummary
import com.example.watcher.data.local.VideoSpeechTranscriptDao
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.HistoryRecordDetail
import com.example.watcher.data.model.HistoryRecordItem
import com.example.watcher.data.model.HistoryRecordSelection
import com.example.watcher.data.model.HistoryRecordType
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorEventEntity
import com.example.watcher.data.model.MonitorLogAction
import com.example.watcher.data.model.MonitorMediaEntity
import com.example.watcher.data.model.MonitorMediaType
import com.example.watcher.data.model.MonitorRun
import com.example.watcher.data.model.MonitorRunStatus
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.StorageSummary
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoAudioAssetEntity
import com.example.watcher.data.model.VideoHistoryDetail
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.VideoSegmentRun
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import com.example.watcher.data.model.historyMonitorRunStatusLabel
import com.example.watcher.data.model.historyTypeLabel
import com.example.watcher.data.model.historyVideoRunStatusLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File

const val HISTORY_DETAIL_PREVIEW_LIMIT = 50

class HistoryRepository(
    private val appContext: Context,
    private val monitorRunDao: MonitorRunDao,
    private val monitorEventDao: MonitorEventDao,
    private val monitorMediaDao: MonitorMediaDao,
    private val monitorTaskDao: MonitorTaskDao,
    private val videoProcessTaskDao: VideoProcessTaskDao,
    private val videoRunDao: VideoProcessRunDao,
    private val videoSegmentRunDao: VideoSegmentRunDao,
    private val videoAudioAssetDao: VideoAudioAssetDao,
    private val videoRemoteFileBindingDao: VideoRemoteFileBindingDao,
    private val videoSpeechTranscriptDao: VideoSpeechTranscriptDao,
    private val classroomNoteFollowupDao: ClassroomNoteFollowupDao,
    private val timelineEventDao: TimelineEventDao
) {
    fun observeHistoryRecords(): Flow<List<HistoryRecordItem>> {
        return combine(
            videoRunDao.observeAllRuns(),
            videoSegmentRunDao.observeRunFileSummaries(),
            monitorRunDao.observeAllRuns(),
            monitorEventDao.observeRunSummaries(),
            monitorMediaDao.observeRunSummaries()
        ) { videoRuns, videoSegmentSummaries, monitorRuns, monitorEventSummaries, monitorMediaSummaries ->
            val segmentsByRun = videoSegmentSummaries.associateBy(VideoSegmentRunSummary::runId)
            val eventsByRun = monitorEventSummaries.associateBy(MonitorEventRunSummary::runId)
            val mediaByRun = monitorMediaSummaries.associateBy(MonitorMediaRunSummary::runId)
            val videoItems = videoRuns.map { run ->
                val segmentSummary = segmentsByRun[run.id]
                val mergedVideoCount = listOf(run.fullMediaPath, run.mergedVideoPath)
                    .count { !it.isNullOrBlank() }
                HistoryRecordItem(
                    selection = HistoryRecordSelection(HistoryRecordType.VideoAnalysis, run.id),
                    title = run.taskTitle.ifBlank { "视频分析任务" },
                    summary = run.finalSummary.ifBlank {
                        run.errorMessage ?: run.finalConclusion.ifBlank { "暂无分析摘要" }
                    },
                    statusLabel = historyVideoRunStatusLabel(run),
                    updatedAt = run.updatedAt,
                    startedAt = run.recordingStartedAt ?: run.createdAt,
                    typeLabel = historyTypeLabel(HistoryRecordType.VideoAnalysis),
                    hasMedia = mergedVideoCount > 0 || (segmentSummary?.segmentFileCount ?: 0) > 0,
                    mediaCount = mergedVideoCount + (segmentSummary?.segmentFileCount ?: 0),
                    previewPath = run.fullMediaPath
                        ?: run.mergedVideoPath
                        ?: segmentSummary?.previewPath
                )
            }
            val monitorItems = monitorRuns.map { run ->
                val eventSummary = eventsByRun[run.id]
                val mediaSummary = mediaByRun[run.id]
                val eventFrames = eventSummary?.frameCount ?: 0
                val assetCount = (mediaSummary?.mediaCount ?: 0) +
                    if (run.baselineImagePath != null) 1 else 0 +
                    if (run.sessionVideoPath != null) 1 else 0 +
                    eventFrames
                HistoryRecordItem(
                    selection = HistoryRecordSelection(HistoryRecordType.LiveMonitor, run.id),
                    title = run.taskTitle.ifBlank { "实时监控任务" },
                    summary = run.lastSummary.ifBlank { run.lastReason.ifBlank { "暂无监控摘要" } },
                    statusLabel = historyMonitorRunStatusLabel(run),
                    updatedAt = run.updatedAt,
                    startedAt = run.startedAt,
                    typeLabel = historyTypeLabel(HistoryRecordType.LiveMonitor),
                    hasMedia = assetCount > 0,
                    mediaCount = assetCount,
                    previewPath = run.sessionVideoPath
                        ?: run.baselineImagePath
                        ?: eventSummary?.previewFramePath
                        ?: mediaSummary?.previewPath
                )
            }
            (videoItems + monitorItems).sortedByDescending(HistoryRecordItem::updatedAt)
        }
    }

    fun observeStorageSummary(): Flow<StorageSummary> {
        return combine(
            videoRunDao.observeAllRuns(),
            videoSegmentRunDao.observeAllSegmentsWithFiles(),
            monitorRunDao.observeAllRuns(),
            monitorEventDao.observeAllEvents(),
            monitorMediaDao.observeAllMedia()
        ) { videoRuns, videoSegments, monitorRuns, monitorEvents, monitorMedia ->
            val eventFramePaths = monitorEvents.mapNotNull(MonitorEventEntity::frameImagePath)
            val paths = buildSet {
                videoRuns.mapNotNullTo(this) { it.fullMediaPath }
                videoRuns.mapNotNullTo(this) { it.mergedVideoPath }
                videoSegments.mapNotNullTo(this) { it.localFilePath }
                monitorMedia.mapTo(this) { it.localFilePath }
                monitorRuns.mapNotNullTo(this) { it.baselineImagePath }
                monitorRuns.mapNotNullTo(this) { it.sessionVideoPath }
                addAll(eventFramePaths)
            }
            StorageSummary(
                totalBytes = paths.sumOf(::fileSize),
                recordCount = videoRuns.size + monitorRuns.size,
                mediaCount = videoRuns.count { !it.fullMediaPath.isNullOrBlank() } +
                    videoRuns.count { !it.mergedVideoPath.isNullOrBlank() } +
                    videoSegments.size +
                    monitorMedia.size +
                    monitorRuns.count { it.baselineImagePath != null } +
                    monitorRuns.count { it.sessionVideoPath != null } +
                    eventFramePaths.size
            )
        }
    }

    fun observeHistoryDetail(selection: HistoryRecordSelection): Flow<HistoryRecordDetail?> {
        return when (selection.type) {
            HistoryRecordType.VideoAnalysis -> {
                val debugAssets = combine(
                    videoAudioAssetDao.observeForRunLimited(selection.recordId, HISTORY_DETAIL_PREVIEW_LIMIT),
                    videoRemoteFileBindingDao.observeForRunLimited(selection.recordId, HISTORY_DETAIL_PREVIEW_LIMIT),
                    timelineEventDao.observeEventsForRunLimited(selection.recordId, HISTORY_DETAIL_PREVIEW_LIMIT),
                    videoSpeechTranscriptDao.observeRecentForRun(selection.recordId, HISTORY_DETAIL_PREVIEW_LIMIT)
                ) { audioAssets, remoteFileBindings, events, speechTranscripts ->
                    VideoHistoryDebugAssets(
                        audioAssets = audioAssets,
                        remoteFileBindings = remoteFileBindings,
                        events = events,
                        speechTranscripts = speechTranscripts
                    )
                }
                val debugCounts = combine(
                    videoAudioAssetDao.observeCountForRun(selection.recordId),
                    videoRemoteFileBindingDao.observeCountForRun(selection.recordId),
                    timelineEventDao.observeEventCountForRun(selection.recordId),
                    videoSpeechTranscriptDao.observeCountForRun(selection.recordId)
                ) { audioAssetCount, remoteFileBindingCount, eventCount, speechTranscriptCount ->
                    VideoHistoryDebugCounts(
                        audioAssetCount = audioAssetCount,
                        remoteFileBindingCount = remoteFileBindingCount,
                        eventCount = eventCount,
                        speechTranscriptCount = speechTranscriptCount
                    )
                }
                combine(
                    videoRunDao.observeRunById(selection.recordId),
                    videoSegmentRunDao.observeSegmentsForRunLimited(selection.recordId, HISTORY_DETAIL_PREVIEW_LIMIT),
                    videoSegmentRunDao.observeSegmentCountForRun(selection.recordId),
                    debugAssets,
                    debugCounts
                ) { run, segments, segmentCount, assets, counts ->
                    run?.let {
                        VideoHistoryDetail(
                            run = it,
                            task = videoProcessTaskDao.getTaskById(it.taskId),
                            segments = segments,
                            audioAssets = assets.audioAssets,
                            remoteFileBindings = assets.remoteFileBindings,
                            events = assets.events,
                            speechTranscripts = assets.speechTranscripts,
                            totalSegmentCount = segmentCount,
                            totalAudioAssetCount = counts.audioAssetCount,
                            totalRemoteFileBindingCount = counts.remoteFileBindingCount,
                            totalEventCount = counts.eventCount,
                            totalSpeechTranscriptCount = counts.speechTranscriptCount
                        )
                    }
                }
            }

            HistoryRecordType.LiveMonitor -> {
                val previewAssets = combine(
                    monitorEventDao.observeRecentEventsForRun(selection.recordId, HISTORY_DETAIL_PREVIEW_LIMIT),
                    monitorMediaDao.observeRecentMediaForRun(selection.recordId, HISTORY_DETAIL_PREVIEW_LIMIT),
                    monitorEventDao.observeEventCountForRun(selection.recordId),
                    monitorMediaDao.observeMediaCountForRun(selection.recordId)
                ) { events, media, eventCount, mediaCount ->
                    MonitorHistoryPreviewAssets(
                        events = events,
                        media = media,
                        eventCount = eventCount,
                        mediaCount = mediaCount
                    )
                }
                combine(
                    monitorRunDao.observeRunById(selection.recordId),
                    previewAssets
                ) { run, assets ->
                    run?.let {
                        com.example.watcher.data.model.MonitorHistoryDetail(
                            run = it,
                            task = it.taskId?.let { taskId -> monitorTaskDao.getTaskById(taskId) },
                            events = assets.events,
                            media = assets.media,
                            totalEventCount = assets.eventCount,
                            totalMediaCount = assets.mediaCount
                        )
                    }
                }
            }
        }
    }

    fun observeFullVideoHistoryDetail(runId: Long): Flow<VideoHistoryDetail?> {
        val fullAssets = combine(
            videoAudioAssetDao.observeForRun(runId),
            videoRemoteFileBindingDao.observeForRun(runId),
            timelineEventDao.observeEventsForRun(runId),
            videoSpeechTranscriptDao.observeForRun(runId)
        ) { audioAssets, remoteFileBindings, events, speechTranscripts ->
            VideoHistoryDebugAssets(
                audioAssets = audioAssets,
                remoteFileBindings = remoteFileBindings,
                events = events,
                speechTranscripts = speechTranscripts
            )
        }
        return combine(
            videoRunDao.observeRunById(runId),
            videoSegmentRunDao.observeSegmentsForRun(runId),
            fullAssets
        ) { run, segments, assets ->
            run?.let {
                buildVideoHistoryDetail(
                    run = it,
                    segments = segments,
                    audioAssets = assets.audioAssets,
                    remoteFileBindings = assets.remoteFileBindings,
                    events = assets.events,
                    speechTranscripts = assets.speechTranscripts
                )
            }
        }
    }

    suspend fun getFullHistoryDetail(selection: HistoryRecordSelection): HistoryRecordDetail? {
        return when (selection.type) {
            HistoryRecordType.VideoAnalysis -> getFullVideoHistoryDetail(selection.recordId)
            HistoryRecordType.LiveMonitor -> {
                val run = monitorRunDao.getRunById(selection.recordId) ?: return null
                val events = monitorEventDao.getEventsForRun(selection.recordId)
                val media = monitorMediaDao.getMediaForRun(selection.recordId)
                com.example.watcher.data.model.MonitorHistoryDetail(
                    run = run,
                    task = run.taskId?.let { taskId -> monitorTaskDao.getTaskById(taskId) },
                    events = events,
                    media = media,
                    totalEventCount = events.size,
                    totalMediaCount = media.size
                )
            }
        }
    }

    suspend fun getFullVideoHistoryDetail(runId: Long): VideoHistoryDetail? {
        val run = videoRunDao.getRunById(runId) ?: return null
        return buildVideoHistoryDetail(
            run = run,
            segments = videoSegmentRunDao.getSegmentsForRun(runId),
            audioAssets = videoAudioAssetDao.getForRun(runId),
            remoteFileBindings = videoRemoteFileBindingDao.getForRun(runId),
            events = timelineEventDao.getEventsForRun(runId),
            speechTranscripts = videoSpeechTranscriptDao.getForRun(runId)
        )
    }

    private suspend fun buildVideoHistoryDetail(
        run: VideoProcessRun,
        segments: List<VideoSegmentRun>,
        audioAssets: List<VideoAudioAssetEntity>,
        remoteFileBindings: List<VideoRemoteFileBindingEntity>,
        events: List<TimelineEventEntity>,
        speechTranscripts: List<VideoSpeechTranscriptEntity>
    ): VideoHistoryDetail {
        return VideoHistoryDetail(
            run = run,
            task = videoProcessTaskDao.getTaskById(run.taskId),
            segments = segments,
            audioAssets = audioAssets,
            remoteFileBindings = remoteFileBindings,
            events = events,
            speechTranscripts = speechTranscripts,
            totalSegmentCount = segments.size,
            totalAudioAssetCount = audioAssets.size,
            totalRemoteFileBindingCount = remoteFileBindings.size,
            totalEventCount = events.size,
            totalSpeechTranscriptCount = speechTranscripts.size
        )
    }

    suspend fun startMonitorRun(task: IntentResult): Long {
        val now = System.currentTimeMillis()
        return monitorRunDao.upsert(
            MonitorRun(
                taskId = task.taskId,
                taskTitle = task.title,
                taskRequirement = task.userRequirement,
                monitorMode = task.monitorMode,
                targetTrigger = task.targetTrigger,
                baselineSource = task.baselineSource,
                status = MonitorRunStatus.Running,
                startedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun appendMonitorEvent(
        runId: Long,
        result: CheckResult,
        message: String,
        action: MonitorLogAction,
        frameImagePath: String? = null,
        confidence: Float? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        monitorEventDao.insert(
            MonitorEventEntity(
                runId = runId,
                timestamp = timestamp,
                result = result,
                message = message,
                action = action,
                frameImagePath = frameImagePath,
                confidence = confidence,
                createdAt = timestamp
            )
        )
    }

    suspend fun syncMonitorRunState(
        runId: Long,
        task: IntentResult?,
        status: MonitorStatus,
        runStatus: MonitorRunStatus,
        baselineImagePath: String? = null,
        sessionVideoPath: String? = null,
        endedAt: Long? = null
    ) {
        val existing = monitorRunDao.getRunById(runId) ?: return
        monitorRunDao.upsert(
            existing.copy(
                taskId = task?.taskId ?: existing.taskId,
                taskTitle = task?.title ?: existing.taskTitle,
                taskRequirement = task?.userRequirement ?: existing.taskRequirement,
                monitorMode = task?.monitorMode ?: existing.monitorMode,
                targetTrigger = task?.targetTrigger ?: existing.targetTrigger,
                baselineSource = task?.baselineSource ?: existing.baselineSource,
                status = runStatus,
                endedAt = endedAt,
                baselineImagePath = baselineImagePath ?: existing.baselineImagePath,
                sessionVideoPath = sessionVideoPath ?: existing.sessionVideoPath,
                lastResult = status.lastResult,
                lastSummary = status.lastSummary,
                lastReason = status.lastReason,
                alertCount = status.alertCount,
                warningCount = status.warningCount,
                unknownCount = status.unknownCount,
                normalCount = status.normalCount,
                totalCheckCount = status.totalCheckCount,
                skippedCount = status.skippedCount,
                failureCount = status.failureCount,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun addMonitorMedia(
        runId: Long,
        localFilePath: String,
        mediaType: MonitorMediaType = MonitorMediaType.Snapshot
    ) {
        monitorMediaDao.insert(
            MonitorMediaEntity(
                runId = runId,
                mediaType = mediaType,
                localFilePath = localFilePath
            )
        )
    }

    suspend fun deleteHistoryRecord(selection: HistoryRecordSelection) {
        when (selection.type) {
            HistoryRecordType.VideoAnalysis -> {
                val run = videoRunDao.getRunById(selection.recordId)
                if (run != null) {
                    val manifest = RunLocalResourceCollector.collectVideoRunResources(
                        videoRunsDir = File(appContext.filesDir, "video_runs"),
                        run = run,
                        segments = videoSegmentRunDao.getSegmentsForRun(selection.recordId),
                        audioAssets = videoAudioAssetDao.getForRun(selection.recordId),
                        remoteFileBindings = videoRemoteFileBindingDao.getForRun(selection.recordId)
                    )
                    deleteLocalResourceManifest(manifest)
                }
                classroomNoteFollowupDao.deleteByRunId(selection.recordId)
                videoRunDao.deleteById(selection.recordId)
            }

            HistoryRecordType.LiveMonitor -> {
                val run = monitorRunDao.getRunById(selection.recordId)
                if (run != null) {
                    val manifest = RunLocalResourceCollector.collectMonitorRunResources(
                        run = run,
                        events = monitorEventDao.getEventsForRun(selection.recordId),
                        media = monitorMediaDao.getMediaForRun(selection.recordId)
                    )
                    deleteLocalResourceManifest(manifest)
                }
                monitorRunDao.deleteById(selection.recordId)
            }
        }
    }

    private fun deleteLocalResourceManifest(manifest: RunLocalResourceManifest) {
        val safeManifest = manifest.managedBy(localResourceRoots())
        safeManifest.files.forEach { file ->
            runCatching {
                if (file.exists() && file.isFile) {
                    file.delete()
                }
            }
        }
        safeManifest.directories.forEach { directory ->
            runCatching {
                if (directory.exists() && directory.isDirectory) {
                    directory.deleteRecursively()
                }
            }
        }
        ClassroomRealtimeDiagnostics.ast("run_resource_cleanup run=${manifest.runId}")
    }

    private fun localResourceRoots(): List<File> = buildList {
        add(appContext.filesDir)
        add(appContext.cacheDir)
        appContext.externalCacheDir?.let(::add)
        appContext.getExternalFilesDirs(null).filterNotNull().forEach(::add)
    }

    private fun fileSize(path: String): Long {
        val file = File(path)
        return if (file.exists()) file.length() else 0L
    }
}

private data class VideoHistoryDebugAssets(
    val audioAssets: List<VideoAudioAssetEntity>,
    val remoteFileBindings: List<VideoRemoteFileBindingEntity>,
    val events: List<TimelineEventEntity>,
    val speechTranscripts: List<VideoSpeechTranscriptEntity>
)

private data class VideoHistoryDebugCounts(
    val audioAssetCount: Int,
    val remoteFileBindingCount: Int,
    val eventCount: Int,
    val speechTranscriptCount: Int
)

private data class MonitorHistoryPreviewAssets(
    val events: List<MonitorEventEntity>,
    val media: List<MonitorMediaEntity>,
    val eventCount: Int,
    val mediaCount: Int
)
