package com.example.watcher.data.model

import java.net.URI

fun normalizeLocalDeviceHost(
    rawHost: String,
    fallbackHost: String = VideoStreamSettings.DEFAULT_DEVICE_IP
): String {
    val host = extractHost(rawHost).orEmpty()
    return if (isAllowedLocalDeviceHost(host)) host else fallbackHost
}

fun isAllowedLocalDeviceHost(rawHost: String): Boolean {
    val host = extractHost(rawHost).orEmpty().lowercase()
    if (host.isBlank()) return false
    if (host == "localhost" || host.endsWith(".local")) return true

    val octets = parseIpv4Octets(host) ?: return false
    return when (octets[0]) {
        10 -> true
        127 -> true
        169 -> octets[1] == 254
        172 -> octets[1] in 16..31
        192 -> octets[1] == 168
        else -> false
    }
}

private fun extractHost(rawHost: String): String? {
    val value = rawHost.trim()
    if (value.isBlank()) return null
    val candidate = if ("://" in value) value else "http://$value"
    val parsedHost = runCatching { URI(candidate).host }.getOrNull()
    return parsedHost?.trim()?.takeIf(String::isNotBlank) ?: value
}

private fun parseIpv4Octets(host: String): List<Int>? {
    val parts = host.split(".")
    if (parts.size != 4) return null
    return parts.map { part ->
        if (part.isBlank() || part.length > 3 || part.any { !it.isDigit() }) {
            return null
        }
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
}
