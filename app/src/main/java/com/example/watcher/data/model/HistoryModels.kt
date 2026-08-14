package com.example.watcher.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "monitor_runs",
    indices = [Index("updatedAt")]
)
data class MonitorRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long? = null,
    val taskTitle: String,
    val taskRequirement: String,
    val monitorMode: MonitorMode = MonitorMode.SceneBaseline,
    val targetTrigger: TargetTrigger = TargetTrigger.OnAppear,
    val baselineSource: BaselineSource = BaselineSource.CapturedFrame,
    val status: MonitorRunStatus = MonitorRunStatus.Running,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val baselineImagePath: String? = null,
    val sessionVideoPath: String? = null,
    val lastResult: CheckResult = CheckResult.NONE,
    val lastSummary: String = "",
    val lastReason: String = "",
    val alertCount: Int = 0,
    val warningCount: Int = 0,
    val unknownCount: Int = 0,
    val normalCount: Int = 0,
    val totalCheckCount: Int = 0,
    val skippedCount: Int = 0,
    val failureCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class MonitorRunStatus {
    Running,
    Paused,
    Completed
}

@Entity(
    tableName = "monitor_events",
    foreignKeys = [
        ForeignKey(
            entity = MonitorRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("runId"), Index("timestamp")]
)
data class MonitorEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val result: CheckResult,
    val message: String,
    val action: MonitorLogAction,
    val frameImagePath: String? = null,
    val confidence: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "monitor_media",
    foreignKeys = [
        ForeignKey(
            entity = MonitorRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("runId"), Index("createdAt")]
)
data class MonitorMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long,
    val mediaType: MonitorMediaType = MonitorMediaType.Snapshot,
    val localFilePath: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class MonitorMediaType {
    Snapshot,
    Baseline,
    EventFrame,
    SessionVideo
}

enum class HistoryRecordType {
    VideoAnalysis,
    LiveMonitor
}

data class HistoryRecordSelection(
    val type: HistoryRecordType,
    val recordId: Long
)

data class HistoryRecordItem(
    val selection: HistoryRecordSelection,
    val title: String,
    val summary: String,
    val statusLabel: String,
    val updatedAt: Long,
    val startedAt: Long,
    val typeLabel: String,
    val hasMedia: Boolean,
    val mediaCount: Int,
    val previewPath: String? = null
)

sealed interface HistoryRecordDetail {
    val selection: HistoryRecordSelection
    val title: String
    val requirement: String
    val statusLabel: String
    val summary: String
    val startedAt: Long
    val updatedAt: Long
    val canDelete: Boolean
}

data class VideoHistoryDetail(
    val run: VideoProcessRun,
    val task: VideoProcessTask? = null,
    val segments: List<VideoSegmentRun>,
    val audioAssets: List<VideoAudioAssetEntity> = emptyList(),
    val remoteFileBindings: List<VideoRemoteFileBindingEntity> = emptyList(),
    val events: List<TimelineEventEntity>,
    val speechTranscripts: List<VideoSpeechTranscriptEntity> = emptyList(),
    val totalSegmentCount: Int = segments.size,
    val totalAudioAssetCount: Int = audioAssets.size,
    val totalRemoteFileBindingCount: Int = remoteFileBindings.size,
    val totalEventCount: Int = events.size,
    val totalSpeechTranscriptCount: Int = speechTranscripts.size
) : HistoryRecordDetail {
    override val selection: HistoryRecordSelection =
        HistoryRecordSelection(HistoryRecordType.VideoAnalysis, run.id)
    override val title: String = run.taskTitle.ifBlank { "视频分析任务" }
    override val requirement: String = run.taskRequirement
    override val statusLabel: String = historyVideoRunStatusLabel(run)
    override val summary: String = run.finalSummary.ifBlank { run.errorMessage ?: run.finalConclusion }
    override val startedAt: Long = run.recordingStartedAt ?: run.createdAt
    override val updatedAt: Long = run.updatedAt
    override val canDelete: Boolean = run.status !in ACTIVE_VIDEO_RUN_STATUSES || isStalePostCaptureVideoRun(run)
    val mergedVideoPath: String? = run.mergedVideoPath
    val fullMediaPath: String? = run.fullMediaPath
    val previewPath: String? = run.fullMediaPath
        ?: run.mergedVideoPath
        ?: segments.firstNotNullOfOrNull { it.localFilePath }
}

data class MonitorHistoryDetail(
    val run: MonitorRun,
    val task: MonitorTask? = null,
    val events: List<MonitorEventEntity>,
    val media: List<MonitorMediaEntity>,
    val totalEventCount: Int = events.size,
    val totalMediaCount: Int = media.size
) : HistoryRecordDetail {
    override val selection: HistoryRecordSelection =
        HistoryRecordSelection(HistoryRecordType.LiveMonitor, run.id)
    override val title: String = run.taskTitle.ifBlank { "实时监控任务" }
    override val requirement: String = run.taskRequirement
    override val statusLabel: String = historyMonitorRunStatusLabel(run)
    override val summary: String = run.lastSummary.ifBlank { run.lastReason.ifBlank { "暂无摘要" } }
    override val startedAt: Long = run.startedAt
    override val updatedAt: Long = run.updatedAt
    override val canDelete: Boolean = run.status == MonitorRunStatus.Completed || isStaleMonitorRun(run)
    val previewPath: String? = run.sessionVideoPath
        ?: run.baselineImagePath
        ?: events.firstNotNullOfOrNull { it.frameImagePath }
        ?: media.firstOrNull()?.localFilePath
}

data class StorageSummary(
    val totalBytes: Long = 0,
    val recordCount: Int = 0,
    val mediaCount: Int = 0
)

val ACTIVE_VIDEO_RUN_STATUSES = setOf(
    VideoRunStatus.Recording,
    VideoRunStatus.Uploading,
    VideoRunStatus.Preprocessing,
    VideoRunStatus.Analyzing,
    VideoRunStatus.Summarizing
)

private val POST_CAPTURE_VIDEO_RUN_STATUSES = setOf(
    VideoRunStatus.Uploading,
    VideoRunStatus.Preprocessing,
    VideoRunStatus.Analyzing,
    VideoRunStatus.Summarizing
)

private const val STALE_POST_CAPTURE_CLEANUP_AFTER_MS = 30 * 60 * 1_000L
private const val STALE_MONITOR_RUN_CLEANUP_AFTER_MS = 6 * 60 * 60 * 1_000L

private val STALE_CLEANUP_MONITOR_RUN_STATUSES = setOf(
    MonitorRunStatus.Running,
    MonitorRunStatus.Paused
)

fun isStalePostCaptureVideoRun(
    run: VideoProcessRun,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (run.status in POST_CAPTURE_VIDEO_RUN_STATUSES &&
        run.recordingEndedAt != null &&
        now - run.updatedAt >= STALE_POST_CAPTURE_CLEANUP_AFTER_MS
    ) return true
    // Also treat Recording runs as stale if no update for 30+ minutes
    if (run.status == VideoRunStatus.Recording &&
        now - run.updatedAt >= STALE_POST_CAPTURE_CLEANUP_AFTER_MS
    ) return true
    return false
}

fun historyVideoRunStatusLabel(run: VideoProcessRun): String {
    val base = videoRunStatusLabel(run.status)
    return if (isStalePostCaptureVideoRun(run)) {
        "$base(疑似中断)"
    } else {
        base
    }
}

fun isStaleMonitorRun(
    run: MonitorRun,
    now: Long = System.currentTimeMillis()
): Boolean {
    return run.status in STALE_CLEANUP_MONITOR_RUN_STATUSES &&
        now - run.updatedAt >= STALE_MONITOR_RUN_CLEANUP_AFTER_MS
}

fun historyMonitorRunStatusLabel(
    run: MonitorRun,
    now: Long = System.currentTimeMillis()
): String {
    val base = monitorRunStatusLabel(run.status)
    return if (isStaleMonitorRun(run, now)) {
        if (run.status == MonitorRunStatus.Running) "疑似中断" else "$base(疑似中断)"
    } else {
        base
    }
}

fun videoRunStatusLabel(status: VideoRunStatus): String {
    return when (status) {
        VideoRunStatus.Idle -> "空闲"
        VideoRunStatus.Planning -> "规划中"
        VideoRunStatus.AwaitingConfirmation -> "待确认"
        VideoRunStatus.Recording -> "录制中"
        VideoRunStatus.Uploading -> "上传中"
        VideoRunStatus.Preprocessing -> "预处理中"
        VideoRunStatus.Analyzing -> "分析中"
        VideoRunStatus.Summarizing -> "汇总中"
        VideoRunStatus.Completed -> "已完成"
        VideoRunStatus.CompletedDegraded -> "完成(降级)"
        VideoRunStatus.Failed -> "失败"
        VideoRunStatus.Cancelled -> "已取消"
    }
}

fun monitorRunStatusLabel(status: MonitorRunStatus): String {
    return when (status) {
        MonitorRunStatus.Running -> "监控中"
        MonitorRunStatus.Paused -> "已暂停"
        MonitorRunStatus.Completed -> "已结束"
    }
}

fun historyTypeLabel(type: HistoryRecordType): String {
    return when (type) {
        HistoryRecordType.VideoAnalysis -> "视频分析"
        HistoryRecordType.LiveMonitor -> "实时监控"
    }
}
