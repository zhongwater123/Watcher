package com.example.watcher.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_stream_settings")
data class VideoStreamSettings(
    @PrimaryKey val id: Int = 1,
    val ipAddress: String = DEFAULT_DEVICE_IP,
    val port: Int = DEFAULT_PORT,
    val deviceId: String = "",
    val mdnsUrl: String = "",
    val resolution: String = DEFAULT_RESOLUTION,
    val quality: Int = 10,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val enabled: Boolean = false,
    val ledControlEnabled: Boolean = true,
    val ledAutoLightEnabled: Boolean = true,
    val ledTargetBrightness: Int = 100,
    val changeDetectionEnabled: Boolean = DEFAULT_CHANGE_DETECTION_ENABLED,
    val changeThresholdPercent: Int = DEFAULT_CHANGE_THRESHOLD_PERCENT,
    val notificationCooldownSeconds: Int = 20,
    val videoAnalysisStreamingEnabled: Boolean = false,
    val deviceProfile: String = DEVICE_PROFILE_ESP32,
    val preferredWifiSsid: String = ""
) {
    companion object {
        const val DEFAULT_DEVICE_IP = "192.168.4.1"
        const val DEFAULT_PORT = 80
        const val LEGACY_STREAM_PORT = 81
        const val DEFAULT_STREAM_PORT = 81
        const val HD_RESOLUTION = "HD"
        const val FALLBACK_RESOLUTION = "VGA"
        const val DEFAULT_RESOLUTION = FALLBACK_RESOLUTION
        const val HD_FRAMESIZE = 11
        const val VGA_FRAMESIZE = 8
        const val QVGA_FRAMESIZE = 5
        const val DEFAULT_CHANGE_DETECTION_ENABLED = false
        const val DEFAULT_CHANGE_THRESHOLD_PERCENT = 3
        const val DEVICE_PROFILE_ESP32 = "Esp32Camera"
        const val DEVICE_PROFILE_MJPEG_ONLY = "MjpegOnly"

        fun normalizeResolution(value: String): String {
            return when (value.trim().uppercase()) {
                "HD",
                "1280X720" -> HD_RESOLUTION
                "VGA",
                "640X480" -> FALLBACK_RESOLUTION
                "QVGA",
                "320X240" -> "QVGA"
                else -> DEFAULT_RESOLUTION
            }
        }

        fun framesizeValueFor(resolution: String): Int {
            return when (normalizeResolution(resolution)) {
                HD_RESOLUTION -> HD_FRAMESIZE
                FALLBACK_RESOLUTION -> VGA_FRAMESIZE
                "QVGA" -> QVGA_FRAMESIZE
                else -> HD_FRAMESIZE
            }
        }

        fun normalizeDeviceProfile(value: String): String {
            return when (value.trim()) {
                DEVICE_PROFILE_MJPEG_ONLY -> DEVICE_PROFILE_MJPEG_ONLY
                else -> DEVICE_PROFILE_ESP32
            }
        }
    }

    private val normalizedPort: Int
        get() = port.takeIf { it in 1..65535 } ?: DEFAULT_PORT

    private val hostWithPort: String
        get() = if (normalizedPort == DEFAULT_PORT) {
            ipAddress
        } else {
            "$ipAddress:$normalizedPort"
        }

    val streamUrl: String
        get() = "http://$ipAddress:$DEFAULT_STREAM_PORT/stream"

    val streamDisplayUrl: String
        get() = streamUrl

    val candidateStreamUrls: List<String>
        get() = listOf(streamUrl)

    val baseUrl: String
        get() = "http://$hostWithPort"

    val supportsDeviceControl: Boolean
        get() = deviceProfile != DEVICE_PROFILE_MJPEG_ONLY

    fun normalized(): VideoStreamSettings {
        return copy(
            ipAddress = ipAddress.trim().ifBlank { DEFAULT_DEVICE_IP },
            port = normalizedPort,
            deviceId = deviceId.trim(),
            mdnsUrl = mdnsUrl.trim(),
            resolution = normalizeResolution(resolution),
            quality = quality.coerceIn(4, 63),
            brightness = brightness.coerceIn(-2, 2),
            contrast = contrast.coerceIn(-2, 2),
            changeThresholdPercent = changeThresholdPercent.coerceIn(1, 100),
            notificationCooldownSeconds = notificationCooldownSeconds.coerceIn(5, 300),
            deviceProfile = normalizeDeviceProfile(deviceProfile),
            preferredWifiSsid = preferredWifiSsid
        )
    }
}
