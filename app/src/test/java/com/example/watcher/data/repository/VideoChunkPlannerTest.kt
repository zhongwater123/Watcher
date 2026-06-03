package com.example.watcher.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoChunkPlannerTest {
    @Test
    fun keepsSmallSegmentsInSingleChunk() {
        val dir = createTempDir()
        try {
            val files = (1..12).map { index ->
                dir.segmentFile(index, sizeBytes = 25)
            }

            val chunks = VideoChunkPlanner(maxChunkBytes = 400).planChunks(files)

            assertEquals(1, chunks.size)
            assertEquals(300, chunks.single().totalBytes)
            assertEquals((1..12).toList(), chunks.single().files.map(::segmentIndex))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun splitsLargeSegmentSetUnderMaxChunkBytes() {
        val dir = createTempDir()
        try {
            val files = (1..6).map { index ->
                dir.segmentFile(index, sizeBytes = 180)
            }

            val chunks = VideoChunkPlanner(maxChunkBytes = 400).planChunks(files)

            assertEquals(3, chunks.size)
            chunks.forEach { chunk ->
                assertTrue(chunk.totalBytes <= 400)
                assertEquals(2, chunk.files.size)
            }
            assertEquals((1..6).toList(), chunks.flatMap { it.files }.map(::segmentIndex))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun File.segmentFile(index: Int, sizeBytes: Int): File {
        val file = File(this, "run_1_segment_$index.mp4")
        file.writeBytes(ByteArray(sizeBytes) { index.toByte() })
        return file
    }

    private fun segmentIndex(file: File): Int {
        return Regex("""segment_(\d+)""")
            .find(file.name)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("missing segment index")
    }
}
