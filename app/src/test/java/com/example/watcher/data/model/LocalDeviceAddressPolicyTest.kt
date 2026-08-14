package com.example.watcher.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDeviceAddressPolicyTest {
    @Test
    fun allowsPrivateLanAndDeviceLocalHosts() {
        assertTrue(isAllowedLocalDeviceHost("10.20.30.40"))
        assertTrue(isAllowedLocalDeviceHost("172.31.0.5"))
        assertTrue(isAllowedLocalDeviceHost("192.168.4.1"))
        assertTrue(isAllowedLocalDeviceHost("esp32cam-device.local"))
        assertTrue(isAllowedLocalDeviceHost("localhost"))
    }

    @Test
    fun rejectsPublicCleartextHosts() {
        assertFalse(isAllowedLocalDeviceHost("8.8.8.8"))
        assertFalse(isAllowedLocalDeviceHost("172.32.0.5"))
        assertFalse(isAllowedLocalDeviceHost("example.com"))
    }
}
