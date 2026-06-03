package com.example.watcher.data.repository

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Utility for slicing audio from a continuous recording by time window
 * and merging audio + video tracks into a single MP4.
 */
class AudioSegmentSlicer {

    /**
     * Extract a time-windowed audio slice from a continuous .m4a file.
     *
     * @param sourceFile The continuous audio file (e.g., run_X_continuous.m4a)
     * @param startMs Start time in milliseconds (relative to file start)
     * @param endMs End time in milliseconds (relative to file start)
     * @param outputFile Destination for the sliced audio
     * @return true if slicing produced a valid output file
     */
    fun sliceAudio(
        sourceFile: File,
        startMs: Long,
        endMs: Long,
        outputFile: File
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return false
        if (startMs >= endMs) return false
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            val audioTrackIndex = findAudioTrack(extractor) ?: return false
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            val startUs = startMs * 1_000L
            val endUs = endMs * 1_000L

            // Seek to the start position
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            var samplesWritten = 0
            var firstPtsUs: Long? = null

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break
                if (sampleTimeUs < startUs) {
                    extractor.advance()
                    continue
                }

                // Normalize timestamps to start from 0
                val first = firstPtsUs ?: sampleTimeUs.also { firstPtsUs = it }
                val normalizedPts = (sampleTimeUs - first).coerceAtLeast(0L)

                bufferInfo.set(0, sampleSize, normalizedPts, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                samplesWritten++
                extractor.advance()
            }

            runCatching { muxer.stop() }
            runCatching { muxer.release() }

            if (samplesWritten == 0) {
                outputFile.delete()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            outputFile.delete()
            false
        } finally {
            extractor.release()
        }
    }

    /**
     * Merge a video-only MP4 and an audio-only file into a single MP4 with both tracks.
     *
     * @param videoFile Pure video MP4 (no audio track)
     * @param audioFile Audio file (.m4a or .mp4 with audio track)
     * @param outputFile Destination merged MP4
     * @return true if merging succeeded
     */
    fun mergeVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        if (!videoFile.exists() || !audioFile.exists()) return false
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrackIndex = findVideoTrack(videoExtractor) ?: return false
            val audioTrackIndex = findAudioTrack(audioExtractor) ?: return false

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            // Copy video track
            copySamples(videoExtractor, muxer, muxerVideoTrack, buffer, bufferInfo)

            // Copy audio track
            copySamples(audioExtractor, muxer, muxerAudioTrack, buffer, bufferInfo)

            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            outputFile.delete()
            false
        } finally {
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            bufferInfo.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private companion object {
        private const val BUFFER_SIZE = 512 * 1024
    }
}
