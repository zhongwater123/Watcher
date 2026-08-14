package com.example.watcher.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomPcmAudioConverterTest {
    @Test
    fun `toAst16kMono downsamples 48k mono pcm to 16k mono`() {
        val pcm = ByteArray(48 * 2) { index -> index.toByte() }
        val frame = ClassroomAudioFrame(
            sequence = 1L,
            pcm = pcm,
            sampleRate = 48_000,
            channelCount = 1,
            bitsPerSample = 16,
            capturedAtMs = 100L,
            relativeStartMs = 0L,
            durationMs = 1_000L
        )

        val converted = ClassroomPcmAudioConverter.toAst16kMono(frame)

        assertEquals(16_000, converted.sampleRate)
        assertEquals(1, converted.channelCount)
        assertEquals(16, converted.bitsPerSample)
        assertEquals(16 * 2, converted.pcm.size)
        assertTrue(converted.pcm.isNotEmpty())
    }
}
