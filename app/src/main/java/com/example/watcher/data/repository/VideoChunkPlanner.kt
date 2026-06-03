package com.example.watcher.data.repository

import java.io.File

class VideoChunkPlanner(
    private val maxChunkBytes: Long = DEFAULT_MAX_CHUNK_BYTES
) {
    fun planChunks(segmentFiles: List<File>): List<VideoChunkPlan> {
        val validFiles = segmentFiles
            .filter { it.exists() && it.length() > 0L }
            .sortedBy(::segmentIndexFromName)
        if (validFiles.isEmpty()) return emptyList()

        val chunks = mutableListOf<MutableList<File>>()
        var current = mutableListOf<File>()
        var currentBytes = 0L

        validFiles.forEach { file ->
            val fileBytes = file.length()
            val wouldExceed = current.isNotEmpty() && currentBytes + fileBytes > maxChunkBytes
            if (wouldExceed) {
                chunks += current
                current = mutableListOf()
                currentBytes = 0L
            }
            current += file
            currentBytes += fileBytes
        }
        if (current.isNotEmpty()) chunks += current

        return chunks.mapIndexed { index, files ->
            VideoChunkPlan(
                chunkIndex = index + 1,
                files = files,
                totalBytes = files.sumOf(File::length)
            )
        }
    }

    private fun segmentIndexFromName(file: File): Int {
        return Regex("""segment_(\d+)""")
            .find(file.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Int.MAX_VALUE
    }

    companion object {
        const val DEFAULT_MAX_CHUNK_BYTES: Long = 400L * 1024L * 1024L
    }
}

data class VideoChunkPlan(
    val chunkIndex: Int,
    val files: List<File>,
    val totalBytes: Long
)

data class VideoMergedChunkResult(
    val chunkIndex: Int,
    val filePath: String,
    val fileSizeBytes: Long,
    val arkFileId: String?,
    val evidenceJson: String,
    val summary: String,
    val errorMessage: String? = null
)
