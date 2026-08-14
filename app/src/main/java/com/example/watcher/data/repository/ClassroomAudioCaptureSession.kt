package com.example.watcher.data.repository

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal class ClassroomAudioCaptureSession(
    private val outputFile: File,
    private val onFrame: (ClassroomAudioFrame) -> Unit,
    private val targetFrameDurationMs: Long = 200L
) {
    private val sequence = AtomicLong(0L)
    private val aggregateLock = Any()
    private val aggregateBuffer = ByteArrayOutputStream()
    private var aggregateStartMs: Long? = null
    private var aggregateCapturedAtMs: Long = 0L
    private var aggregateDurationMs: Long = 0L
    private var sampleRate: Int = 48_000
    private var channelCount: Int = 1
    private var bitsPerSample: Int = 16

    private val recorder = ContinuousAudioRecorder { frame ->
        appendPcmFrame(frame)
    }

    val file: File get() = outputFile
    val startedAtMs: Long get() = recorder.startedAtMs
    val isRecording: Boolean get() = recorder.isRecording

    fun start(): Boolean {
        outputFile.parentFile?.mkdirs()
        return recorder.start(outputFile)
    }

    fun stop(): ContinuousAudioResult {
        val result = recorder.stop()
        flushAggregate()
        return result
    }

    fun release() {
        recorder.release()
        flushAggregate()
    }

    private fun appendPcmFrame(frame: ContinuousPcmFrame) {
        synchronized(aggregateLock) {
            if (aggregateStartMs == null) {
                aggregateStartMs = frame.relativeStartMs
                aggregateCapturedAtMs = frame.capturedAtMs
            }
            sampleRate = frame.sampleRate
            channelCount = frame.channelCount
            bitsPerSample = frame.bitsPerSample
            aggregateBuffer.write(frame.pcm)
            aggregateDurationMs += frame.durationMs
            if (aggregateDurationMs >= targetFrameDurationMs) {
                emitAggregateLocked()
            }
        }
    }

    private fun flushAggregate() {
        synchronized(aggregateLock) {
            if (aggregateBuffer.size() > 0) {
                emitAggregateLocked()
            }
        }
    }

    private fun emitAggregateLocked() {
        val startMs = aggregateStartMs ?: return
        val bytes = aggregateBuffer.toByteArray()
        if (bytes.isEmpty()) return
        aggregateBuffer.reset()
        val frame = ClassroomAudioFrame(
            sequence = sequence.incrementAndGet(),
            pcm = bytes,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            capturedAtMs = aggregateCapturedAtMs,
            relativeStartMs = startMs,
            durationMs = aggregateDurationMs.coerceAtLeast(1L)
        )
        aggregateStartMs = null
        aggregateDurationMs = 0L
        aggregateCapturedAtMs = 0L
        onFrame(frame)
    }
}
