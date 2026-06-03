package com.example.watcher.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.ceil

enum class VideoTaskCategory(val value: String) {
    LongHorizonSummary("long_horizon_summary"),
    ContinuousWatch("continuous_watch"),
    ShortBurstDense("short_burst_dense");

    companion object {
        fun fromValue(value: String?): VideoTaskCategory? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

enum class RecordingScenario(
    val value: String,
    val label: String,
    val outputFocus: String
) {
    General("general", "通用记录", "概览、主题脉络、关键点、时间线、重要片段、待跟进事项"),
    ClassLecture("class_lecture", "课堂/讲座", "知识大纲、重点难点、例子/演示、复习清单、自测问题"),
    Meeting("meeting", "会议", "议题、结论、决策、行动项、责任人、未决问题"),
    Training("training", "培训", "流程步骤、操作规范、注意事项、常见错误、练习建议"),
    Interview("interview", "访谈/研讨", "观点归纳、问题与回答、共识/分歧、引用摘录");

    companion object {
        fun fromValue(value: String?): RecordingScenario {
            return entries.firstOrNull { it.value == value } ?: General
        }
    }
}

@Entity(tableName = "video_process_tasks")
data class VideoProcessTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val templateId: String? = null,
    val templateLabel: String? = null,
    val taskCategory: String? = null,
    val strategyReason: String = "",
    val title: String,
    val userInput: String,
    val userRequirement: String,
    val sceneContext: String,
    @ColumnInfo(name = "analysisPrompt")
    val segmentAnalysisPrompt: String,
    val finalSummaryPrompt: String,
    val recordingScenario: String = RecordingScenario.General.value,
    val speechInputEnabled: Boolean = false,
    val plannedDurationSeconds: Int,
    val plannedSamplingFps: Int,
    val plannedSegmentDurationSeconds: Int,
    val captureIntervalSeconds: Int,
    val plannedSegmentCount: Int,
    val autoStartStreamingOutput: Boolean = false,
    val finalSummaryEnabled: Boolean = true,
    val confirmationNotes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
    val runCount: Int = 0
)

@Entity(
    tableName = "video_process_runs",
    indices = [Index("taskId")]
)
data class VideoProcessRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val templateId: String? = null,
    val templateLabel: String? = null,
    val taskTitle: String = "",
    val taskRequirement: String = "",
    val recordingScenario: String = RecordingScenario.General.value,
    val speechInputEnabled: Boolean = false,
    val status: VideoRunStatus = VideoRunStatus.Idle,
    val recordingStartedAt: Long? = null,
    val recordingEndedAt: Long? = null,
    val totalDurationSeconds: Int = 0,
    val segmentDurationSeconds: Int = 0,
    val captureIntervalSeconds: Int = 0,
    val segmentCount: Int = 0,
    val finalSummary: String = "",
    val finalConclusion: String = "",
    val rawModelSummary: String = "",
    val structuredNoteJson: String = "",
    val markdownNote: String = "",
    val audioEnhancementInfo: String = "",
    val mergedVideoPath: String? = null,
    val fullMediaPath: String? = null,
    val fullMediaDurationMs: Long = 0L,
    val fullMediaHasAudio: Boolean = false,
    val fullMediaVideoSource: String = "",
    val errorMessage: String? = null,
    val degradedReason: String? = null,
    val continuousAudioPath: String? = null,
    val continuousAudioDurationMs: Long = 0L,
    val continuousAudioStartedAt: Long = 0L,
    val outlineMarkdown: String = "",
    val outlineGeneratedAt: Long = 0L,
    val reportVersion: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val mergedSegmentCountActual: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val segmentsMissingMergedAnalysisAsset: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val audioOutlineAvailable: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val videoRefinementApplied: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val videoRefinementInputMode: String = "",
    @ColumnInfo(defaultValue = "")
    val reportPipelineStagesJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class VideoRunStatus {
    Idle,
    Planning,
    AwaitingConfirmation,
    Recording,
    Uploading,
    Preprocessing,
    Analyzing,
    Summarizing,
    Completed,
    CompletedDegraded,
    Failed,
    Cancelled
}

@Entity(
    tableName = "video_segment_runs",
    foreignKeys = [
        ForeignKey(
            entity = VideoProcessRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("runId")]
)
data class VideoSegmentRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long,
    val segmentIndex: Int,
    val status: VideoRunStatus = VideoRunStatus.Idle,
    val durationSeconds: Int,
    val durationMs: Long = durationSeconds * 1_000L,
    val localFilePath: String? = null,
    val mediaStartMs: Long? = null,
    val mediaEndMs: Long? = null,
    val wallClockStartMs: Long? = null,
    val wallClockEndMs: Long? = null,
    val interrupted: Boolean = false,
    val arkFileId: String? = null,
    val summary: String = "",
    val conclusion: String = "",
    val evidenceJson: String = "",
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = VideoProcessRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("runId"), Index("segmentRunId")]
)
data class TimelineEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long,
    val segmentRunId: Long? = null,
    val timestampSeconds: Int,
    val title: String,
    val detail: String,
    val confidence: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "video_speech_transcripts",
    foreignKeys = [
        ForeignKey(
            entity = VideoProcessRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("runId"),
        Index("segmentIndex"),
        Index("timestamp"),
        Index(value = ["runId", "timestamp", "text"], unique = true)
    ]
)
data class VideoSpeechTranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long,
    val segmentIndex: Int? = null,
    val timestamp: Long,
    val displayTimestamp: String,
    val text: String,
    val isFinal: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

