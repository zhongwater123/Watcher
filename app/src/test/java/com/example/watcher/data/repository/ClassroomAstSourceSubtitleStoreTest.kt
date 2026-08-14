package com.example.watcher.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClassroomAstSourceSubtitleStoreTest {
    @Test
    fun appendsAndLoadsFinalSourceSubtitles() {
        val root = createTempDir(prefix = "ast-source-store")
        val store = ClassroomAstSourceSubtitleStore(root)

        store.append(
            ClassroomAstSourceSubtitle(
                runId = 7,
                startMs = 1_000,
                endMs = 2_000,
                text = "hello world",
                isFinal = true,
                sequence = 12,
                logId = "log-1",
                createdAt = 100
            )
        )

        val subtitles = store.load(runId = 7)

        assertEquals(1, subtitles.size)
        assertEquals("hello world", subtitles.single().text)
        assertEquals(1_000L, subtitles.single().startMs)
        assertEquals(2_000L, subtitles.single().endMs)
    }

    @Test
    fun matchesAndConcatenatesOverlappingSourceFragments() {
        val root = createTempDir(prefix = "ast-source-store")
        val store = ClassroomAstSourceSubtitleStore(root)
        store.append(source(runId = 3, start = 1_000, end = 1_800, text = "one"))
        store.append(source(runId = 3, start = 1_850, end = 2_700, text = "two"))
        store.append(source(runId = 3, start = 4_000, end = 4_500, text = "far"))

        val sourceText = store.findSourceTextFor(
            subtitles = store.load(runId = 3),
            startMs = 1_200,
            endMs = 2_500
        )

        assertEquals("one two", sourceText)
    }

    @Test
    fun nearestSourceOutsideToleranceDoesNotMatch() {
        val subtitles = listOf(source(runId = 4, start = 10_000, end = 11_000, text = "too far"))

        val sourceText = ClassroomAstSourceSubtitleStore(File("unused")).findSourceTextFor(
            subtitles = subtitles,
            startMs = 1_000,
            endMs = 2_000
        )

        assertNull(sourceText)
    }

    @Test
    fun clearRunDeletesSidecarDirectory() {
        val root = createTempDir(prefix = "ast-source-store")
        val store = ClassroomAstSourceSubtitleStore(root)
        store.append(source(runId = 9, start = 0, end = 1_000, text = "cleanup"))

        store.clearRun(runId = 9)

        assertTrue(store.load(runId = 9).isEmpty())
    }

    private fun source(runId: Long, start: Long, end: Long, text: String): ClassroomAstSourceSubtitle {
        return ClassroomAstSourceSubtitle(
            runId = runId,
            startMs = start,
            endMs = end,
            text = text,
            isFinal = true,
            sequence = 1,
            logId = "log"
        )
    }
}
