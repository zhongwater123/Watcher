package com.example.watcher.data.local.pose

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary .beat file format v1: Event-based beat/segment/phrase storage.
 *
 * Layout:
 *   [Header: 128 bytes]
 *   [Beat entries: beatCount x 16 bytes]
 *   [Segment entries: segmentCount x 32 bytes]
 *   [Phrase entries: phraseCount x 24 bytes]
 *
 * Alignment guarantee: totalFrameCount, fps, videoDurationMs must match the
 * corresponding .pose file header for the same session.
 */
object BeatFileFormat {

    private const val MAGIC = "BEAT"
    private const val VERSION: Short = 1
    private const val HEADER_SIZE = 128
    private const val BEAT_ENTRY_SIZE = 16
    private const val SEGMENT_ENTRY_SIZE = 32
    private const val PHRASE_ENTRY_SIZE = 24

    data class BeatFileHeader(
        val version: Short = VERSION,
        val totalFrameCount: Int,
        val fps: Short,
        val videoDurationMs: Long,
        val bpmTenths: Int,          // BPM x 10, e.g. 1200 = 120.0 BPM
        val beatCount: Int,
        val segmentCount: Int,
        val phraseCount: Int,
        val timeSignatureNum: Short = 4,
        val timeSignatureDen: Short = 4,
        val flags: Int = 0           // bit0: hasLLMValidation, bit1: dspOnly
    ) {
        val bpm: Float get() = bpmTenths / 10f
    }

    data class BeatEntry(
        val timestampMs: Int,
        val frameIndex: Int,
        val strength: Float,         // 0.0 - 1.0
        val beatType: BeatType,
        val confidence: Float        // 0.0 - 1.0
    )

    enum class BeatType(val code: Byte) {
        DOWNBEAT(0), UPBEAT(1), ACCENT(2);
        companion object {
            fun fromCode(code: Byte): BeatType = entries.firstOrNull { it.code == code } ?: UPBEAT
        }
    }

    data class SegmentEntry(
        val startMs: Int,
        val endMs: Int,
        val startFrameIdx: Int,
        val endFrameIdx: Int,
        val segmentType: SegmentType,
        val energyLevel: Float       // 0.0 - 1.0
    )

    enum class SegmentType(val code: Byte) {
        INTRO(0), VERSE(1), CHORUS(2), BRIDGE(3), OUTRO(4), BREAK(5);
        companion object {
            fun fromCode(code: Byte): SegmentType = entries.firstOrNull { it.code == code } ?: VERSE
        }
    }

    data class PhraseEntry(
        val startMs: Int,
        val endMs: Int,
        val startFrameIdx: Int,
        val endFrameIdx: Int,
        val beatCountInPhrase: Int,
        val phraseType: PhraseType,
        val difficulty: Float        // 0.0 - 1.0
    )

    enum class PhraseType(val code: Byte) {
        EIGHT_COUNT(0), FOUR_COUNT(1), CUSTOM(2);
        companion object {
            fun fromCode(code: Byte): PhraseType = entries.firstOrNull { it.code == code } ?: EIGHT_COUNT
        }
    }

    data class BeatFileData(
        val header: BeatFileHeader,
        val beats: List<BeatEntry>,
        val segments: List<SegmentEntry>,
        val phrases: List<PhraseEntry>
    )

