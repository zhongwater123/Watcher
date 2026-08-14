package com.example.watcher.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomRealtimeAudioTest {
    @Test
    fun realtimeAudioQueueDropsOldestFrameWhenFull() {
        val queue = RealtimeAudioQueue(capacityFrames = 2)

        assertTrue(queue.offer(testFrame(sequence = 1)))
        assertTrue(queue.offer(testFrame(sequence = 2)))
        assertFalse(queue.offer(testFrame(sequence = 3)))

        val drained = queue.drain(maxFrames = 10)
        assertEquals(listOf(2L, 3L), drained.map { it.sequence })
        assertEquals(1, queue.snapshot().droppedFrameCount)
    }

    @Test
    fun transcriptMapperAppliesGlobalOffsetAndPreservesWordTimestamps() {
        val utterance = JSONObject(
            """
            {
              "text": "多态",
              "start_time": 1200,
              "end_time": 2200,
              "definite": true,
              "words": [
                {"text": "多", "start_time": 1200, "end_time": 1600},
                {"text": "态", "start_time": 1600, "end_time": 2200}
              ]
            }
            """.trimIndent()
        )

        val mapped = ClassroomAsrTranscriptMapper.fromUtteranceJson(
            runId = 9,
            segmentIndex = 1,
            utterance = utterance,
            globalOffsetMs = 30_000,
            source = "live_asr",
            asrLogId = "log-1"
        )

        assertEquals(31_200L, mapped.globalStartMs)
        assertEquals(32_200L, mapped.globalEndMs)
        assertEquals("多态", mapped.text)
        assertTrue(mapped.isFinal)
        assertEquals("log-1", mapped.asrLogId)
        assertEquals(2, mapped.words.size)
        assertEquals(31_600L, mapped.words[1].globalStartMs)
    }

    @Test
    fun realtimeAudioPacketizerAggregatesSmallFrames() {
        val packetizer = RealtimeAudioPacketizer(targetDurationMs = 200)
        val emitted = mutableListOf<ClassroomAudioFrame>()

        repeat(9) { index ->
            emitted += packetizer.add(testFrame(sequence = index + 1L, durationMs = 21))
        }
        assertTrue(emitted.isEmpty())

        emitted += packetizer.add(testFrame(sequence = 10, durationMs = 21))

        assertEquals(1, emitted.size)
        assertEquals(10L, emitted.single().sequence)
        assertEquals(210L, emitted.single().durationMs)
        assertEquals(10, emitted.single().pcm.size)
    }

    private fun testFrame(sequence: Long) = ClassroomAudioFrame(
        sequence = sequence,
        pcm = byteArrayOf(sequence.toByte()),
        sampleRate = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        capturedAtMs = 1_000 + sequence,
        relativeStartMs = sequence * 200,
        durationMs = 200
    )

    private fun testFrame(sequence: Long, durationMs: Long) = ClassroomAudioFrame(
        sequence = sequence,
        pcm = byteArrayOf(sequence.toByte()),
        sampleRate = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        capturedAtMs = 1_000 + sequence,
        relativeStartMs = sequence * 200,
        durationMs = durationMs
    )
}
