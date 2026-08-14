package com.example.watcher.data.repository

import com.example.watcher.data.model.VideoSegmentRun
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoMediaAssemblerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recordedSegmentVideoFilesPreferRecordedSegmentFileOverGuessedRawPath() {
        val outputRoot = temporaryFolder.newFolder("output")
        val videoRunsDir = File(outputRoot, "video_runs").apply { mkdirs() }
        File(videoRunsDir, "run_42_segment_1.mp4").apply {
            writeBytes(byteArrayOf(1))
        }
        val recordedSegmentFile = File(videoRunsDir, "run_42_segment_1_merged.mp4").apply {
            writeBytes(byteArrayOf(2))
        }
        val segment = RecordedSegment(
            segment = VideoSegmentRun(
                runId = 42,
                segmentIndex = 1,
                durationSeconds = 60,
                localFilePath = recordedSegmentFile.absolutePath
            ),
            file = recordedSegmentFile,
            segmentNumber = 1,
            durationSeconds = 60,
            startOffsetSeconds = 0,
            wallClockStartTime = 1_000,
            wallClockEndTime = 61_000,
            hasAudio = true
        )

        val files = resolveRecordedSegmentVideoFiles(
            runId = 42,
            segments = listOf(segment),
            outputRoot = outputRoot
        )

        assertEquals(listOf(recordedSegmentFile.absolutePath), files.map { it.absolutePath })
    }
}
