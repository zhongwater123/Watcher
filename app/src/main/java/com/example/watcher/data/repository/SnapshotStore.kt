package com.example.watcher.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnapshotStore(
    private val context: Context
) {
    fun save(bitmap: Bitmap): String? {
        return saveToGallery(
            bitmap = bitmap,
            prefix = "SNAPSHOT"
        )
    }

    fun save(
        bitmap: Bitmap,
        directory: String,
        prefix: String,
        quality: Int = 90
    ): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(context.getExternalFilesDir(directory), "${prefix}_$timestamp.jpg")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToGallery(
        bitmap: Bitmap,
        prefix: String,
        quality: Int = 90
    ): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${prefix}_$timestamp.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Watcher"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)
            }
            uri.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun importImage(
        inputStream: InputStream,
        directory: String,
        prefix: String,
        extension: String = "jpg"
    ): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val normalizedExtension = extension.trimStart('.').ifBlank { "jpg" }
            val file = File(
                context.getExternalFilesDir(directory),
                "${prefix}_$timestamp.$normalizedExtension"
            )
            file.parentFile?.mkdirs()
            inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun createFile(
        directory: String,
        fileName: String
    ): File? {
        return try {
            File(context.getExternalFilesDir(directory), fileName).also {
                it.parentFile?.mkdirs()
            }
        } catch (_: Exception) {
            null
        }
    }
}
