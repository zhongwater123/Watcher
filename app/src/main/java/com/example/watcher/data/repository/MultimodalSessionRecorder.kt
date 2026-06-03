package com.example.watcher.data.repository

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.jcodec.api.android.AndroidSequenceEncoder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class MultimodalSessionRecorder {
    fun start(
        scope: CoroutineScope,
        runId: Long,
        outputRoot: File,
        frameProvider: () -> Bitmap?,
        targetFps: Int,
        videoSource: String
    ): MultimodalSessionRecording {
        val stopRequested = AtomicBoolean(false)
        val outputFile = File(outputRoot, "video_runs/run_${runId}_full_media.mp4")
        val startedAt = System.currentTimeMillis()
        val safeFps = targetFps.coerceIn(MIN_FULL_MEDIA_FPS, MAX_FULL_MEDIA_FPS)
        val job = scope.async(Dispatchers.IO) {
            runCatching {
                recordFullVideo(
                    outputFile = outputFile,
                    frameProvider = frameProvider,
                    stopRequested = stopRequested,
                    startedAt = startedAt,
                    targetFps = safeFps,
                    videoSource = videoSource
                )
            }.getOrElse {
                outputFile.delete()
                MetadataRecordingResult(
                    file = null,
                    durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                    hasAudio = false,
                    videoSource = videoSource
                )
            }
        }
        return MultimodalSessionRecording(
            startedAt = startedAt,
            stopRequested = stopRequested,
            result = job
        )
    }

    private suspend fun recordFullVideo(
        outputFile: File,
        frameProvider: () -> Bitmap?,
        stopRequested: AtomicBoolean,
        startedAt: Long,
        targetFps: Int,
        videoSource: String
    ): MetadataRecordingResult {
        outputFile.parentFile?.mkdirs()
        val frameIntervalMs = (1_000L / targetFps).coerceAtLeast(50L)
        val encoder = AndroidSequenceEncoder.createSequenceEncoder(outputFile, targetFps)
        var capturedFrameCount = 0

        try {
            while (!stopRequested.get()) {
                val bitmap = frameProvider()
                    ?.let(::normalizeBitmapForEncoding)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                if (bitmap != null) {
                    encoder.encodeImage(bitmap)
                    capturedFrameCount += 1
                }
                delay(frameIntervalMs)
            }
        } finally {
            runCatching { encoder.finish() }
        }

        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        if (capturedFrameCount == 0) {
            outputFile.delete()
            return MetadataRecordingResult(
                file = null,
                durationMs = durationMs,
                hasAudio = false,
                videoSource = videoSource
            )
        }
        return MetadataRecordingResult(
            file = outputFile,
            durationMs = durationMs,
            hasAudio = false,
            videoSource = videoSource
        )
    }

    private companion object {
        private const val MIN_FULL_MEDIA_FPS = 2
        private const val MAX_FULL_MEDIA_FPS = 12
    }
}

internal class MultimodalSessionRecording(
    val startedAt: Long,
    private val stopRequested: AtomicBoolean,
    private val result: Deferred<MetadataRecordingResult>
) {
    suspend fun stop(): MetadataRecordingResult {
        stopRequested.set(true)
        return result.await()
    }
}
