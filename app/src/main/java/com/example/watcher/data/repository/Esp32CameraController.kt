package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.remote.LedControlService
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class Esp32CameraController(baseUrl: String = "http://192.168.4.1/") {
    data class ApplyResult(
        val appliedSettings: VideoStreamSettings,
        val usedFallback: Boolean,
        val message: String? = null
    )

    private val tag = "Esp32CameraCtrl"
    private val cameraService = Retrofit.Builder()
        .baseUrl(normalizeBaseUrl(baseUrl))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LedControlService::class.java)

    suspend fun applyPreferredSettings(settings: VideoStreamSettings): ApplyResult {
        val normalized = settings.normalized()
        val preferredResolution = VideoStreamSettings.normalizeResolution(normalized.resolution)
        if (preferredResolution != VideoStreamSettings.HD_RESOLUTION) {
            applyExactSettings(normalized)
            return ApplyResult(appliedSettings = normalized, usedFallback = false)
        }

        return runCatching {
            applyExactSettings(normalized)
            ApplyResult(appliedSettings = normalized, usedFallback = false)
        }.getOrElse { error ->
            Log.w(tag, "HD apply failed, fallback to VGA", error)
            val fallbackSettings = normalized.copy(resolution = VideoStreamSettings.FALLBACK_RESOLUTION)
            applyExactSettings(fallbackSettings)
            ApplyResult(
                appliedSettings = fallbackSettings,
                usedFallback = true,
                message = error.message
            )
        }
    }

    private suspend fun applyExactSettings(settings: VideoStreamSettings) {
        val normalized = settings.normalized()
        val frameSize = VideoStreamSettings.framesizeValueFor(normalized.resolution)
        applyControl("framesize", frameSize)
        applyControl("quality", normalized.quality)
        applyControl("brightness", normalized.brightness)
        applyControl("contrast", normalized.contrast)

        val statusResponse = cameraService.getStatus()
        if (!statusResponse.isSuccessful) {
            throw IllegalStateException("设备状态读取失败：HTTP ${statusResponse.code()}")
        }

        val status = statusResponse.body()
            ?: throw IllegalStateException("Device status response was empty.")
        if (status.framesize != frameSize) {
            throw IllegalStateException(
                "设备参数校验失败：framesize expected=$frameSize actual=${status.framesize}"
            )
        }
    }

    private suspend fun applyControl(variable: String, value: Int) {
        val response = cameraService.setControl(variable = variable, value = value)
        if (!response.isSuccessful) {
            throw IllegalStateException("设备控制失败：$variable HTTP ${response.code()}")
        }
    }
}
