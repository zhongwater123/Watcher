package com.example.watcher.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRuntimeInfoTest {
    @Test
    fun apModeWithHardwareFallbackFlagIsProvisioningFailureFallback() {
        val info = DeviceRuntimeInfo(
            mode = "ap",
            apIp = "192.168.4.1",
            wifiConfigured = true,
            wifiConnectResult = "wifi_connect_failed",
            wifiConnectStatus = 1,
            wifiDisconnectReason = 201,
            wifiFallbackToAp = true
        )

        assertTrue(info.isProvisioningFailureFallback)
    }

    @Test
    fun apModeWithoutHardwareFallbackFlagRemainsNormalProvisioning() {
        val info = DeviceRuntimeInfo(
            mode = "ap",
            apIp = "192.168.4.1",
            wifiConfigured = true,
            wifiConnectResult = "wifi_connect_failed",
            wifiFallbackToAp = false
        )

        assertFalse(info.isProvisioningFailureFallback)
    }
}
