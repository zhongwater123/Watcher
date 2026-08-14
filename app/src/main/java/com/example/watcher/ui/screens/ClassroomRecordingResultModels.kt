package com.example.watcher.ui.screens

import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.ClassroomKnowledgeFrameRef
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeProcessingStatus
import com.example.watcher.data.model.ClassroomKnowledgeTreeProgress
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.persistedClassroomKnowledgeFrameRefs
import com.example.watcher.data.model.persistedClassroomKnowledgeTree

internal enum class ClassroomRecordingPhase {
    NotStarted,
    Recording,
    Completed
}

internal data class ClassroomRecordingResultUiModel(
    val runId: Long?,
    val title: String,
    val subtitle: String,
    val summary: String,
    val noteText: String,
    val structuredNoteJson: String,
    val copyText: String,
    val durationLabel: String,
    val segmentLabel: String,
    val playbackPath: String?,
    val inputSource: String,
    val hasAudio: Boolean,
    val statusLabel: String,
    val updatedAtLabel: String,
    val degradedReason: String?,
    val hasMarkdown: Boolean,
    val hasAudioOutline: Boolean,
    val hasFinalNote: Boolean,
    val knowledgeTree: ClassroomKnowledgeTree?,
    val changedKnowledgeNodeIds: List<String>,
    val newKnowledgeNodeIds: List<String>,
    val knowledgeTreeStatus: String,
    val knowledgeTreeProgress: ClassroomKnowledgeTreeProgress,
    val knowledgeFrameRefs: List<ClassroomKnowledgeFrameRef>
)

internal data class ClassroomHistoryItemUiModel(
    val runId: Long,
    val title: String,
    val statusLabel: String,
    val updatedAt: Long,
    val summary: String,
    val rhythmLabel: String
)

internal fun resolveClassroomRecordingPhase(status: VideoProcessingStatus): ClassroomRecordingPhase {
    return when {
        status.stage in terminalVideoStages -> ClassroomRecordingPhase.Completed
        status.stopRequested -> ClassroomRecordingPhase.Completed
        status.stage in postCaptureVideoStages -> ClassroomRecordingPhase.Completed
        status.isBusy -> ClassroomRecordingPhase.Recording
        else -> ClassroomRecordingPhase.NotStarted
    }
}

internal fun resolveClassroomRecordingDisplayPhase(
    status: VideoProcessingStatus,
    resultConfirmed: Boolean
): ClassroomRecordingPhase {
    val resolvedPhase = resolveClassroomRecordingPhase(status)
    return when {
        resolvedPhase == ClassroomRecordingPhase.Completed &&
            !resultConfirmed &&
            status.activeTask != null -> ClassroomRecordingPhase.Recording
        else -> resolvedPhase
    }
}

internal fun buildClassroomHistoryItems(
    recentRuns: List<VideoProcessRun>,
    limit: Int = 20
): List<ClassroomHistoryItemUiModel> {
    return recentRuns
        .asSequence()
        .filter { it.recordingScenario == RecordingScenario.ClassLecture.value }
        .filter { it.status in terminalVideoStages }
        .sortedByDescending { it.updatedAt }
        .take(limit)
        .map { run ->
            ClassroomHistoryItemUiModel(
                runId = run.id,
                title = run.taskTitle.ifBlank { "Classroom recording" },
                statusLabel = videoStageLabel(run.status),
                updatedAt = run.updatedAt,
                summary = run.finalSummary.ifBlank { run.errorMessage ?: "No summary yet" },
                rhythmLabel = buildClassroomRhythmLabel(run)
            )
        }
        .toList()
}

