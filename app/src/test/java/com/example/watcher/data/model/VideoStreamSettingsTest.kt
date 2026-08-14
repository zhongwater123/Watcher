package com.example.watcher.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStreamSettingsTest {
    @Test
    fun streamUrlUsesFixedEsp32StreamPort() {
        val settings = VideoStreamSettings(
            ipAddress = "192.168.4.1",
            port = 80
        )

        assertEquals("http://192.168.4.1:81/stream", settings.streamUrl)
        assertEquals(listOf("http://192.168.4.1:81/stream"), settings.candidateStreamUrls)
    }

    @Test
    fun deviceIpIsPreferredForProvisionedDeviceControlAndStream() {
        val settings = VideoStreamSettings(
            ipAddress = "10.20.30.40",
            port = 80,
            mdnsUrl = "http://esp32cam-device.local"
        )

        assertEquals("http://10.20.30.40", settings.baseUrl)
        assertEquals("http://10.20.30.40:81/stream", settings.streamUrl)
        assertEquals(listOf("http://10.20.30.40:81/stream"), settings.candidateStreamUrls)
    }

    @Test
    fun normalizedFallsBackToFixedDeviceIpAndKeepsPreferredWifiRaw() {
        val normalized = VideoStreamSettings(
            ipAddress = "   ",
            preferredWifiSsid = "  HomeWiFi  "
        ).normalized()

        assertEquals(VideoStreamSettings.DEFAULT_DEVICE_IP, normalized.ipAddress)
        assertEquals("  HomeWiFi  ", normalized.preferredWifiSsid)
    }

    @Test
    fun normalizedKeepsPrivateDeviceAddresses() {
        assertEquals("10.20.30.40", VideoStreamSettings(ipAddress = "10.20.30.40").normalized().ipAddress)
        assertEquals("172.16.1.8", VideoStreamSettings(ipAddress = "172.16.1.8").normalized().ipAddress)
        assertEquals("192.168.4.1", VideoStreamSettings(ipAddress = "192.168.4.1").normalized().ipAddress)
    }

    @Test
    fun normalizedRejectsPublicCleartextDeviceAddresses() {
        assertEquals(VideoStreamSettings.DEFAULT_DEVICE_IP, VideoStreamSettings(ipAddress = "8.8.8.8").normalized().ipAddress)
        assertEquals(VideoStreamSettings.DEFAULT_DEVICE_IP, VideoStreamSettings(ipAddress = "example.com").normalized().ipAddress)
    }

    @Test
    fun normalizeRotationDegreesWrapsToRightAngles() {
        assertEquals(0, VideoStreamSettings.normalizeRotationDegrees(0))
        assertEquals(90, VideoStreamSettings.normalizeRotationDegrees(90))
        assertEquals(180, VideoStreamSettings.normalizeRotationDegrees(180))
        assertEquals(270, VideoStreamSettings.normalizeRotationDegrees(270))
        assertEquals(270, VideoStreamSettings.normalizeRotationDegrees(-90))
        assertEquals(90, VideoStreamSettings.normalizeRotationDegrees(450))
    }

    @Test
    fun normalizedKeepsOnlySupportedFrameOrientationValues() {
        val normalized = VideoStreamSettings(
            rotationDegrees = 450,
            mirrorHorizontally = true
        ).normalized()

        assertEquals(90, normalized.rotationDegrees)
        assertTrue(normalized.mirrorHorizontally)
    }

    @Test
    fun streamReconnectIsNotRequiredForFrameOrientationOnlyChanges() {
        val base = VideoStreamSettings(
            ipAddress = "192.168.4.1",
            rotationDegrees = 0,
            mirrorHorizontally = false
        ).normalized()

        assertFalse(base.copy(rotationDegrees = 90).requiresStreamReconnectComparedTo(base))
        assertFalse(base.copy(mirrorHorizontally = true).requiresStreamReconnectComparedTo(base))
        assertTrue(base.copy(ipAddress = "192.168.4.2").requiresStreamReconnectComparedTo(base))
    }

    @Test
    fun streamAutoConnectRequiresPersistedSettings() {
        assertFalse(VideoStreamSettings.shouldAutoConnect(null))
        assertTrue(VideoStreamSettings.shouldAutoConnect(VideoStreamSettings()))
    }
}
