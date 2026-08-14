package com.example.watcher.data.remote

import android.graphics.Bitmap
import android.util.Log
import com.example.watcher.data.model.normalizeLocalDeviceHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LcdPushClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    data class PushResult(val success: Boolean, val error: String? = null)

    suspend fun pushFrame(deviceIp: String, bitmap: Bitmap): PushResult = withContext(Dispatchers.IO) {
        val safeDeviceIp = normalizeLocalDeviceHost(deviceIp)
        try {
            val rgb565 = bitmapToRgb565(bitmap)
            val body = rgb565.toRequestBody(OCTET_STREAM)
            val request = Request.Builder()
                .url("http://$safeDeviceIp/lcd/push")
                .post(body)
                .build()
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string().orEmpty().take(100)
            response.close()
            if (code == 200) {
                PushResult(success = true)
            } else {
                PushResult(success = false, error = "HTTP $code: $responseBody")
            }
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "LCD push connect failed: ${e.message}")
            PushResult(success = false, error = "连接被拒绝 (${deviceIp})")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "LCD push timeout: ${e.message}")
            PushResult(success = false, error = "连接超时 (${deviceIp})")
        } catch (e: Exception) {
            Log.w(TAG, "LCD push failed: ${e.message}")
            PushResult(success = false, error = "${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    companion object {
        private const val TAG = "LcdPushClient"
        private const val LCD_WIDTH = 240
        private const val LCD_HEIGHT = 320
        private const val FRAME_SIZE = LCD_WIDTH * LCD_HEIGHT * 2 // 153600 bytes
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        fun bitmapToRgb565(source: Bitmap): ByteArray {
            // Rotate 90° clockwise then scale to LCD dimensions
            val matrix = android.graphics.Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            val scaled = if (rotated.width == LCD_WIDTH && rotated.height == LCD_HEIGHT) {
                rotated
            } else {
                Bitmap.createScaledBitmap(rotated, LCD_WIDTH, LCD_HEIGHT, true)
            }
            if (rotated !== scaled && rotated !== source) rotated.recycle()
            val pixels = IntArray(LCD_WIDTH * LCD_HEIGHT)
            scaled.getPixels(pixels, 0, LCD_WIDTH, 0, 0, LCD_WIDTH, LCD_HEIGHT)
            if (scaled !== source && scaled !== rotated) scaled.recycle()

            val buffer = ByteArray(FRAME_SIZE)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val rgb565 = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
                val offset = i * 2
                buffer[offset] = (rgb565 shr 8).toByte()
                buffer[offset + 1] = (rgb565 and 0xFF).toByte()
            }
            return buffer
        }
    }
}