internal fun buildClassroomRecordingResultUiModel(
    status: VideoProcessingStatus,
    recentRuns: List<VideoProcessRun>,
    selectedRunId: Long?
): ClassroomRecordingResultUiModel {
    val statusRunId = status.completedRunId ?: status.activeRunId
    val runId = statusRunId ?: selectedRunId.takeUnless { status.stage in terminalVideoStages }
    val run = recentRuns.firstOrNull { it.id == runId }
    val markdownNote = status.markdownNote.ifBlank { run?.markdownNote.orEmpty() }
    val structuredNoteJson = status.structuredNoteJson.ifBlank { run?.structuredNoteJson.orEmpty() }
    val rawSummary = status.rawModelSummary.ifBlank { run?.rawModelSummary.orEmpty() }
    val finalSummary = status.finalSummary.ifBlank { run?.finalSummary.orEmpty() }
    val noteText = markdownNote
        .ifBlank { status.streamingBuffer }
        .ifBlank { rawSummary }
        .ifBlank { finalSummary }
        .ifBlank { structuredNoteJson }

    val title = run?.taskTitle?.takeIf(String::isNotBlank)
        ?: status.activeTask?.title?.takeIf(String::isNotBlank)
        ?: "Classroom recording"
    val durationSeconds = status.recordedDurationSeconds
        .takeIf { it > 0 }
        ?: run?.totalDurationSeconds?.takeIf { it > 0 }
        ?: run?.fullMediaDurationMs?.takeIf { it > 0L }?.let { (it / 1_000L).toInt() }
        ?: 0
    val segmentCount = status.segmentCount
        .takeIf { it > 0 }
        ?: run?.segmentCount?.takeIf { it > 0 }
        ?: 0
    val analyzedSegmentCount = status.analyzedSegmentCount
        .takeIf { it > 0 }
        ?: segmentCount
    val statusPlaybackPath = status.playbackPath
        ?.takeIf(String::isNotBlank)
        ?.takeIf { statusRunId != null && runId == statusRunId }
    val resolvedRunStatus = run?.status ?: status.stage
    val hasFinalNote = markdownNote.isNotBlank() && resolvedRunStatus in finalNoteVideoStages
    val hasAudioOutline = run?.audioOutlineAvailable == true ||
        run?.outlineMarkdown?.isNotBlank() == true ||
        messageIndicatesAudioOutline(status.message) ||
        (markdownNote.isNotBlank() && !hasFinalNote)
    val statusBelongsToResultRun = statusRunId != null && runId == statusRunId
    val statusKnowledgeTree = status.realtimeKnowledgeTree
        ?.takeIf { statusBelongsToResultRun || run == null }
        ?.takeIf { it.nodes.isNotEmpty() }
    val persistedKnowledgeTree = run?.persistedClassroomKnowledgeTree()
    val knowledgeTree = statusKnowledgeTree ?: persistedKnowledgeTree
    val knowledgeTreeStatus = when {
        statusKnowledgeTree != null -> status.realtimeKnowledgeTreeStatus
            .ifBlank { ClassroomKnowledgeTreeProcessingStatus.Completed.value }
        persistedKnowledgeTree != null -> run?.classroomKnowledgeTreeStatus
            .orEmpty()
            .ifBlank { ClassroomKnowledgeTreeProcessingStatus.Completed.value }
        else -> status.realtimeKnowledgeTreeStatus
    }
    val knowledgeFrameRefs = if (statusKnowledgeTree != null) {
        status.realtimeKnowledgeFrameRefs
    } else {
        run?.persistedClassroomKnowledgeFrameRefs().orEmpty()
    }

    return ClassroomRecordingResultUiModel(
        runId = runId,
        title = title,
        subtitle = if (runId != null) "Run #$runId" else "Current classroom recording",
        summary = finalSummary,
        noteText = noteText,
        structuredNoteJson = structuredNoteJson,
        copyText = noteText.ifBlank { structuredNoteJson }.ifBlank { finalSummary },
        durationLabel = if (durationSeconds > 0) formatClassroomDuration(durationSeconds) else "Duration pending",
        segmentLabel = if (segmentCount > 0) "$analyzedSegmentCount / $segmentCount segments" else "Segments pending",
        playbackPath = run?.fullMediaPath?.takeIf(String::isNotBlank)
            ?: run?.mergedVideoPath?.takeIf(String::isNotBlank)
            ?: statusPlaybackPath,
        inputSource = run?.fullMediaVideoSource?.takeIf(String::isNotBlank) ?: "live_camera",
        hasAudio = run?.fullMediaHasAudio == true,
        statusLabel = videoStageLabel(run?.status ?: status.stage),
        updatedAtLabel = run?.updatedAt?.let(::formatDateTime) ?: "-",
        degradedReason = status.degradedReason ?: run?.degradedReason,
        hasMarkdown = markdownNote.isNotBlank(),
        hasAudioOutline = hasAudioOutline,
        hasFinalNote = hasFinalNote,
        knowledgeTree = knowledgeTree,
        changedKnowledgeNodeIds = if (statusKnowledgeTree != null) status.changedKnowledgeNodeIds else emptyList(),
        newKnowledgeNodeIds = if (statusKnowledgeTree != null) status.newKnowledgeNodeIds else emptyList(),
        knowledgeTreeStatus = knowledgeTreeStatus,
        knowledgeTreeProgress = if (statusKnowledgeTree != null) {
            status.realtimeKnowledgeTreeProgress
        } else {
            ClassroomKnowledgeTreeProgress()
        },
        knowledgeFrameRefs = knowledgeFrameRefs
    )
}

