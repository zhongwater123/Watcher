package com.example.watcher.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryModelsTest {
    @Test
    fun stalePostCaptureVideoRunAllowsManualCleanup() {
        val now = 3_600_000L
        val run = VideoProcessRun(
            id = 1L,
            taskId = 1L,
            status = VideoRunStatus.Summarizing,
            recordingEndedAt = 1_000L,
            updatedAt = now - 31 * 60 * 1_000L
        )

        assertTrue(isStalePostCaptureVideoRun(run, now))
        assertTrue(VideoHistoryDetail(run = run, segments = emptyList(), events = emptyList()).canDelete)
    }

    @Test
    fun freshPostCaptureVideoRunDoesNotAllowManualCleanup() {
        val now = 3_600_000L
        val run = VideoProcessRun(
            id = 1L,
            taskId = 1L,
            status = VideoRunStatus.Summarizing,
            recordingEndedAt = 1_000L,
            updatedAt = now - 5 * 60 * 1_000L
        )

        assertFalse(isStalePostCaptureVideoRun(run, now))
        assertFalse(VideoHistoryDetail(run = run, segments = emptyList(), events = emptyList()).canDelete)
    }

    @Test
    fun staleRecordingRunAllowsManualCleanup() {
        val now = 3_600_000L
        val run = VideoProcessRun(
            id = 1L,
            taskId = 1L,
            status = VideoRunStatus.Recording,
            recordingEndedAt = null,
            updatedAt = now - 31 * 60 * 1_000L
        )

        assertTrue(isStalePostCaptureVideoRun(run, now))
        assertTrue(VideoHistoryDetail(run = run, segments = emptyList(), events = emptyList()).canDelete)
    }

    @Test
    fun completedVideoRunAllowsDelete() {
        val run = VideoProcessRun(
            id = 1L,
            taskId = 1L,
            status = VideoRunStatus.Completed
        )

        assertTrue(VideoHistoryDetail(run = run, segments = emptyList(), events = emptyList()).canDelete)
    }

    @Test
    fun videoHistoryDetailKeepsFullCountsWhenPreviewListsAreLimited() {
        val detail = VideoHistoryDetail(
            run = VideoProcessRun(id = 1L, taskId = 1L),
            segments = listOf(VideoSegmentRun(runId = 1L, segmentIndex = 0, durationSeconds = 5)),
            events = listOf(TimelineEventEntity(runId = 1L, timestampSeconds = 0, title = "start", detail = "detail")),
            speechTranscripts = listOf(
                VideoSpeechTranscriptEntity(runId = 1L, timestamp = 0L, displayTimestamp = "00:00", text = "hello")
            ),
            totalSegmentCount = 120,
            totalEventCount = 80,
            totalSpeechTranscriptCount = 300
        )

        assertEquals(1, detail.segments.size)
        assertEquals(120, detail.totalSegmentCount)
        assertEquals(80, detail.totalEventCount)
        assertEquals(300, detail.totalSpeechTranscriptCount)
    }

    @Test
    fun monitorHistoryDetailKeepsFullCountsWhenPreviewListsAreLimited() {
        val detail = MonitorHistoryDetail(
            run = MonitorRun(id = 1L, taskTitle = "monitor", taskRequirement = "watch"),
            events = listOf(
                MonitorEventEntity(
                    runId = 1L,
                    result = CheckResult.NORMAL,
                    message = "ok",
                    action = MonitorLogAction.RESULT
                )
            ),
            media = listOf(MonitorMediaEntity(runId = 1L, localFilePath = "/tmp/frame.jpg")),
            totalEventCount = 250,
            totalMediaCount = 75
        )

        assertEquals(1, detail.events.size)
        assertEquals(1, detail.media.size)
        assertEquals(250, detail.totalEventCount)
        assertEquals(75, detail.totalMediaCount)
    }

    @Test
    fun staleRunningMonitorRunAllowsManualCleanup() {
        val now = 24 * 60 * 60 * 1_000L
        val run = MonitorRun(
            id = 1L,
            taskTitle = "monitor",
            taskRequirement = "watch",
            status = MonitorRunStatus.Running,
            updatedAt = now - 7 * 60 * 60 * 1_000L
        )

        assertTrue(isStaleMonitorRun(run, now))
        assertTrue(MonitorHistoryDetail(run = run, events = emptyList(), media = emptyList()).canDelete)
        assertEquals("疑似中断", historyMonitorRunStatusLabel(run, now))
    }

    @Test
    fun freshRunningMonitorRunDoesNotAllowManualCleanup() {
        val now = 24 * 60 * 60 * 1_000L
        val run = MonitorRun(
            id = 1L,
            taskTitle = "monitor",
            taskRequirement = "watch",
            status = MonitorRunStatus.Running,
            updatedAt = now - 5 * 60 * 1_000L
        )

        assertFalse(isStaleMonitorRun(run, now))
        assertFalse(MonitorHistoryDetail(run = run, events = emptyList(), media = emptyList()).canDelete)
        assertEquals("监控中", historyMonitorRunStatusLabel(run, now))
    }

    @Test
    fun stalePausedMonitorRunAllowsManualCleanup() {
        val now = 24 * 60 * 60 * 1_000L
        val run = MonitorRun(
            id = 1L,
            taskTitle = "monitor",
            taskRequirement = "watch",
            status = MonitorRunStatus.Paused,
            updatedAt = now - 7 * 60 * 60 * 1_000L
        )

        assertTrue(isStaleMonitorRun(run, now))
        assertTrue(MonitorHistoryDetail(run = run, events = emptyList(), media = emptyList()).canDelete)
        assertEquals("已暂停(疑似中断)", historyMonitorRunStatusLabel(run, now))
    }

    @Test
    fun freshPausedMonitorRunDoesNotAllowManualCleanup() {
        val now = 24 * 60 * 60 * 1_000L
        val run = MonitorRun(
            id = 1L,
            taskTitle = "monitor",
            taskRequirement = "watch",
            status = MonitorRunStatus.Paused,
            updatedAt = now - 5 * 60 * 1_000L
        )

        assertFalse(isStaleMonitorRun(run, now))
        assertFalse(MonitorHistoryDetail(run = run, events = emptyList(), media = emptyList()).canDelete)
        assertEquals("已暂停", historyMonitorRunStatusLabel(run, now))
    }
}
