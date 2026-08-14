package com.example.watcher.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoParserTest {
    @Test
    fun parseDeviceInfoResponseRepairsMalformedModeField() {
        val payload = """
            {"device_id":"F835DA34E3EC","mode":"ap,"ap_ssid":"ESP32CAM-34E3EC","ap_ip":"192.168.4.1","wifi_configured":false,"sta_ssid":"","ip":"192.168.4.1","http_port":80,"stream_port":81,"discovery_port":32108,"mdns":"","mdns_active":false,"stream_url":"http://192.168.4.1:81/stream"}
        """.trimIndent()

        val parsed = parseDeviceInfoResponse(payload)

        assertEquals("F835DA34E3EC", parsed.device_id)
        assertEquals("ap", parsed.mode)
        assertEquals("ESP32CAM-34E3EC", parsed.ap_ssid)
        assertEquals("192.168.4.1", parsed.ip)
        assertFalse(parsed.wifi_configured)
    }

    @Test
    fun parseDeviceInfoResponseReadsLanDiscoveryFields() {
        val payload = """
            {
              "device_id": "F835DA34E3EC",
              "mode": "sta",
              "ap_ssid": "ESP32CAM-34E3EC",
              "ap_ip": "192.168.4.1",
              "wifi_configured": true,
              "sta_ssid": "HomeWiFi",
              "ip": "10.244.205.10",
              "http_port": 80,
              "stream_port": 81,
              "discovery_port": 32108,
              "mdns": "http://esp32cam-34e3ec.local",
              "mdns_active": true,
              "stream_url": "http://10.244.205.10:81/stream",
              "wifi_connect_result": "success",
              "wifi_connect_status": 3,
              "wifi_connect_esp_err": 0,
              "wifi_disconnect_reason": 0,
              "wifi_fallback_to_ap": false,
              "wifi_ssid_bytes": 8
            }
        """.trimIndent()

        val parsed = parseDeviceInfoResponse(payload)

        assertEquals("sta", parsed.mode)
        assertEquals("HomeWiFi", parsed.sta_ssid)
        assertEquals("10.244.205.10", parsed.ip)
        assertEquals(32108, parsed.discovery_port)
        assertTrue(parsed.mdns_active)
        assertEquals("http://esp32cam-34e3ec.local", parsed.mdns)
        assertEquals("success", parsed.wifi_connect_result)
        assertEquals(3, parsed.wifi_connect_status)
        assertEquals(8, parsed.wifi_ssid_bytes)
    }

    @Test
    fun parseDeviceInfoResponseReadsApFallbackAfterSavedWifiFailure() {
        val payload = """
            {
              "device_id": "70B5F204A7AC",
              "mode": "ap",
              "ap_ssid": "ESP32CAM-04A7AC",
              "ap_ip": "192.168.4.1",
              "wifi_configured": true,
              "sta_ssid": "HomeWiFi",
              "ip": "192.168.4.1",
              "http_port": 80,
              "stream_port": 81,
              "discovery_port": 32108,
              "wifi_connect_result": "wifi_connect_failed",
              "wifi_connect_status": 1,
              "wifi_disconnect_reason": 201,
              "wifi_fallback_to_ap": true
            }
        """.trimIndent()

        val parsed = parseDeviceInfoResponse(payload)

        assertEquals("70B5F204A7AC", parsed.device_id)
        assertEquals("ap", parsed.mode)
        assertTrue(parsed.wifi_configured)
        assertEquals("wifi_connect_failed", parsed.wifi_connect_result)
        assertEquals(1, parsed.wifi_connect_status)
        assertEquals(201, parsed.wifi_disconnect_reason)
        assertTrue(parsed.wifi_fallback_to_ap)
    }
}
