package com.example.watcher.data.local.pose

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary .pose file format v2: Pre-allocated fixed-size slots with bitmap index.
 *
 * Layout:
 *   [Header: 64 bytes]
 *   [Bitmap: ceil(totalFrames/8) bytes] — 1 bit per frame, 1=filled
 *   [Slot 0: SLOT_SIZE bytes]
 *   [Slot 1: SLOT_SIZE bytes]
 *   ...
 *   [Slot N-1: SLOT_SIZE bytes]
 *
 * Total file size = 64 + ceil(N/8) + N × 1056
 * For 4min@30fps (7200 frames) ≈ 7.6 MB
 */
object PoseFileFormat {

    private const val MAGIC = "POSE"
    private const val VERSION: Short = 2
    private const val HEADER_SIZE = 64
    private const val LANDMARK_COUNT = 33
    private const val BYTES_PER_LANDMARK = 32 // 8 floats × 4 bytes
    const val SLOT_SIZE = LANDMARK_COUNT * BYTES_PER_LANDMARK // 1056 bytes per frame

    data class PoseFileHeader(
        val version: Short = VERSION,
        val landmarkCount: Short = LANDMARK_COUNT.toShort(),
        val totalFrameCount: Int,
        val fps: Short,
        val videoDurationMs: Long,
        val videoWidth: Short,
        val videoHeight: Short,
        val filledFrameCount: Int = 0,
        val flags: Short = 0
    )

    data class PoseFrame(
        val frameIndex: Int,
        val timestampMs: Long = 0,
        val landmarks: List<PoseLandmarkData>
    )

    data class PoseLandmarkData(
        val nx: Float, val ny: Float, val nz: Float,
        val visibility: Float, val presence: Float,
        val wx: Float, val wy: Float, val wz: Float
    )

    /**
     * Create a new .pose file pre-allocated for the given frame count.
     * File is immediately full-size but all slots are empty (bitmap all zeros).
     */
    fun createFile(file: File, header: PoseFileHeader) {
        val bitmapSize = bitmapSizeFor(header.totalFrameCount)
        val totalSize = HEADER_SIZE.toLong() + bitmapSize + header.totalFrameCount.toLong() * SLOT_SIZE

        RandomAccessFile(file, "rw").use { raf ->
            // Write header
            val headerBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            headerBuf.put(MAGIC.toByteArray(Charsets.US_ASCII)) // 4
            headerBuf.putShort(header.version) // 2
            headerBuf.putShort(header.landmarkCount) // 2
            headerBuf.putInt(header.totalFrameCount) // 4
            headerBuf.putShort(header.fps) // 2
            headerBuf.putLong(header.videoDurationMs) // 8
            headerBuf.putShort(header.videoWidth) // 2
            headerBuf.putShort(header.videoHeight) // 2
            headerBuf.putInt(0) // filledFrameCount = 0 initially // 4
            headerBuf.putShort(header.flags) // 2
            // remaining 32 bytes are zero (reserved)
            headerBuf.flip()
            raf.seek(0)
            raf.write(headerBuf.array())

            // Pre-allocate file to full size (sparse — OS handles efficiently)
            raf.setLength(totalSize)
        }
    }

    /**
     * Random-access reader/writer for an existing .pose file.
     * Supports reading any frame by index, writing to specific slots,
     * and querying the fill bitmap.
     */
    class SlotFile(file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "rw")
        val header: PoseFileHeader
        private val bitmapSize: Int
        private val bitmapOffset: Long = HEADER_SIZE.toLong()
        private val slotsOffset: Long
        private val bitmap: ByteArray
        private var filledCount: Int
        private var closed = false

        init {
            header = readHeader()
            bitmapSize = bitmapSizeFor(header.totalFrameCount)
            slotsOffset = HEADER_SIZE.toLong() + bitmapSize
            bitmap = ByteArray(bitmapSize)
            raf.seek(bitmapOffset)
            raf.readFully(bitmap)
            filledCount = countSetBits()
        }

        val totalFrames: Int get() = header.totalFrameCount
        val filledFrameCount: Int get() = filledCount
        val fillRatio: Float get() = if (totalFrames > 0) filledCount.toFloat() / totalFrames else 0f

        fun isFrameFilled(frameIndex: Int): Boolean {
            if (frameIndex < 0 || frameIndex >= header.totalFrameCount) return false
            val byteIdx = frameIndex / 8
            val bitIdx = frameIndex % 8
            return (bitmap[byteIdx].toInt() and (1 shl bitIdx)) != 0
        }

        fun readFrame(frameIndex: Int): PoseFrame? {
            if (closed || frameIndex < 0 || frameIndex >= header.totalFrameCount) return null
            if (!isFrameFilled(frameIndex)) return null

            val offset = slotsOffset + frameIndex.toLong() * SLOT_SIZE
            raf.seek(offset)
            val slotBuf = ByteArray(SLOT_SIZE)
            raf.readFully(slotBuf)
            return parseSlot(frameIndex, slotBuf)
        }

