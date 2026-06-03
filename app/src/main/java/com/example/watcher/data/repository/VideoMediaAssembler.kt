package com.example.watcher.data.repository

import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import java.io.File

/**
 * Handles media assembly operations: merging video segments,
 * combining video with audio, chunking, and validation.
 */
internal class VideoMediaAssembler(
    private val segmentMerger: VideoSegmentMerger,
    private val remoteFileResolver: VideoRemoteFileResolver
) {

    suspend fun mergeSegmentVideos(
        runId: Long,
        task: VideoProcessTaskDraft,
        results: List<SegmentExecutionResult>,
        outputRoot: File
    ): String {
        val segmentFiles = results
            .sortedBy { it.segment.segmentIndex }
            .mapNotNull { result ->
                result.segment.localFilePath
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.takeIf(File::exists)
            }
        if (segmentFiles.isEmpty()) {
            throw IllegalStateException("No local segment files are available for merging.")
        }

        val outputFile = File(outputRoot, "video_runs/run_${runId}_merged.mp4")
        return segmentMerger.mergeSegments(
            segmentFiles = segmentFiles,
            outputFile = outputFile
        ).absolutePath
    }

    suspend fun mergeVideoWithMasterAudio(
        runId: Long,
        videoPath: String?,
        audioPath: String?,
        outputRoot: File
    ): String? {
        val videoFile = videoPath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
            ?: return videoPath
        val audioFile = audioPath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
            ?: return videoPath
        val outputFile = File(outputRoot, "video_runs/run_${runId}_full_media.mp4")
        return if (AudioSegmentSlicer().mergeVideoAndAudio(videoFile, audioFile, outputFile)) {
            runCatching {
                remoteFileResolver.recordLocalFileBinding(
                    file = outputFile,
                    runId = runId,
                    segmentRunId = null,
                    assetKind = VideoRemoteAssetKind.FullMediaVideo,
                    mediaType = "video/mp4"
                )
            }
            outputFile.absolutePath
        } else {
            videoPath
        }
    }

    suspend fun mergeVideoChunks(
        runId: Long,
        chunkPlans: List<VideoChunkPlan>,
        outputRoot: File
    ): List<File> {
        return chunkPlans.map { plan ->
            val outputFile = File(outputRoot, "video_runs/run_${runId}_chunk_${plan.chunkIndex}.mp4")
            segmentMerger.mergeSegments(
                segmentFiles = plan.files,
                outputFile = outputFile
            )
            remoteFileResolver.recordLocalFileBinding(
                file = outputFile,
                runId = runId,
                segmentRunId = null,
                assetKind = VideoRemoteAssetKind.MergedChunkVideo,
                mediaType = "video/mp4"
            )
            outputFile
        }
    }

    fun validateMedia(path: String?): MediaValidationResult {
        return path
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.let(segmentMerger::validateMedia)
            ?: MediaValidationResult(errorMessage = "No merged media path.")
    }

    suspend fun recordDerivedVideoAssetBinding(
        runId: Long,
        filePath: String?,
        assetKind: VideoRemoteAssetKind,
        segmentRunId: Long? = null
    ) {
        val file = filePath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
            ?: return
        remoteFileResolver.recordLocalFileBinding(
            file = file,
            runId = runId,
            segmentRunId = segmentRunId,
            assetKind = assetKind,
            mediaType = "video/mp4"
        )
    }
}
