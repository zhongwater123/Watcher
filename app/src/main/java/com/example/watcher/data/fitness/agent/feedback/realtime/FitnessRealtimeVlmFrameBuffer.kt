package com.example.watcher.data.fitness.agent.feedback.realtime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.example.watcher.data.training.fitness.TrainingFrame
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

internal data class FitnessRealtimeVlmFrame(
    val frameSeq: Long,
    val sourceFrameSeq: Long,
    val capturedAtMs: Long,
    val bufferedAtElapsedMs: Long,
    val source: String,
    val imageDataUri: String,
    val width: Int,
    val height: Int,
    val byteLength: Int
)

internal class FitnessRealtimeVlmFrameBuffer(
    private val retentionMs: Long,
    private val maxLongEdgePx: Int,
    private val jpegQuality: Int
) {
    private val frames = ArrayDeque<FitnessRealtimeVlmFrame>()
    private var nextSeq = 0L

    @Synchronized
    fun offer(
        frame: TrainingFrame,
        sourceFrameSeq: Long,
        bufferedAtElapsedMs: Long,
        source: String
    ): FitnessRealtimeVlmFrame? {
        val encoded = runCatching { encodeFrame(frame.bitmap) }.getOrNull() ?: return null
        val bufferedFrame = FitnessRealtimeVlmFrame(
            frameSeq = ++nextSeq,
            sourceFrameSeq = sourceFrameSeq,
            capturedAtMs = frame.capturedAtMs,
            bufferedAtElapsedMs = bufferedAtElapsedMs,
            source = source,
            imageDataUri = encoded.dataUri,
            width = encoded.width,
            height = encoded.height,
            byteLength = encoded.byteLength
        )
        frames.addLast(bufferedFrame)
        pruneLocked(bufferedAtElapsedMs)
        return bufferedFrame
    }

    @Synchronized
    fun snapshotSize(): Int = frames.size

    @Synchronized
    fun latestFrame(nowElapsedMs: Long): FitnessRealtimeVlmFrame? {
        pruneLocked(nowElapsedMs)
        return frames.lastOrNull()
    }

    @Synchronized
    fun clear() {
        frames.clear()
    }

    private fun pruneLocked(nowElapsedMs: Long) {
        val cutoff = nowElapsedMs - retentionMs
        while (frames.isNotEmpty() && frames.first().bufferedAtElapsedMs < cutoff) {
            frames.removeFirst()
        }
    }

    private fun encodeFrame(bitmap: Bitmap): EncodedFrame {
        val scaled = bitmap.scaledForLongEdge(maxLongEdgePx)
        val bytes = ByteArrayOutputStream((scaled.width * scaled.height / 4).coerceAtLeast(1)).use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
            output.toByteArray()
        }
        val encoded = EncodedFrame(
            dataUri = "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            width = scaled.width,
            height = scaled.height,
            byteLength = bytes.size
        )
        if (scaled !== bitmap) scaled.recycle()
        return encoded
    }

    private fun Bitmap.scaledForLongEdge(maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return this
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(2)
        val targetHeight = (height * scale).toInt().coerceAtLeast(2)
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                this@scaledForLongEdge,
                Rect(0, 0, this@scaledForLongEdge.width, this@scaledForLongEdge.height),
                Rect(0, 0, targetWidth, targetHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }
        return output
    }

    private data class EncodedFrame(
        val dataUri: String,
        val width: Int,
        val height: Int,
        val byteLength: Int
    )
}
