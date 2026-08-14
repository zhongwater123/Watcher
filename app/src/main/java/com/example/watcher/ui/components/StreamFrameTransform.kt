package com.example.watcher.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import com.example.watcher.data.model.VideoStreamSettings

internal object StreamFrameTransform {
    fun apply(
        bitmap: Bitmap,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean
    ): Bitmap {
        val normalizedRotation = VideoStreamSettings.normalizeRotationDegrees(rotationDegrees)
        if (normalizedRotation == 0 && !mirrorHorizontally) {
            return bitmap
        }

        val matrix = Matrix().apply {
            if (normalizedRotation != 0) {
                postRotate(normalizedRotation.toFloat())
            }
            if (mirrorHorizontally) {
                postScale(-1f, 1f)
            }
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
