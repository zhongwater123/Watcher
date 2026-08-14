package com.example.watcher.data.repository

import com.example.watcher.data.model.VideoAudioAssetEntity
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.VideoSegmentRun
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLocalResourceManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun videoRunManifestCollectsExplicitAndPrefixGeneratedResources() {
        val filesDir = temporaryFolder.newFolder("files")
        val videoRunsDir = File(filesDir, "video_runs").apply { mkdirs() }
        val runId = 42L
        val fullMedia = touch(videoRunsDir, "run_42_full_media.mp4")
        val merged = touch(videoRunsDir, "run_42_merged.mp4")
        val continuousAudio = touch(videoRunsDir, "run_42_continuous_audio.m4a")
        val segment = touch(videoRunsDir, "run_42_segment_1.mp4")
        val segmentAudio = touch(videoRunsDir, "run_42_segment_1_audio.m4a")
        val mergedSegment = touch(videoRunsDir, "run_42_segment_1_merged.mp4")
        val chunk = touch(videoRunsDir, "run_42_chunk_1.mp4")
        val remoteLocal = touch(videoRunsDir, "run_42_remote_local.mp4")
        val frameDir = File(videoRunsDir, "run_42_frame_evidence").apply { mkdirs() }
        val knowledgeDir = File(videoRunsDir, "run_42_knowledge_frames").apply { mkdirs() }
        val otherRunFile = touch(videoRunsDir, "run_420_segment_1.mp4")
        val externalSource = touch(temporaryFolder.newFolder("external"), "source.mp4")

        val manifest = RunLocalResourceCollector.collectVideoRunResources(
            videoRunsDir = videoRunsDir,
            run = VideoProcessRun(
                id = runId,
                taskId = 1,
                fullMediaPath = fullMedia.absolutePath,
                mergedVideoPath = merged.absolutePath,
                continuousAudioPath = continuousAudio.absolutePath
            ),
            segments = listOf(
                VideoSegmentRun(
                    runId = runId,
                    segmentIndex = 1,
                    durationSeconds = 10,
                    localFilePath = segment.absolutePath
                )
            ),
            audioAssets = listOf(
                VideoAudioAssetEntity(
                    runId = runId,
                    assetType = "segmentAudio",
                    localFilePath = segmentAudio.absolutePath,
                    sourceVideoPath = externalSource.absolutePath
                )
            ),
            remoteFileBindings = listOf(
                VideoRemoteFileBindingEntity(
                    runId = runId,
                    assetKind = VideoRemoteAssetKind.MergedSegmentVideo.value,
                    localPath = remoteLocal.absolutePath
                )
            )
        )

        val files = manifest.files.map(File::getAbsolutePath).toSet()
        assertTrue(files.contains(fullMedia.absolutePath))
        assertTrue(files.contains(merged.absolutePath))
        assertTrue(files.contains(continuousAudio.absolutePath))
        assertTrue(files.contains(segment.absolutePath))
        assertTrue(files.contains(segmentAudio.absolutePath))
        assertTrue(files.contains(mergedSegment.absolutePath))
        assertTrue(files.contains(chunk.absolutePath))
        assertTrue(files.contains(remoteLocal.absolutePath))
        assertFalse(files.contains(otherRunFile.absolutePath))
        assertFalse(files.contains(externalSource.absolutePath))
        assertEquals(
            setOf(frameDir.absolutePath, knowledgeDir.absolutePath),
            manifest.directories.map(File::getAbsolutePath).toSet()
        )
    }

    @Test
    fun managedManifestKeepsOnlyFilesInsideAllowedRoots() {
        val filesDir = temporaryFolder.newFolder("files")
        val internalFile = touch(filesDir, "run_42_segment_1.mp4")
        val externalFile = touch(temporaryFolder.newFolder("external"), "source.mp4")
        val manifest = RunLocalResourceManifest(
            runId = 42,
            files = listOf(internalFile, externalFile)
        )

        val managed = manifest.managedBy(listOf(filesDir))

        assertEquals(listOf(internalFile.absolutePath), managed.files.map(File::getAbsolutePath))
    }

    private fun touch(parent: File, name: String): File {
        return File(parent, name).apply {
            parentFile?.mkdirs()
            writeText("x")
        }
    }
}