private val terminalVideoStages = setOf(
    VideoRunStatus.Completed,
    VideoRunStatus.CompletedDegraded,
    VideoRunStatus.Failed,
    VideoRunStatus.Cancelled
)

private val finalNoteVideoStages = setOf(
    VideoRunStatus.Completed,
    VideoRunStatus.CompletedDegraded
)

private val postCaptureVideoStages = setOf(
    VideoRunStatus.Uploading,
    VideoRunStatus.Preprocessing,
    VideoRunStatus.Analyzing,
    VideoRunStatus.Summarizing
)

private fun buildClassroomRhythmLabel(run: VideoProcessRun): String {
    val duration = run.totalDurationSeconds.takeIf { it > 0 }
        ?: run.fullMediaDurationMs.takeIf { it > 0L }?.let { (it / 1_000L).toInt() }
        ?: 0
    val durationLabel = if (duration > 0) formatClassroomDuration(duration) else "Duration pending"
    val segmentLabel = if (run.segmentCount > 0) "${run.segmentCount} segments" else "Segments pending"
    return "$durationLabel · $segmentLabel"
}

internal enum class ClassroomNoteProgressState {
    Pending,
    Active,
    Done
}

internal data class ClassroomNoteProgressStep(
    val label: String,
    val detail: String,
    val state: ClassroomNoteProgressState
)

internal fun buildClassroomNoteProgressSteps(
    status: VideoProcessingStatus,
    result: ClassroomRecordingResultUiModel
): List<ClassroomNoteProgressStep> {
    val draftAvailable = status.streamingBuffer.isNotBlank() ||
        result.noteText.isNotBlank() ||
        result.hasAudioOutline ||
        result.hasFinalNote
    val audioAvailable = result.hasAudioOutline || result.hasFinalNote
    val finalAvailable = result.hasFinalNote
    return listOf(
        ClassroomNoteProgressStep(
            label = "初稿状态",
            detail = when {
                draftAvailable -> "已生成课堂草稿"
                status.stage == VideoRunStatus.Summarizing -> "正在整理实时转写"
                else -> "等待录制结束"
            },
            state = when {
                draftAvailable -> ClassroomNoteProgressState.Done
                status.stage == VideoRunStatus.Summarizing -> ClassroomNoteProgressState.Active
                else -> ClassroomNoteProgressState.Pending
            }
        ),
        ClassroomNoteProgressStep(
            label = "音频笔记",
            detail = when {
                audioAvailable -> "音频课堂大纲已生成"
                draftAvailable || status.stage == VideoRunStatus.Summarizing -> "正在生成音频课堂大纲"
                else -> "等待初稿"
            },
            state = when {
                audioAvailable -> ClassroomNoteProgressState.Done
                draftAvailable || status.stage == VideoRunStatus.Summarizing -> ClassroomNoteProgressState.Active
                else -> ClassroomNoteProgressState.Pending
            }
        ),
        ClassroomNoteProgressStep(
            label = "最终笔记",
            detail = when {
                finalAvailable -> "最终课堂笔记已完成"
                audioAvailable -> "正在结合视频证据补强"
                status.stage == VideoRunStatus.Summarizing -> "等待音频大纲"
                else -> "等待生成"
            },
            state = when {
                finalAvailable -> ClassroomNoteProgressState.Done
                audioAvailable || status.stage == VideoRunStatus.Summarizing -> ClassroomNoteProgressState.Active
                else -> ClassroomNoteProgressState.Pending
            }
        )
    )
}

private fun messageIndicatesAudioOutline(message: String): Boolean {
    return message.contains("音频大纲已生成") || message.contains("音频课堂大纲已生成")
}

private fun formatClassroomDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
        else -> "%d:%02d".format(minutes, seconds)
    }
}
