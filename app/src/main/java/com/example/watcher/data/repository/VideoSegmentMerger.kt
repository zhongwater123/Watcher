package com.example.watcher.data.repository

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class VideoSegmentMerger {
    suspend fun mergeSegments(
        segmentFiles: List<File>,
        outputFile: File
    ): File {
        val validSegmentFiles = segmentFiles.filter { it.exists() && it.length() > 0L }
        if (validSegmentFiles.isEmpty()) {
            throw IllegalArgumentException("No valid video segments to merge.")
        }
        outputFile.parentFile?.mkdirs()
        if (validSegmentFiles.size == 1) {
            validSegmentFiles.first().copyTo(outputFile, overwrite = true)
            return outputFile
        }

        val trackFormats = validSegmentFiles.map(::readTrackFormats)
        val videoFormat = trackFormats.firstOrNull { it.video != null }?.video
            ?: throw IllegalArgumentException("No segment contains a video track.")
        val audioFormat = trackFormats.firstOrNull { it.audio != null }?.audio
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoTrackIndex = muxer.addTrack(videoFormat)
        val audioTrackIndex = audioFormat?.let(muxer::addTrack)
        val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_SIZE)
        var segmentStartUs = 0L

        try {
            muxer.start()
            validSegmentFiles.forEach { file ->
                val videoStats = copyTrackSamples(
                    inputFile = file,
                    mimePrefix = "video/",
                    outputTrackIndex = videoTrackIndex,
                    muxer = muxer,
                    buffer = buffer,
                    presentationOffsetUs = segmentStartUs
                )
                val audioStats = if (audioTrackIndex != null) {
                    copyTrackSamples(
                        inputFile = file,
                        mimePrefix = "audio/",
                        outputTrackIndex = audioTrackIndex,
                        muxer = muxer,
                        buffer = buffer,
                        presentationOffsetUs = segmentStartUs
                    )
                } else {
                    TrackCopyStats()
                }
                segmentStartUs += maxOf(videoStats.durationUs, audioStats.durationUs)
                    .coerceAtLeast(MIN_SEGMENT_OFFSET_US)
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        return outputFile
    }

    fun validateMedia(file: File): MediaValidationResult {
        if (!file.exists() || file.length() <= 0L) {
            return MediaValidationResult(errorMessage = "Media file is missing or empty.")
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideoTrack = false
            var hasAudioTrack = false
            var videoSamples = 0
            var audioSamples = 0
            var maxPtsUs = 0L

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> hasVideoTrack = true
                    mime.startsWith("audio/") -> hasAudioTrack = true
                }
            }

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(index)
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0L) break
                    maxPtsUs = maxPtsUs.coerceAtLeast(sampleTime)
                    if (mime.startsWith("video/")) videoSamples++ else audioSamples++
                    extractor.advance()
                }
                extractor.unselectTrack(index)
            }

            MediaValidationResult(
                durationMs = maxPtsUs / 1_000L,
                hasVideo = hasVideoTrack && videoSamples > 0,
                hasAudio = hasAudioTrack && audioSamples > 0,
                errorMessage = when {
                    !hasVideoTrack -> "Merged media has no video track."
                    videoSamples <= 0 -> "Merged media has no video samples."
                    else -> null
                }
            )
        } catch (error: Exception) {
            MediaValidationResult(errorMessage = error.message ?: "Failed to validate media.")
        } finally {
            extractor.release()
        }
    }

    private fun readTrackFormats(file: File): TrackFormats {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    video == null && mime.startsWith("video/") -> video = format
                    audio == null && mime.startsWith("audio/") -> audio = format
                }
            }
            TrackFormats(video = video, audio = audio)
        } finally {
            extractor.release()
        }
    }

    private fun copyTrackSamples(
        inputFile: File,
        mimePrefix: String,
        outputTrackIndex: Int,
        muxer: MediaMuxer,
        buffer: ByteBuffer,
        presentationOffsetUs: Long
    ): TrackCopyStats {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            val inputTrackIndex = findTrackIndex(extractor, mimePrefix) ?: return TrackCopyStats()
            extractor.selectTrack(inputTrackIndex)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0
            var firstPtsUs: Long? = null
            var lastPtsUs = 0L
            var lastDeltaUs = DEFAULT_SAMPLE_DELTA_US
            var hasObservedDelta = false
            var previousPtsUs: Long? = null
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                val firstPts = firstPtsUs ?: sampleTimeUs.also { firstPtsUs = it }
                val normalizedPtsUs = (sampleTimeUs - firstPts).coerceAtLeast(0L)
                previousPtsUs?.let { previous ->
                    val delta = (normalizedPtsUs - previous).takeIf { it > 0L }
                    if (delta != null) {
                        lastDeltaUs = delta
                        hasObservedDelta = true
                    }
                }
                bufferInfo.set(
                    0,
                    sampleSize,
                    presentationOffsetUs + normalizedPtsUs,
                    extractor.sampleFlags
                )
                muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                sampleCount++
                lastPtsUs = normalizedPtsUs
                previousPtsUs = normalizedPtsUs
                extractor.advance()
            }
            val tailDeltaUs = if (hasObservedDelta) lastDeltaUs else DEFAULT_SAMPLE_DELTA_US
            TrackCopyStats(
                sampleCount = sampleCount,
                durationUs = if (sampleCount > 0) lastPtsUs + tailDeltaUs else 0L
            )
        } finally {
            extractor.release()
        }
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        return null
    }

    private data class TrackFormats(
        val video: MediaFormat?,
        val audio: MediaFormat?
    )

    private data class TrackCopyStats(
        val sampleCount: Int = 0,
        val durationUs: Long = 0L
    )

    private companion object {
        private const val COPY_BUFFER_SIZE = 2 * 1024 * 1024
        private const val DEFAULT_SAMPLE_DELTA_US = 33_333L
        private const val MIN_SEGMENT_OFFSET_US = 1_000L
    }
}

data class MediaValidationResult(
    val durationMs: Long = 0L,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    val errorMessage: String? = null
)
