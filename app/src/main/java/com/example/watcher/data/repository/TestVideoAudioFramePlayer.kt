package com.example.watcher.data.repository

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

internal class TestVideoAudioFramePlayer(
    private val videoFile: File,
    private val startOffsetMs: Long = 0L,
    private val endOffsetMs: Long,
    private val wallClockStartedAtMs: Long,
    private val onFrame: (ClassroomAudioFrame) -> Unit
) {
    fun audioFormat(): TestVideoAudioFormat? {
        if (!videoFile.exists()) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = findAudioTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            TestVideoAudioFormat(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                bitsPerSample = 16
            )
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    fun play(shouldStop: () -> Boolean) {
        if (!videoFile.exists()) return
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val sequence = AtomicLong(0L)
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = findAudioTrack(extractor) ?: return
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            extractor.seekTo(startOffsetMs * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            while (!decoderDone && !shouldStop()) {
                if (!extractorDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) {
                            inputBuffer.clear()
                            extractor.readSampleData(inputBuffer, 0)
                        } else {
                            -1
                        }
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleSize < 0 || sampleTimeUs / 1_000L > endOffsetMs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= startOffsetMs * 1_000L) {
                            val relativeStartMs = (bufferInfo.presentationTimeUs / 1_000L - startOffsetMs).coerceAtLeast(0L)
                            sleepUntil(wallClockStartedAtMs + relativeStartMs, shouldStop)
                            val pcm = outputBuffer.toByteArray(bufferInfo.offset, bufferInfo.size)
                            onFrame(
                                ClassroomAudioFrame(
                                    sequence = sequence.incrementAndGet(),
                                    pcm = pcm,
                                    sampleRate = sampleRate,
                                    channelCount = channelCount,
                                    bitsPerSample = 16,
                                    capturedAtMs = System.currentTimeMillis(),
                                    relativeStartMs = relativeStartMs,
                                    durationMs = estimateDurationMs(bufferInfo.size, sampleRate, channelCount)
                                )
                            )
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun sleepUntil(targetMs: Long, shouldStop: () -> Boolean) {
        while (!shouldStop()) {
            val waitMs = targetMs - System.currentTimeMillis()
            if (waitMs <= 0L) return
            Thread.sleep(waitMs.coerceAtMost(50L))
        }
    }

    private fun estimateDurationMs(byteCount: Int, sampleRate: Int, channelCount: Int): Long {
        val bytesPerSampleFrame = (channelCount.coerceAtLeast(1) * 2).coerceAtLeast(1)
        return byteCount / bytesPerSampleFrame * 1_000L / sampleRate.coerceAtLeast(1)
    }

    private fun ByteBuffer.toByteArray(offset: Int, size: Int): ByteArray {
        val duplicate = duplicate()
        duplicate.position(offset)
        duplicate.limit(offset + size)
        return ByteArray(size).also { duplicate.get(it) }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return null
    }
}

internal data class TestVideoAudioFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int
)