    /**
     * Create a complete .beat file from analysis results.
     */
    fun createFile(
        file: File,
        header: BeatFileHeader,
        beats: List<BeatEntry>,
        segments: List<SegmentEntry>,
        phrases: List<PhraseEntry>
    ) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)

            // Write header (128 bytes)
            val headerBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            headerBuf.put(MAGIC.toByteArray(Charsets.US_ASCII))  // 4
            headerBuf.putShort(header.version)                    // 2
            headerBuf.putShort(0)                                 // 2 reserved
            headerBuf.putInt(header.totalFrameCount)              // 4
            headerBuf.putShort(header.fps)                        // 2
            headerBuf.putShort(0)                                 // 2 reserved
            headerBuf.putLong(header.videoDurationMs)             // 8
            headerBuf.putInt(header.bpmTenths)                    // 4
            headerBuf.putInt(header.beatCount)                    // 4
            headerBuf.putInt(header.segmentCount)                 // 4
            headerBuf.putInt(header.phraseCount)                  // 4
            headerBuf.putShort(header.timeSignatureNum)           // 2
            headerBuf.putShort(header.timeSignatureDen)           // 2
            headerBuf.putInt(header.flags)                        // 4
            // remaining 80 bytes are zero (reserved)
            headerBuf.flip()
            raf.write(headerBuf.array())

            // Write beat entries
            val beatBuf = ByteBuffer.allocate(BEAT_ENTRY_SIZE * beats.size).order(ByteOrder.LITTLE_ENDIAN)
            beats.forEach { beat ->
                beatBuf.putInt(beat.timestampMs)
                beatBuf.putInt(beat.frameIndex)
                beatBuf.putShort((beat.strength * 1000).toInt().coerceIn(0, 1000).toShort())
                beatBuf.put(beat.beatType.code)
                beatBuf.put((beat.confidence * 255).toInt().coerceIn(0, 255).toByte())
                beatBuf.putInt(0) // reserved
            }
            beatBuf.flip()
            raf.write(beatBuf.array())

            // Write segment entries
            val segBuf = ByteBuffer.allocate(SEGMENT_ENTRY_SIZE * segments.size).order(ByteOrder.LITTLE_ENDIAN)
            segments.forEach { seg ->
                segBuf.putInt(seg.startMs)
                segBuf.putInt(seg.endMs)
                segBuf.putInt(seg.startFrameIdx)
                segBuf.putInt(seg.endFrameIdx)
                segBuf.put(seg.segmentType.code)
                segBuf.put((seg.energyLevel * 255).toInt().coerceIn(0, 255).toByte())
                repeat(14) { segBuf.put(0) } // reserved
            }
            segBuf.flip()
            raf.write(segBuf.array())

            // Write phrase entries
            val phraseBuf = ByteBuffer.allocate(PHRASE_ENTRY_SIZE * phrases.size).order(ByteOrder.LITTLE_ENDIAN)
            phrases.forEach { phrase ->
                phraseBuf.putInt(phrase.startMs)
                phraseBuf.putInt(phrase.endMs)
                phraseBuf.putInt(phrase.startFrameIdx)
                phraseBuf.putInt(phrase.endFrameIdx)
                phraseBuf.putShort(phrase.beatCountInPhrase.toShort())
                phraseBuf.put(phrase.phraseType.code)
                phraseBuf.put((phrase.difficulty * 255).toInt().coerceIn(0, 255).toByte())
                phraseBuf.putInt(0) // reserved
            }
            phraseBuf.flip()
            raf.write(phraseBuf.array())
        }
    }

    /**
     * Read a complete .beat file.
     */
    fun readFile(file: File): BeatFileData? {
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        return RandomAccessFile(file, "r").use { raf ->
            // Read header
            val headerBytes = ByteArray(HEADER_SIZE)
            raf.readFully(headerBytes)
            val hBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(4); hBuf.get(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) return null

            val version = hBuf.short
            hBuf.short // reserved
            val totalFrameCount = hBuf.int
            val fps = hBuf.short
            hBuf.short // reserved
            val videoDurationMs = hBuf.long
            val bpmTenths = hBuf.int
            val beatCount = hBuf.int
            val segmentCount = hBuf.int
            val phraseCount = hBuf.int
            val tsNum = hBuf.short
            val tsDen = hBuf.short
            val flags = hBuf.int

            val header = BeatFileHeader(
                version = version,
                totalFrameCount = totalFrameCount,
                fps = fps,
                videoDurationMs = videoDurationMs,
                bpmTenths = bpmTenths,
                beatCount = beatCount,
                segmentCount = segmentCount,
                phraseCount = phraseCount,
                timeSignatureNum = tsNum,
                timeSignatureDen = tsDen,
                flags = flags
            )

            // Read beats
            val beatBytes = ByteArray(BEAT_ENTRY_SIZE * beatCount)
            raf.readFully(beatBytes)
            val bBuf = ByteBuffer.wrap(beatBytes).order(ByteOrder.LITTLE_ENDIAN)
            val beats = (0 until beatCount).map {
                val timestampMs = bBuf.int
                val frameIndex = bBuf.int
                val strengthTenths = bBuf.short.toInt() and 0xFFFF
                val beatTypeCode = bBuf.get()
                val confidenceByte = bBuf.get().toInt() and 0xFF
                bBuf.int // reserved
                BeatEntry(
                    timestampMs = timestampMs,
                    frameIndex = frameIndex,
                    strength = strengthTenths / 1000f,
                    beatType = BeatType.fromCode(beatTypeCode),
                    confidence = confidenceByte / 255f
                )
            }

            // Read segments
            val segBytes = ByteArray(SEGMENT_ENTRY_SIZE * segmentCount)
            raf.readFully(segBytes)
            val sBuf = ByteBuffer.wrap(segBytes).order(ByteOrder.LITTLE_ENDIAN)
            val segments = (0 until segmentCount).map {
                val startMs = sBuf.int
                val endMs = sBuf.int
                val startFrameIdx = sBuf.int
                val endFrameIdx = sBuf.int
                val segTypeCode = sBuf.get()
                val energyByte = sBuf.get().toInt() and 0xFF
                repeat(14) { sBuf.get() } // reserved
                SegmentEntry(
                    startMs = startMs,
                    endMs = endMs,
                    startFrameIdx = startFrameIdx,
                    endFrameIdx = endFrameIdx,
                    segmentType = SegmentType.fromCode(segTypeCode),
                    energyLevel = energyByte / 255f
                )
            }

            // Read phrases
            val phraseBytes = ByteArray(PHRASE_ENTRY_SIZE * phraseCount)
            raf.readFully(phraseBytes)
            val pBuf = ByteBuffer.wrap(phraseBytes).order(ByteOrder.LITTLE_ENDIAN)
            val phrases = (0 until phraseCount).map {
                val startMs = pBuf.int
                val endMs = pBuf.int
                val startFrameIdx = pBuf.int
                val endFrameIdx = pBuf.int
                val beatCountInPhrase = pBuf.short.toInt() and 0xFFFF
                val phraseTypeCode = pBuf.get()
                val diffByte = pBuf.get().toInt() and 0xFF
                pBuf.int // reserved
                PhraseEntry(
                    startMs = startMs,
                    endMs = endMs,
                    startFrameIdx = startFrameIdx,
                    endFrameIdx = endFrameIdx,
                    beatCountInPhrase = beatCountInPhrase,
                    phraseType = PhraseType.fromCode(phraseTypeCode),
                    difficulty = diffByte / 255f
                )
            }

            BeatFileData(header, beats, segments, phrases)
        }
    }

    /**
     * Calculate frame index from timestamp (same formula as PoseFileFormat).
     */
    fun frameIndexForTimestamp(timestampMs: Int, fps: Int, totalFrames: Int): Int {
        return ((timestampMs.toLong() * fps) / 1000L).toInt().coerceIn(0, totalFrames - 1)
    }
}
