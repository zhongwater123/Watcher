package com.example.watcher.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.watcher.data.model.ClassroomRecordingInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class ClassroomTestVideoImporter(
    private val context: Context
) {
    suspend fun import(uri: Uri): ClassroomRecordingInput.TestVideo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
            ?.takeIf(String::isNotBlank)
            ?: "classroom-test-video.mp4"

        val safeName = displayName.replace(Regex("""[^\w.-]"""), "_").ifBlank { "test-video.mp4" }
        val outputDir = File(context.filesDir, "classroom_test_inputs").apply { mkdirs() }
        val outputFile = File(outputDir, "${System.currentTimeMillis()}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Unable to open selected video.")

        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(outputFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } finally {
            retriever.release()
        }

        ClassroomRecordingInput.TestVideo(
            localPath = outputFile.absolutePath,
            displayName = displayName,
            durationMs = durationMs
        )
    }

    suspend fun cleanupCache(keepPath: String? = null): ClassroomTestVideoCleanupResult = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "classroom_test_inputs")
        if (!outputDir.exists() || !outputDir.isDirectory) {
            return@withContext ClassroomTestVideoCleanupResult()
        }
        val keepFilePath = keepPath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.safeComparablePath()
        var deletedCount = 0
        var bytesFreed = 0L
        outputDir.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.safeComparablePath() != keepFilePath }
            .forEach { file ->
                val length = file.length()
                if (file.delete()) {
                    deletedCount += 1
                    bytesFreed += length
                }
            }
        ClassroomTestVideoCleanupResult(deletedCount = deletedCount, bytesFreed = bytesFreed)
    }

    private fun File.safeComparablePath(): String {
        return runCatching { canonicalPath }.getOrElse { absolutePath }
    }
}

internal data class ClassroomTestVideoCleanupResult(
    val deletedCount: Int = 0,
    val bytesFreed: Long = 0L
)