enum class VideoAudioAssetType(val value: String) {
    MasterAudio("masterAudio"),
    SegmentAudio("segmentAudio");

    companion object {
        fun fromValue(value: String?): VideoAudioAssetType {
            return entries.firstOrNull { it.value == value } ?: SegmentAudio
        }
    }
}

@Entity(
    tableName = "video_audio_assets",
    foreignKeys = [
        ForeignKey(
            entity = VideoProcessRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VideoSegmentRun::class,
            parentColumns = ["id"],
            childColumns = ["segmentRunId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("runId"),
        Index("segmentRunId"),
        Index(value = ["runId", "assetType", "segmentIndex"], unique = true)
    ]
)
data class VideoAudioAssetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long,
    val segmentRunId: Long? = null,
    val segmentIndex: Int? = null,
    val assetType: String,
    val localFilePath: String,
    val durationMs: Long = 0L,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val codecMime: String = "",
    val sourceVideoPath: String? = null,
    val diagnosticsJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class VideoRemoteAssetKind(val value: String) {
    SegmentVideo("segment_video"),
    MergedChunkVideo("merged_chunk_video"),
    MasterVideo("master_video"),
    FullMediaVideo("full_media_video"),
    SegmentAudio("segment_audio"),
    MasterAudio("master_audio"),
    MergedSegmentVideo("merged_segment_video");

    companion object {
        fun fromValue(value: String?): VideoRemoteAssetKind {
            return entries.firstOrNull { it.value == value } ?: SegmentVideo
        }
    }
}

@Entity(
    tableName = "video_remote_file_bindings",
    foreignKeys = [
        ForeignKey(
            entity = VideoProcessRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VideoSegmentRun::class,
            parentColumns = ["id"],
            childColumns = ["segmentRunId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("runId"),
        Index("segmentRunId"),
        Index("arkFileId"),
        Index(value = ["runId", "assetKind", "localPath"], unique = true)
    ]
)
data class VideoRemoteFileBindingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: Long,
    val segmentRunId: Long? = null,
    val assetKind: String,
    val localPath: String,
    val lengthBytes: Long = 0L,
    val lastModified: Long = 0L,
    val mediaType: String = "video/mp4",
    val arkFileId: String? = null,
    val status: String = "local",
    val uploadAttemptCount: Int = 0,
    val lastCheckedAt: Long = 0L,
    val diagnosticsJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class VideoProcessTaskDraft(
    val taskId: Long? = null,
    val templateId: String? = null,
    val templateLabel: String? = null,
    val taskCategory: String? = null,
    val strategyReason: String = "",
    val title: String = "",
    val userInput: String = "",
    val userRequirement: String = "",
    val sceneContext: String = "",
    val segmentAnalysisPrompt: String = "",
    val finalSummaryPrompt: String = "",
    val recordingScenario: String = RecordingScenario.General.value,
    val speechInputEnabled: Boolean = false,
    val plannedDurationSeconds: Int = DEFAULT_DURATION_SECONDS,
    val plannedSamplingFps: Int = DEFAULT_SAMPLING_FPS,
    val plannedSegmentDurationSeconds: Int = DEFAULT_SEGMENT_DURATION_SECONDS,
    val captureIntervalSeconds: Int = DEFAULT_CAPTURE_INTERVAL_SECONDS,
    val plannedSegmentCount: Int = 1,
    val autoStartStreamingOutput: Boolean = false,
    val finalSummaryEnabled: Boolean = true,
    val confirmationNotes: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun normalized(): VideoProcessTaskDraft {
        val safeRequirement = userRequirement.ifBlank { userInput.ifBlank { DEFAULT_REQUIREMENT } }
        val safeTitle = title.ifBlank { safeRequirement.take(MAX_TITLE_LENGTH) }
            .take(MAX_TITLE_LENGTH)
        val safeDuration = plannedDurationSeconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
        val safeSamplingFps = plannedSamplingFps.coerceIn(MIN_SAMPLING_FPS, MAX_SAMPLING_FPS)
        val safeSegmentDuration = plannedSegmentDurationSeconds
            .coerceIn(MIN_SEGMENT_DURATION_SECONDS, MAX_SEGMENT_DURATION_SECONDS)
            .coerceAtMost(safeDuration)
        val safeCaptureInterval = safeSegmentDuration
        val safeSegmentCount = ceil(safeDuration / safeCaptureInterval.toDouble()).toInt()
            .coerceAtLeast(1)
        val safeSceneContext = sceneContext.ifBlank { DEFAULT_SCENE_CONTEXT }
        val safeSegmentPrompt = segmentAnalysisPrompt.ifBlank {
            buildFallbackSegmentAnalysisPrompt(
                userRequirement = safeRequirement,
                sceneContext = safeSceneContext
            )
        }
        val safeFinalSummaryPrompt = finalSummaryPrompt.ifBlank {
            buildFallbackFinalSummaryPrompt(
                userRequirement = safeRequirement,
                sceneContext = safeSceneContext
            )
        }
        val safeCategory = VideoTaskCategory.fromValue(taskCategory)?.value
        val safeRecordingScenario = RecordingScenario.fromValue(recordingScenario).value

        return copy(
            taskCategory = safeCategory,
            recordingScenario = safeRecordingScenario,
            strategyReason = strategyReason.trim(),
            title = safeTitle,
            userRequirement = safeRequirement,
            sceneContext = safeSceneContext,
            segmentAnalysisPrompt = safeSegmentPrompt,
            finalSummaryPrompt = safeFinalSummaryPrompt,
            speechInputEnabled = false,
            plannedDurationSeconds = safeDuration,
            plannedSamplingFps = safeSamplingFps,
            plannedSegmentDurationSeconds = safeSegmentDuration,
            captureIntervalSeconds = safeCaptureInterval,
            plannedSegmentCount = safeSegmentCount,
            confirmationNotes = confirmationNotes.trim()
        )
    }

    fun toEntity(existing: VideoProcessTask? = null): VideoProcessTask {
        val normalized = normalized()
        val now = System.currentTimeMillis()
        return VideoProcessTask(
            id = normalized.taskId ?: 0L,
            templateId = normalized.templateId,
            templateLabel = normalized.templateLabel,
            taskCategory = normalized.taskCategory,
            strategyReason = normalized.strategyReason,
            title = normalized.title,
            userInput = normalized.userInput,
            userRequirement = normalized.userRequirement,
            sceneContext = normalized.sceneContext,
            segmentAnalysisPrompt = normalized.segmentAnalysisPrompt,
            finalSummaryPrompt = normalized.finalSummaryPrompt,
            recordingScenario = normalized.recordingScenario,
            speechInputEnabled = normalized.speechInputEnabled,
            plannedDurationSeconds = normalized.plannedDurationSeconds,
            plannedSamplingFps = normalized.plannedSamplingFps,
            plannedSegmentDurationSeconds = normalized.plannedSegmentDurationSeconds,
            captureIntervalSeconds = normalized.captureIntervalSeconds,
            plannedSegmentCount = normalized.plannedSegmentCount,
            autoStartStreamingOutput = normalized.autoStartStreamingOutput,
            finalSummaryEnabled = normalized.finalSummaryEnabled,
            confirmationNotes = normalized.confirmationNotes,
            createdAt = existing?.createdAt ?: normalized.createdAt,
            updatedAt = now,
            lastUsedAt = existing?.lastUsedAt,
            runCount = existing?.runCount ?: 0
        )
    }

    companion object {
        const val DEFAULT_DURATION_SECONDS = 3_600
        const val DEFAULT_SEGMENT_DURATION_SECONDS = 60
        const val DEFAULT_CAPTURE_INTERVAL_SECONDS = 60
        const val DEFAULT_SAMPLING_FPS = 1
        const val MIN_DURATION_SECONDS = 5
        const val MAX_DURATION_SECONDS = 21_600
        const val MIN_SEGMENT_DURATION_SECONDS = 2
        const val MAX_SEGMENT_DURATION_SECONDS = 300
        const val MIN_CAPTURE_INTERVAL_SECONDS = 2
        const val MAX_CAPTURE_INTERVAL_SECONDS = 3_600
        const val MIN_SAMPLING_FPS = 1
        const val MAX_SAMPLING_FPS = 8
        const val MAX_TITLE_LENGTH = 48
        private const val DEFAULT_REQUIREMENT = "请总结当前视频中的关键信息。"
        private const val DEFAULT_SCENE_CONTEXT = "当前画面可作为本次视频分析任务的场景参考。"

        fun fromEntity(task: VideoProcessTask): VideoProcessTaskDraft {
            return VideoProcessTaskDraft(
                taskId = task.id,
                templateId = task.templateId,
                templateLabel = task.templateLabel,
                taskCategory = task.taskCategory,
                strategyReason = task.strategyReason,
                title = task.title,
                userInput = task.userInput,
                userRequirement = task.userRequirement,
                sceneContext = task.sceneContext,
                segmentAnalysisPrompt = task.segmentAnalysisPrompt,
                finalSummaryPrompt = task.finalSummaryPrompt,
                recordingScenario = task.recordingScenario,
                speechInputEnabled = task.speechInputEnabled,
                plannedDurationSeconds = task.plannedDurationSeconds,
                plannedSamplingFps = task.plannedSamplingFps,
                plannedSegmentDurationSeconds = task.plannedSegmentDurationSeconds,
                captureIntervalSeconds = task.captureIntervalSeconds,
                plannedSegmentCount = task.plannedSegmentCount,
                autoStartStreamingOutput = task.autoStartStreamingOutput,
                finalSummaryEnabled = task.finalSummaryEnabled,
                confirmationNotes = task.confirmationNotes,
                createdAt = task.createdAt
            ).normalized()
        }

        fun buildFallbackSegmentAnalysisPrompt(
            userRequirement: String,
            sceneContext: String
        ): String = listOf(userRequirement, sceneContext).joinToString(separator = " | ")

        fun buildFallbackFinalSummaryPrompt(
            userRequirement: String,
            sceneContext: String
        ): String = listOf(userRequirement, sceneContext).joinToString(separator = " | ")
    }
}

data class VideoTaskPlan(
    val templateId: String? = null,
    val templateLabel: String? = null,
    val taskCategory: String? = null,
    val strategyReason: String = "",
    val title: String,
    val userRequirement: String,
    val sceneContext: String,
    val recordingDurationSeconds: Int,
    val samplingFps: Int,
    val segmentDurationSeconds: Int,
    val captureIntervalSeconds: Int,
    val segmentCount: Int,
    val segmentAnalysisPrompt: String,
    val finalSummaryPrompt: String,
    val recordingScenario: String = RecordingScenario.General.value,
    val speechInputEnabled: Boolean = false,
    val autoStartStreamingOutput: Boolean = false,
    val finalSummaryEnabled: Boolean = true,
    val confirmationNotes: String = ""
) {
    fun toDraft(
        userInput: String,
        taskId: Long? = null,
        createdAt: Long = System.currentTimeMillis()
    ): VideoProcessTaskDraft {
        return VideoProcessTaskDraft(
            taskId = taskId,
            templateId = templateId,
            templateLabel = templateLabel,
            taskCategory = taskCategory,
            strategyReason = strategyReason,
            title = title,
            userInput = userInput,
            userRequirement = userRequirement,
            sceneContext = sceneContext,
            segmentAnalysisPrompt = segmentAnalysisPrompt,
            finalSummaryPrompt = finalSummaryPrompt,
            recordingScenario = recordingScenario,
            speechInputEnabled = speechInputEnabled,
            plannedDurationSeconds = recordingDurationSeconds,
            plannedSamplingFps = samplingFps,
            plannedSegmentDurationSeconds = segmentDurationSeconds,
            captureIntervalSeconds = captureIntervalSeconds,
            plannedSegmentCount = segmentCount,
            autoStartStreamingOutput = autoStartStreamingOutput,
            finalSummaryEnabled = finalSummaryEnabled,
            confirmationNotes = confirmationNotes,
            createdAt = createdAt
        ).normalized()
    }
}

data class VideoTimelineEvent(
    val timestampSeconds: Int,
    val title: String,
    val detail: String,
    val confidence: Float? = null
)

data class VideoAnalysisResult(
    val summary: String,
    val conclusion: String,
    val timelineEvents: List<VideoTimelineEvent>,
    val rawResponse: String = "",
    val structuredNoteJson: String = "",
    val markdownNote: String = "",
    val evidenceJson: String = ""
)

data class VideoSpeechTranscript(
    val timestamp: Long,
    val text: String
)

data class VideoSegmentFeedback(
    val segmentIndex: Int,
    val summary: String,
    val conclusion: String,
    val status: VideoRunStatus = VideoRunStatus.Completed
)

data class VideoProcessingStatus(
    val stage: VideoRunStatus = VideoRunStatus.Idle,
    val activeTask: VideoProcessTaskDraft? = null,
    val activeRunId: Long? = null,
    val templateLabel: String? = null,
    val currentSegmentIndex: Int = 0,
    val segmentCount: Int = 0,
    val segmentDurationSeconds: Int = 0,
    val captureIntervalSeconds: Int = 0,
    val message: String = "",
    val finalSummary: String = "",
    val finalConclusion: String = "",
    val timelineEvents: List<VideoTimelineEvent> = emptyList(),
    val streamingBuffer: String = "",
    val streamingEnabled: Boolean = false,
    val isStreamingActive: Boolean = false,
    val isRecordingActive: Boolean = false,
    val isAnalysisActive: Boolean = false,
    val recordingSegmentIndex: Int = 0,
    val activeStreamingSegmentIndex: Int = 0,
    val recordedSegmentCount: Int = 0,
    val analyzedSegmentCount: Int = 0,
    val pendingSegmentCount: Int = 0,
    val recordedDurationSeconds: Int = 0,
    val remainingDurationSeconds: Int = 0,
    val nextCaptureInSeconds: Int? = null,
    val stopRequested: Boolean = false,
    val segmentFeedbacks: List<VideoSegmentFeedback> = emptyList(),
    val speechInputEnabled: Boolean = false,
    val isSpeechActive: Boolean = false,
    val isSpeechListening: Boolean = false,
    val speechErrorMessage: String? = null,
    val recentSpeech: List<SpeechTranscriptEntry> = emptyList(),
    val errorMessage: String? = null,
    val isBusy: Boolean = false,
    val isTaskSaving: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val currentSegmentHasAudio: Boolean = false,
    val segmentAudioResults: List<Boolean> = emptyList()
)