        fun writeFrame(frameIndex: Int, frame: PoseFrame) {
            if (closed || frameIndex < 0 || frameIndex >= header.totalFrameCount) return
            if (isFrameFilled(frameIndex)) return // Already filled, skip

            val offset = slotsOffset + frameIndex.toLong() * SLOT_SIZE
            val slotBuf = ByteBuffer.allocate(SLOT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            frame.landmarks.forEach { lm ->
                slotBuf.putFloat(lm.nx); slotBuf.putFloat(lm.ny); slotBuf.putFloat(lm.nz)
                slotBuf.putFloat(lm.visibility); slotBuf.putFloat(lm.presence)
                slotBuf.putFloat(lm.wx); slotBuf.putFloat(lm.wy); slotBuf.putFloat(lm.wz)
            }
            repeat(LANDMARK_COUNT - frame.landmarks.size) {
                repeat(8) { slotBuf.putFloat(0f) }
            }
            slotBuf.flip()
            raf.seek(offset)
            raf.write(slotBuf.array(), 0, SLOT_SIZE)

            // Update bitmap
            val byteIdx = frameIndex / 8
            val bitIdx = frameIndex % 8
            bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            filledCount++
        }

        /**
         * Find the nearest filled frame within a range.
         */
        fun findNearestFilled(frameIndex: Int, maxDistance: Int = 2): Int? {
            if (isFrameFilled(frameIndex)) return frameIndex
            for (d in 1..maxDistance) {
                if (frameIndex - d >= 0 && isFrameFilled(frameIndex - d)) return frameIndex - d
                if (frameIndex + d < header.totalFrameCount && isFrameFilled(frameIndex + d)) return frameIndex + d
            }
            return null
        }

        /**
         * Get list of unfilled frame indices in a range.
         */
        fun getUnfilledFrames(startFrame: Int = 0, endFrame: Int = header.totalFrameCount): List<Int> {
            val result = mutableListOf<Int>()
            for (i in startFrame until endFrame.coerceAtMost(header.totalFrameCount)) {
                if (!isFrameFilled(i)) result.add(i)
            }
            return result
        }

        fun flush() {
            if (closed) return
            // Write bitmap back to file
            raf.seek(bitmapOffset)
            raf.write(bitmap)
            // Update filledFrameCount in header (offset 26: after magic4+ver2+lm2+total4+fps2+dur8+w2+h2 = 26)
            raf.seek(26)
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(filledCount)
            buf.flip()
            raf.write(buf.array())
        }

        override fun close() {
            if (closed) return
            runCatching { flush() }  // Flush BEFORE setting closed flag
            closed = true
            runCatching { raf.close() }
        }

        private fun readHeader(): PoseFileHeader {
            val headerBuf = ByteArray(HEADER_SIZE)
            raf.seek(0)
            raf.readFully(headerBuf)
            val buf = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); buf.get(magic)
            require(String(magic, Charsets.US_ASCII) == MAGIC) { "Invalid .pose file" }
            val version = buf.short
            val landmarkCount = buf.short
            val totalFrameCount = buf.int
            val fps = buf.short
            val durationMs = buf.long
            val width = buf.short
            val height = buf.short
            val filled = buf.int
            val flags = buf.short
            return PoseFileHeader(
                version = version,
                landmarkCount = landmarkCount,
                totalFrameCount = totalFrameCount,
                fps = fps,
                videoDurationMs = durationMs,
                videoWidth = width,
                videoHeight = height,
                filledFrameCount = filled,
                flags = flags
            )
        }

        private fun parseSlot(frameIndex: Int, bytes: ByteArray): PoseFrame {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val landmarks = (0 until LANDMARK_COUNT).map {
                PoseLandmarkData(
                    nx = buf.float, ny = buf.float, nz = buf.float,
                    visibility = buf.float, presence = buf.float,
                    wx = buf.float, wy = buf.float, wz = buf.float
                )
            }
            return PoseFrame(frameIndex = frameIndex, landmarks = landmarks)
        }

        private fun countSetBits(): Int {
            var count = 0
            for (b in bitmap) {
                count += Integer.bitCount(b.toInt() and 0xFF)
            }
            return count
        }
    }

    private fun bitmapSizeFor(frameCount: Int): Int = (frameCount + 7) / 8

    /**
     * Calculate frame index from playback position.
     */
    fun frameIndexForPosition(positionMs: Long, fps: Int, totalFrames: Int): Int {
        return ((positionMs * fps) / 1000L).toInt().coerceIn(0, totalFrames - 1)
    }
}
