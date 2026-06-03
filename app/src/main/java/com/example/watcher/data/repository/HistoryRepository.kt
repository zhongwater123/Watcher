package com.example.watcher.data.repository

import com.example.watcher.data.local.MonitorEventDao
import com.example.watcher.data.local.MonitorMediaDao
import com.example.watcher.data.local.MonitorRunDao
import com.example.watcher.data.local.MonitorTaskDao
import com.example.watcher.data.local.TimelineEventDao
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.local.VideoProcessRunDao
import com.example.watcher.data.local.VideoProcessTaskDao
import com.example.watcher.data.local.VideoRemoteFileBindingDao
import com.example.watcher.data.local.VideoSegmentRunDao
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
import com.example.watcher.data.model.historyTypeLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File

class HistoryRepository(
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
    private val timelineEventDao: TimelineEventDao
) {
    fun observeHistoryRecords(): Flow<List<HistoryRecordItem>> {
        return combine(
            videoRunDao.observeAllRuns(),
            videoSegmentRunDao.observeAllSegmentsWithFiles(),
            monitorRunDao.observeAllRuns(),
            monitorEventDao.observeAllEvents(),
            monitorMediaDao.observeAllMedia()
        ) { videoRuns, videoSegments, monitorRuns, monitorEvents, monitorMedia ->
            val segmentsByRun = videoSegments.groupBy(VideoSegmentRun::runId)
            val eventsByRun = monitorEvents.groupBy(MonitorEventEntity::runId)
            val mediaByRun = monitorMedia.groupBy(MonitorMediaEntity::runId)
            val videoItems = videoRuns.map { run ->
                val segments = segmentsByRun[run.id].orEmpty().sortedBy(VideoSegmentRun::segmentIndex)
                val mergedVideoCount = listOf(run.fullMediaPath, run.mergedVideoPath)
                    .count { !it.isNullOrBlank() }
                HistoryRecordItem(
                    selection = HistoryRecordSelection(HistoryRecordType.VideoAnalysis, run.id),
                    title = run.taskTitle.ifBlank { "视频分析任务" },
                    summary = run.finalSummary.ifBlank {
                        run.errorMessage ?: run.finalConclusion.ifBlank { "暂无分析摘要" }
                    },
                    statusLabel = com.example.watcher.data.model.videoRunStatusLabel(run.status),
                    updatedAt = run.updatedAt,
                    startedAt = run.recordingStartedAt ?: run.createdAt,
                    typeLabel = historyTypeLabel(HistoryRecordType.VideoAnalysis),
                    hasMedia = mergedVideoCount > 0 || segments.isNotEmpty(),
                    mediaCount = mergedVideoCount + segments.count { !it.localFilePath.isNullOrBlank() },
                    previewPath = run.fullMediaPath
                        ?: run.mergedVideoPath
                        ?: segments.firstNotNullOfOrNull { it.localFilePath }
                )
            }
            val monitorItems = monitorRuns.map { run ->
                val events = eventsByRun[run.id].orEmpty()
                val media = mediaByRun[run.id].orEmpty()
                val eventFrames = events.count { !it.frameImagePath.isNullOrBlank() }
                val assetCount = media.size +
                    if (run.baselineImagePath != null) 1 else 0 +
                    if (run.sessionVideoPath != null) 1 else 0 +
                    eventFrames
                HistoryRecordItem(
                    selection = HistoryRecordSelection(HistoryRecordType.LiveMonitor, run.id),
                    title = run.taskTitle.ifBlank { "实时监控任务" },
                    summary = run.lastSummary.ifBlank { run.lastReason.ifBlank { "暂无监控摘要" } },
                    statusLabel = com.example.watcher.data.model.monitorRunStatusLabel(run.status),
                    updatedAt = run.updatedAt,
                    startedAt = run.startedAt,
                    typeLabel = historyTypeLabel(HistoryRecordType.LiveMonitor),
                    hasMedia = assetCount > 0,
                    mediaCount = assetCount,
                    previewPath = run.sessionVideoPath
                        ?: run.baselineImagePath
                        ?: events.firstNotNullOfOrNull { it.frameImagePath }
                        ?: media.firstOrNull()?.localFilePath
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
                    videoAudioAssetDao.observeForRun(selection.recordId),
                    videoRemoteFileBindingDao.observeForRun(selection.recordId),
                    timelineEventDao.observeEventsForRun(selection.recordId),
                    videoSpeechTranscriptDao.observeForRun(selection.recordId)
                ) { audioAssets, remoteFileBindings, events, speechTranscripts ->
                    VideoHistoryDebugAssets(
                        audioAssets = audioAssets,
                        remoteFileBindings = remoteFileBindings,
                        events = events,
                        speechTranscripts = speechTranscripts
                    )
                }
                combine(
                    videoRunDao.observeRunById(selection.recordId),
                    videoSegmentRunDao.observeSegmentsForRun(selection.recordId),
                    debugAssets
                ) { run, segments, assets ->
                    run?.let {
                        VideoHistoryDetail(
                            run = it,
                            task = videoProcessTaskDao.getTaskById(it.taskId),
                            segments = segments,
                            audioAssets = assets.audioAssets,
                            remoteFileBindings = assets.remoteFileBindings,
                            events = assets.events,
                            speechTranscripts = assets.speechTranscripts
                        )
                    }
                }
            }

            HistoryRecordType.LiveMonitor -> combine(
                monitorRunDao.observeRunById(selection.recordId),
                monitorEventDao.observeEventsForRun(selection.recordId),
                monitorMediaDao.observeMediaForRun(selection.recordId)
            ) { run, events, media ->
                run?.let {
                    com.example.watcher.data.model.MonitorHistoryDetail(
                        run = it,
                        task = it.taskId?.let { taskId -> monitorTaskDao.getTaskById(taskId) },
                        events = events,
                        media = media
                    )
                }
            }
        }
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
                videoRunDao.getRunById(selection.recordId)?.let { run ->
                    run.fullMediaPath?.let(::deleteFileIfExists)
                    run.mergedVideoPath?.let(::deleteFileIfExists)
                }
                videoSegmentRunDao.getSegmentsForRun(selection.recordId)
                    .mapNotNull(VideoSegmentRun::localFilePath)
                    .forEach(::deleteFileIfExists)
                videoRunDao.deleteById(selection.recordId)
            }

            HistoryRecordType.LiveMonitor -> {
                monitorEventDao.getEventsForRun(selection.recordId)
                    .mapNotNull(MonitorEventEntity::frameImagePath)
                    .forEach(::deleteFileIfExists)
                monitorMediaDao.getMediaForRun(selection.recordId)
                    .map(MonitorMediaEntity::localFilePath)
                    .forEach(::deleteFileIfExists)
                monitorRunDao.getRunById(selection.recordId)?.let { run ->
                    run.baselineImagePath?.let(::deleteFileIfExists)
                    run.sessionVideoPath?.let(::deleteFileIfExists)
                }
                monitorRunDao.deleteById(selection.recordId)
            }
        }
    }

    private fun deleteFileIfExists(path: String) {
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
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
