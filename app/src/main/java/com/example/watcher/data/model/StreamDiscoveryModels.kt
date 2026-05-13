package com.example.watcher.data.model

enum class DiscoveredStreamDeviceKind {
    Esp32Camera,
    MjpegOnly
}

data class DiscoveredStreamDevice(
    val host: String,
    val preferredPort: Int,
    val streamPort: Int,
    val statusPort: Int? = null,
    val kind: DiscoveredStreamDeviceKind,
    val deviceId: String = "",
    val mdnsUrl: String = ""
) {
    val streamUrl: String
        get() = buildHttpUrl(host = host, port = streamPort, path = "/stream")

    val statusUrl: String?
        get() = statusPort?.let { buildHttpUrl(host = host, port = it, path = "/status") }

    fun toVideoStreamSettings(existing: VideoStreamSettings): VideoStreamSettings {
        val profile = when (kind) {
            DiscoveredStreamDeviceKind.Esp32Camera -> VideoStreamSettings.DEVICE_PROFILE_ESP32
            DiscoveredStreamDeviceKind.MjpegOnly -> VideoStreamSettings.DEVICE_PROFILE_MJPEG_ONLY
        }
        return existing.copy(
            ipAddress = host,
            port = preferredPort,
            deviceId = deviceId,
            mdnsUrl = mdnsUrl,
            deviceProfile = profile
        )
    }
}

data class StreamScanUiState(
    val isScanning: Boolean = false,
    val devices: List<DiscoveredStreamDevice> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private fun buildHttpUrl(host: String, port: Int, path: String): String {
    return if (port == VideoStreamSettings.DEFAULT_PORT) {
        "http://$host$path"
    } else {
        "http://$host:$port$path"
    }
}
