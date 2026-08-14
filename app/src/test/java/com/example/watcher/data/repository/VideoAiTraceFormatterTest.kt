package com.example.watcher.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAiTraceFormatterTest {
    @Test
    fun splitTextReassemblesLongContent() {
        val text = (1..250).joinToString(separator = "") { "chunk-$it;" }

        val chunks = VideoAiTraceFormatter.splitText(text, chunkSize = 37)

        assertEquals(text, chunks.joinToString(separator = ""))
        assertTrue(chunks.size > 1)
    }

    @Test
    fun formatLinesIncludePartHashLengthModelAndDuration() {
        val context = VideoAiTraceContext(
            traceId = "trace-1",
            runId = 42,
            taskId = 7,
            node = "VideoSegmentAnalyzer",
            segmentIndex = 3,
            model = "test-model",
            requestKind = "merged_video"
        )
        val text = "abcdefghijklmnopqrstuvwxyz"

        val lines = VideoAiTraceFormatter.formatLines(
            context = context,
            phase = "prompt",
            kind = "prompt",
            text = text,
            durationMs = 123,
            chunkSize = 10
        )

        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("trace=trace-1 run=42 node=VideoSegmentAnalyzer phase=prompt segment=3 chunk=0"))
        assertTrue(lines[0].contains("part=1/3 kind=prompt"))
        assertTrue(lines[0].contains("sha256=${VideoAiTraceFormatter.sha256(text)}"))
        assertTrue(lines[0].contains("length=${text.length} bytes=${text.toByteArray(Charsets.UTF_8).size} model=test-model durationMs=123"))
        assertTrue(lines[0].contains("data=abcdefghij"))
        assertTrue(lines[2].contains("part=3/3 kind=prompt"))
    }

    @Test
    fun formatLinesEscapeNewlinesSoEveryLogcatLineIsSelfContained() {
        val context = VideoAiTraceContext(
            traceId = "trace-1",
            runId = 42,
            node = "ClassroomNoteSynthesizer",
            model = "test-model"
        )
        val text = "第一行\n第二行"

        val lines = VideoAiTraceFormatter.formatLines(
            context = context,
            phase = "response",
            kind = "response",
            text = text,
            chunkSize = 50
        )

        assertEquals(1, lines.size)
        assertTrue(!lines[0].contains("\n"))
        assertTrue(lines[0].contains("data=第一行\\n第二行"))
    }

    @Test
    fun sha256IsStableForIntegrityChecks() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            VideoAiTraceFormatter.sha256("abc")
        )
    }
}
