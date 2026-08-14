package com.example.watcher.data.repository

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.Closeable
import java.io.File

internal class TestVideoFrameProvider(
    private val videoFile: File,
    private val startOffsetMs: Long,
    private val wallClockStartedAtMs: Long
) : Closeable {
    private val retriever = MediaMetadataRetriever().apply {
        setDataSource(videoFile.absolutePath)
    }

    fun currentFrame(): Bitmap? {
        val elapsedMs = (System.currentTimeMillis() - wallClockStartedAtMs).coerceAtLeast(0L)
        val timestampUs = (startOffsetMs + elapsedMs).coerceAtLeast(0L) * 1_000L
        return retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    override fun close() {
        retriever.release()
    }
}
