package com.example.watcher.ui.screens

import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.ClassroomRecordingDefaults
import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeNodeStatus
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeProcessingStatus
import com.example.watcher.data.model.toPersistedClassroomKnowledgeTreeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomRecordingResultModelsTest {
    @Test
    fun classroomPhaseMapsIdleBusyAndTerminalStates() {
        assertEquals(
            ClassroomRecordingPhase.NotStarted,
            resolveClassroomRecordingPhase(VideoProcessingStatus())
        )
        assertEquals(
            ClassroomRecordingPhase.Recording,
            resolveClassroomRecordingPhase(
                VideoProcessingStatus(stage = VideoRunStatus.Recording, isBusy = true)
            )
        )
        assertEquals(
            ClassroomRecordingPhase.Completed,
            resolveClassroomRecordingPhase(
                VideoProcessingStatus(stage = VideoRunStatus.Completed, completedRunId = 9)
            )
        )
    }

    @Test
    fun classroomPhaseMovesToCompletedSurfaceAfterStopOrPostCaptureProcessing() {
        assertEquals(
            ClassroomRecordingPhase.Completed,
            resolveClassroomRecordingPhase(
                VideoProcessingStatus(stage = VideoRunStatus.Recording, isBusy = true, stopRequested = true)
            )
        )
        assertEquals(
            ClassroomRecordingPhase.Completed,
            resolveClassroomRecordingPhase(
                VideoProcessingStatus(stage = VideoRunStatus.Analyzing, isBusy = true, recordedSegmentCount = 2)
            )
        )
        assertEquals(
            ClassroomRecordingPhase.Completed,
            resolveClassroomRecordingPhase(
                VideoProcessingStatus(stage = VideoRunStatus.Summarizing, isBusy = true, recordedSegmentCount = 2)
            )
        )
    }

    @Test
    fun classroomDisplayPhaseKeepsCurrentStoppedRecordingUntilUserConfirmsResult() {
        val draft = ClassroomRecordingDefaults.buildDraft("Java 多态", 600)
        val status = VideoProcessingStatus(
            stage = VideoRunStatus.Summarizing,
            activeTask = draft,
            activeRunId = 42,
            isBusy = true,
            stopRequested = true,
            streamingBuffer = "# 临时草稿"
        )

        assertEquals(ClassroomRecordingPhase.Completed, resolveClassroomRecordingPhase(status))
        assertEquals(
            ClassroomRecordingPhase.Recording,
            resolveClassroomRecordingDisplayPhase(status, resultConfirmed = false)
        )
        assertEquals(
            ClassroomRecordingPhase.Completed,
            resolveClassroomRecordingDisplayPhase(status, resultConfirmed = true)
        )
    }

    @Test
    fun classroomDisplayPhaseOpensHistoricalCompletedRunDirectly() {
        val status = VideoProcessingStatus(
            stage = VideoRunStatus.Completed,
            activeTask = null,
            activeRunId = 42,
            completedRunId = 42,
            isBusy = false
        )

        assertEquals(
            ClassroomRecordingPhase.Completed,
            resolveClassroomRecordingDisplayPhase(status, resultConfirmed = false)
        )
    }

    @Test
    fun classroomHistoryOnlyIncludesLatestClassLectureRuns() {
        val classroomOld = VideoProcessRun(
            id = 1,
            taskId = 1,
            recordingScenario = RecordingScenario.ClassLecture.value,
            status = VideoRunStatus.Completed,
            updatedAt = 100
        )
        val general = VideoProcessRun(
            id = 2,
            taskId = 1,
            recordingScenario = RecordingScenario.General.value,
            status = VideoRunStatus.Completed,
            updatedAt = 500
        )
        val classroomNew = VideoProcessRun(
            id = 3,
            taskId = 1,
            recordingScenario = RecordingScenario.ClassLecture.value,
            status = VideoRunStatus.CompletedDegraded,
            updatedAt = 900
        )
        val classroomInProgress = VideoProcessRun(
            id = 4,
            taskId = 1,
            recordingScenario = RecordingScenario.ClassLecture.value,
            status = VideoRunStatus.Recording,
            updatedAt = 1_000
        )

        val history = buildClassroomHistoryItems(
            recentRuns = listOf(classroomOld, general, classroomNew, classroomInProgress),
            limit = 1
        )

        assertEquals(listOf(3L), history.map { it.runId })
    }

    @Test
    fun classroomHistoryIncludesStoppedAndCancelledClassLectureRuns() {
        val manualStop = VideoProcessRun(
            id = 10,
            taskId = 1,
            recordingScenario = RecordingScenario.ClassLecture.value,
            status = VideoRunStatus.CompletedDegraded,
            updatedAt = 1_000
        )
        val cancelled = VideoProcessRun(
            id = 11,
            taskId = 1,
            recordingScenario = RecordingScenario.ClassLecture.value,
            status = VideoRunStatus.Cancelled,
            updatedAt = 900
        )

        val history = buildClassroomHistoryItems(
            recentRuns = listOf(cancelled, manualStop),
            limit = 20
        )

        assertEquals(listOf(10L, 11L), history.map { it.runId })
    }

    @Test
    fun completedResultPrefersFullMediaForPlayback() {
        val status = VideoProcessingStatus(completedRunId = 42)
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            mergedVideoPath = "/tmp/merged.mp4",
            fullMediaPath = "/tmp/full.mp4",
            fullMediaVideoSource = "test_video",
            degradedReason = "Manual stop; generated a partial summary from recorded segments."
        )

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(run),
            selectedRunId = null
        )

        assertEquals("/tmp/full.mp4", result.playbackPath)
        assertEquals("test_video", result.inputSource)
        assertEquals("Manual stop; generated a partial summary from recorded segments.", result.degradedReason)
    }

    @Test
    fun completedResultPrefersPersistedFullMediaOverStatusPreviewPlayback() {
        val status = VideoProcessingStatus(
            completedRunId = 42,
            playbackPath = "/tmp/preview-or-segment.mp4"
        )
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            mergedVideoPath = "/tmp/merged.mp4",
            fullMediaPath = "/tmp/full-master.mp4"
        )

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(run),
            selectedRunId = null
        )

        assertEquals("/tmp/full-master.mp4", result.playbackPath)
    }

    @Test
    fun completedResultDoesNotFallBackToSelectedHistoryWithoutCurrentRun() {
        val status = VideoProcessingStatus(stage = VideoRunStatus.Failed)
        val historicalRun = VideoProcessRun(
            id = 99,
            taskId = 7,
            taskTitle = "Historical classroom",
            markdownNote = "old note",
            fullMediaPath = "/tmp/old.mp4"
        )

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(historicalRun),
            selectedRunId = 99
        )

        assertNull(result.runId)
        assertEquals("Classroom recording", result.title)
        assertEquals("", result.noteText)
        assertNull(result.playbackPath)
    }

    @Test
    fun resultContentPrefersStatusMarkdownOverOtherSources() {
        val status = VideoProcessingStatus(
            completedRunId = 42,
            markdownNote = "# Complete class note",
            rawModelSummary = "raw",
            finalSummary = "summary"
        )
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            markdownNote = "# Historical note",
            rawModelSummary = "historical raw",
            finalSummary = "historical summary"
        )

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(run),
            selectedRunId = null
        )

        assertEquals("# Complete class note", result.noteText)
        assertEquals("# Complete class note", result.copyText)
        assertEquals(42L, result.runId)
        assertTrue(result.hasMarkdown)
    }

    @Test
    fun resultContentUsesStreamingBufferAsProgressiveDraftBeforeFinalNote() {
        val status = VideoProcessingStatus(
            activeRunId = 42,
            stage = VideoRunStatus.Summarizing,
            streamingBuffer = "# 临时课堂草稿\n\n## 课堂要点\n- 多态调用规则",
            rawModelSummary = "raw",
            finalSummary = "summary"
        )

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = emptyList(),
            selectedRunId = null
        )

        assertEquals("# 临时课堂草稿\n\n## 课堂要点\n- 多态调用规则", result.noteText)
        assertEquals("# 临时课堂草稿\n\n## 课堂要点\n- 多态调用规则", result.copyText)
    }

    @Test
    fun noteProgressMarksAudioOutlineBeforeFinalNote() {
        val status = VideoProcessingStatus(
            activeRunId = 42,
            stage = VideoRunStatus.Summarizing,
            message = "音频大纲已生成，正在补强视频分片证据..."
        )
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            markdownNote = "# 音频大纲",
            outlineMarkdown = "# 音频大纲",
            audioOutlineAvailable = true
        )
        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(run),
            selectedRunId = null
        )

        val steps = buildClassroomNoteProgressSteps(status, result)

        assertEquals(
            listOf(
                ClassroomNoteProgressState.Done,
                ClassroomNoteProgressState.Done,
                ClassroomNoteProgressState.Active
            ),
            steps.map { it.state }
        )
        assertTrue(result.hasAudioOutline)
        assertFalse(result.hasFinalNote)
    }

    @Test
    fun noteProgressMarksFinalNoteWhenRunCompletes() {
        val status = VideoProcessingStatus(
            completedRunId = 42,
            stage = VideoRunStatus.Completed
        )
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            status = VideoRunStatus.Completed,
            markdownNote = "# 最终笔记",
            finalSummary = "完成总结",
            audioOutlineAvailable = true
        )
        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(run),
            selectedRunId = null
        )

        val steps = buildClassroomNoteProgressSteps(status, result)

        assertEquals(
            listOf(
                ClassroomNoteProgressState.Done,
                ClassroomNoteProgressState.Done,
                ClassroomNoteProgressState.Done
            ),
            steps.map { it.state }
        )
        assertTrue(result.hasFinalNote)
    }

    @Test
    fun resultContentFallsBackToHistoricalRunWhenStatusOnlyHasSummary() {
        val status = VideoProcessingStatus(
            completedRunId = 42,
            finalSummary = "short summary"
        )
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            markdownNote = "## Historical full note",
            structuredNoteJson = """{"title":"class"}""",
            finalSummary = "historical summary"
        )

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(run),
            selectedRunId = null
        )

        assertEquals("## Historical full note", result.noteText)
        assertEquals("""{"title":"class"}""", result.structuredNoteJson)
    }

    @Test
    fun completedResultUsesPersistedKnowledgeTreeFromHistoricalRun() {
        val tree = ClassroomKnowledgeTree(
            rootTitle = "计算机组成原理",
            nodes = listOf(
                ClassroomKnowledgeNode(
                    id = "module_1",
                    title = "补码表示",
                    oneLineTakeaway = "补码统一了加减法运算。",
                    status = ClassroomKnowledgeNodeStatus.Completed
                )
            ),
            updatedAtMs = 123_000
        )
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            recordingScenario = RecordingScenario.ClassLecture.value,
            status = VideoRunStatus.Completed,
            classroomKnowledgeTreeJson = tree.toPersistedClassroomKnowledgeTreeJson(),
            classroomKnowledgeTreeStatus = ClassroomKnowledgeTreeProcessingStatus.Completed.value
        )

        val result = buildClassroomRecordingResultUiModel(
            status = VideoProcessingStatus(stage = VideoRunStatus.Idle),
            recentRuns = listOf(run),
            selectedRunId = 42
        )

        assertEquals("计算机组成原理", result.knowledgeTree?.rootTitle)
        assertEquals("补码表示", result.knowledgeTree?.nodes?.single()?.title)
        assertEquals(ClassroomKnowledgeTreeProcessingStatus.Completed.value, result.knowledgeTreeStatus)
    }

    @Test
    fun completedResultCanRenderSelectedHistoricalClassroomRunInConcisePage() {
        val status = VideoProcessingStatus(
            stage = VideoRunStatus.Completed,
            activeRunId = 42,
            completedRunId = 42,
            markdownNote = "## Historical classroom note",
            finalSummary = "historical summary",
            recordedDurationSeconds = 600,
            segmentCount = 3,
            analyzedSegmentCount = 3
        )
        val run = VideoProcessRun(
            id = 42,
            taskId = 7,
            taskTitle = "Java 多态复习课",
            recordingScenario = RecordingScenario.ClassLecture.value,
            markdownNote = "## Persisted note",
            fullMediaPath = "/tmp/class.mp4",
            fullMediaVideoSource = "test_video",
            fullMediaHasAudio = true
        )

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = listOf(run),
            selectedRunId = 42
        )

        assertEquals(ClassroomRecordingPhase.Completed, resolveClassroomRecordingPhase(status))
        assertEquals(42L, result.runId)
        assertEquals("Java 多态复习课", result.title)
        assertEquals("## Historical classroom note", result.noteText)
        assertEquals("/tmp/class.mp4", result.playbackPath)
        assertEquals("10:00", result.durationLabel)
        assertEquals("3 / 3 segments", result.segmentLabel)
    }

    @Test
    fun resultContentFallsBackToSummaryWhenNoReportBodyExists() {
        val status = VideoProcessingStatus(finalSummary = "summary only")

        val result = buildClassroomRecordingResultUiModel(
            status = status,
            recentRuns = emptyList(),
            selectedRunId = null
        )

        assertEquals("summary only", result.noteText)
        assertEquals("summary only", result.copyText)
    }
}
