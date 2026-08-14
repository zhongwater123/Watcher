package com.example.watcher.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.example.watcher.data.model.IntentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class BitmapSource(
    val path: String?,
    val base64: String?
)

internal fun preferredBaselinePreviewSource(
    currentTask: IntentResult?,
    pendingBaselinePath: String?,
    pendingBaselineBase64: String?
): BitmapSource {
    return when {
        currentTask?.baselineImagePath != null -> BitmapSource(currentTask.baselineImagePath, null)
        currentTask?.baseFrameBase64 != null -> BitmapSource(null, currentTask.baseFrameBase64)
        pendingBaselinePath != null -> BitmapSource(pendingBaselinePath, null)
        pendingBaselineBase64 != null -> BitmapSource(null, pendingBaselineBase64)
        else -> BitmapSource(null, null)
    }
}

@Composable
internal fun rememberDecodedBitmap(
    path: String?,
    base64: String?
): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, path, base64) {
        value = null
        value = withContext(Dispatchers.IO) {
            path?.let(BitmapFactory::decodeFile)
                ?: base64?.let(::decodeBase64Bitmap)
        }
    }
    return bitmap
}

private fun decodeBase64Bitmap(base64: String): Bitmap? {
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
