package com.example.watcher.ui.viewmodel

import com.example.watcher.data.local.VideoStreamSettingsDao
import com.example.watcher.data.model.DeviceProvisionUiState
import com.example.watcher.data.model.DeviceRuntimeInfo
import com.example.watcher.data.model.DiscoveredStreamDevice
import com.example.watcher.data.model.DiscoveredStreamDeviceKind
import com.example.watcher.data.model.StreamScanUiState
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.repository.DeviceProvisionCoordinator
import com.example.watcher.data.repository.LanStreamScanner
import com.example.watcher.data.repository.ProvisionRediscoveryMode
import com.example.watcher.data.repository.StreamDeviceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles device provisioning, LAN scanning, stream settings, and settings migrations.
 * Extracted from IntentViewModel to reduce its size.
 */
internal class DeviceDelegate(
    private val scope: CoroutineScope,
    private val settingsDao: VideoStreamSettingsDao,
    private val lanStreamScanner: LanStreamScanner,
    private val streamDeviceCoordinator: StreamDeviceCoordinator,
    private val migrationPreferences: android.content.SharedPreferences,
    private val onReconnectStream: () -> Unit
) {
    companion object {
        private const val PROVISIONING_RESTART_GRACE_PERIOD_MS = 2_500L
        private const val PROVISIONING_REDISCOVERY_TIMEOUT_MS = 90_000L
        private const val PROVISIONING_REDISCOVERY_RETRY_MS = 3_000L
        private const val KEY_CHANGE_DETECTION_DEFAULTS_MIGRATED = "change_detection_defaults_v1"
        private const val KEY_RESOLUTION_DEFAULTS_MIGRATED = "resolution_defaults_v1"
        private const val RUNTIME_REDISCOVERY_COOLDOWN_MS = 30_000L
    }

    private val _streamScanUiState = MutableStateFlow(StreamScanUiState())
    private val _deviceProvisionUiState = MutableStateFlow(DeviceProvisionUiState())
    private val _settingsNotice = MutableStateFlow<String?>(null)

    val streamScanUiState: StateFlow<StreamScanUiState> = _streamScanUiState.asStateFlow()
    val deviceProvisionUiState: StateFlow<DeviceProvisionUiState> = _deviceProvisionUiState.asStateFlow()
    val settingsNotice: StateFlow<String?> = _settingsNotice.asStateFlow()

    private var streamScanJob: Job? = null
    private var deviceProvisionJob: Job? = null
    private var runtimeRediscoveryJob: Job? = null
    private var lastRuntimeRediscoveryAttemptAt: Long = 0L
    private var lastAppliedDeviceSettings: VideoStreamSettings? = null

    fun consumeSettingsNotice() { _settingsNotice.value = null }

    // ── Stream settings ──────────────────────────────────────────

    fun saveVideoStreamSettings(settings: VideoStreamSettings) {
        scope.launch {
            val normalized = settings.normalized()
            val previous = settingsDao.getSettingsSync()?.normalized()
            if (normalized.deviceProfile == VideoStreamSettings.DEVICE_PROFILE_MJPEG_ONLY) {
                _settingsNotice.value = "Generic MJPEG mode skips light and camera control commands."
            }
            settingsDao.insert(normalized)
            if (normalized.requiresStreamReconnectComparedTo(previous)) {
                onReconnectStream()
            }
        }
    }

    fun initializeVideoSettings() {
        scope.launch {
            val currentSettings = settingsDao.getSettingsSync()
            if (currentSettings != null) {
                migrateChangeDetectionDefaultsIfNeeded(currentSettings)
                migrateResolutionDefaultsIfNeeded(currentSettings)
            }
        }
    }

    suspend fun applySettings(settings: VideoStreamSettings) {
        val normalized = settings.normalized()
        if (!normalized.requiresStreamReconnectComparedTo(lastAppliedDeviceSettings)) {
            lastAppliedDeviceSettings = normalized
            return
        }
        runCatching { streamDeviceCoordinator.applySettings(normalized) }
            .onSuccess { outcome ->
                lastAppliedDeviceSettings = normalized
                outcome.persistedSettings?.let { settingsDao.insert(it) }
                if (!outcome.notice.isNullOrBlank()) {
                    _settingsNotice.value = outcome.notice
                }
            }
            .onFailure { error ->
                if (!isDeviceProvisionFlowBusy()) {
                    _settingsNotice.value = error.message ?: "Device connection failed."
                }
            }
    }

    // ── LAN device scan ──────────────────────────────────────────

    fun scanVideoStreamDevices() {
        streamScanJob?.cancel()
        streamScanJob = scope.launch {
            val currentSettings = settingsDao.getSettingsSync() ?: VideoStreamSettings()
            val discoveredDevices = linkedMapOf<String, DiscoveredStreamDevice>()
            _streamScanUiState.value = StreamScanUiState(
                isScanning = true,
                statusMessage = "Scanning the current LAN for video devices..."
            )
            try {
                val summary = lanStreamScanner.scan(preferredPort = currentSettings.port) { device ->
                    discoveredDevices[device.host] = device
                    _streamScanUiState.value = StreamScanUiState(
                        isScanning = true,
                        devices = sortDiscoveredDevices(discoveredDevices.values.toList()),
                        statusMessage = "Scanning... ${discoveredDevices.size} candidates found."
                    )
                }
                val devices = sortDiscoveredDevices(discoveredDevices.values.toList())
                _streamScanUiState.value = StreamScanUiState(
                    isScanning = false,
                    devices = devices,
                    statusMessage = if (devices.isEmpty()) {
                        "No video stream was found on ${summary.subnetLabel}. You can still enter an address manually."
                    } else {
                        "Found ${devices.size} candidates on ${summary.subnetLabel}. Pick one and save settings."
                    }
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _streamScanUiState.value = StreamScanUiState(
                    isScanning = false,
                    errorMessage = error.message ?: "Failed to scan for devices. Try again later."
                )
            }
        }
    }

    fun clearStreamDeviceScan() {
        streamScanJob?.cancel()
        streamScanJob = null
        _streamScanUiState.value = StreamScanUiState()
    }

    fun recoverProvisionedDeviceAfterRuntimeDisconnect() {
        if (runtimeRediscoveryJob?.isActive == true || isDeviceProvisionFlowBusy()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRuntimeRediscoveryAttemptAt < RUNTIME_REDISCOVERY_COOLDOWN_MS) {
            return
        }
        runtimeRediscoveryJob = scope.launch {
            lastRuntimeRediscoveryAttemptAt = System.currentTimeMillis()
            val currentSettings = settingsDao.getSettingsSync()?.normalized() ?: return@launch
            if (
                currentSettings.deviceProfile != VideoStreamSettings.DEVICE_PROFILE_ESP32 ||
                !currentSettings.supportsDeviceControl
            ) {
                return@launch
            }
            if (currentSettings.deviceId.isBlank() && currentSettings.mdnsUrl.isBlank()) {
                _settingsNotice.value =
                    "The saved camera has no device identity yet. Open device settings and scan once before automatic recovery can work."
                return@launch
            }
            _settingsNotice.value = "Detected a network change. Looking for the camera's new LAN address..."
            val rediscovery = runCatching {
                lanStreamScanner.rediscoverProvisionedDevice(
                    settings = currentSettings,
                    expectedDeviceId = currentSettings.deviceId.ifBlank { null },
                    knownMdnsUrl = currentSettings.mdnsUrl.ifBlank { null }
                )
            }.getOrElse { error ->
                _settingsNotice.value = error.message ?: "Failed to search for the camera on the current network."
                return@launch
            }
            val discoveredDevice = rediscovery.discoveredDevice
            if (discoveredDevice == null) {
                _settingsNotice.value = buildRuntimeRediscoveryFailureMessage(rediscovery.mode)
                return@launch
            }
            val updatedSettings = currentSettings.copy(
                ipAddress = discoveredDevice.host,
                port = discoveredDevice.preferredPort,
                deviceId = discoveredDevice.deviceId.ifBlank { currentSettings.deviceId },
                mdnsUrl = discoveredDevice.mdnsUrl.ifBlank { currentSettings.mdnsUrl }
            ).normalized()
            settingsDao.insert(updatedSettings)
            _settingsNotice.value =
                "Camera found at ${updatedSettings.ipAddress}. Reconnecting automatically."
            onReconnectStream()
        }
    }

    // ── Device provisioning ──────────────────────────────────────

    fun refreshDeviceProvisionInfo() {
        launchProvisionAction(
            loadingState = { it.copy(isLoadingInfo = true, isWaitingForReconnect = false, isFindingProvisionedDevice = false, errorMessage = null, statusMessage = "Loading device status...") }
        ) { coordinator ->
            val info = coordinator.fetchDeviceInfo()
            val currentSettings = settingsDao.getSettingsSync() ?: VideoStreamSettings()
            val updatedSettings = currentSettings.copy(
                ipAddress = info.ip.ifBlank { currentSettings.ipAddress },
                deviceId = info.deviceId.ifBlank { currentSettings.deviceId },
                mdnsUrl = info.mdnsUrl.ifBlank { currentSettings.mdnsUrl },
                preferredWifiSsid = info.staSsid.ifBlank { currentSettings.preferredWifiSsid }
            ).normalized()
            settingsDao.insert(updatedSettings)
            _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
                isLoadingInfo = false, deviceInfo = info,
                statusMessage = buildProvisionStatusMessage(info), errorMessage = null
            )
        }
    }

    fun scanProvisioningWifi() {
        launchProvisionAction(
            loadingState = { it.copy(isScanningWifi = true, isWaitingForReconnect = false, isFindingProvisionedDevice = false, errorMessage = null, statusMessage = "Scanning nearby Wi-Fi networks...") }
        ) { coordinator ->
            val networks = coordinator.scanWifiNetworks()
            _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
                isScanningWifi = false, wifiNetworks = networks,
                statusMessage = if (networks.isEmpty()) "No Wi-Fi networks were found by the device." else "Found ${networks.size} Wi-Fi networks. Tap one to fill it in.",
                errorMessage = null
            )
        }
    }

    fun submitProvisioningWifi(ssid: String, password: String) {
        launchProvisionAction(
            loadingState = { it.copy(isSubmittingWifi = true, isWaitingForReconnect = false, isFindingProvisionedDevice = false, errorMessage = null, statusMessage = "Saving Wi-Fi settings to the device...") }
        ) { coordinator ->
            val expectedDeviceId = _deviceProvisionUiState.value.deviceInfo?.deviceId
            val notice = coordinator.saveWifiConfig(ssid = ssid, password = password)
            _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
                isSubmittingWifi = false, isWaitingForReconnect = true,
                statusMessage = notice, errorMessage = null
            )
            _settingsNotice.value = notice
            awaitProvisionedDeviceReconnect(expectedDeviceId = expectedDeviceId, targetWifiSsid = ssid)
        }
    }

    fun clearProvisionedWifi() {
        launchProvisionAction(
            loadingState = { it.copy(isClearingWifi = true, isWaitingForReconnect = false, isFindingProvisionedDevice = false, errorMessage = null, statusMessage = "Clearing saved Wi-Fi from the device...") }
        ) { coordinator ->
            val notice = coordinator.clearWifiConfig()
            val currentSettings = settingsDao.getSettingsSync() ?: VideoStreamSettings()
            settingsDao.insert(
                currentSettings.copy(ipAddress = VideoStreamSettings.DEFAULT_DEVICE_IP, preferredWifiSsid = "").normalized()
            )
            _deviceProvisionUiState.value = DeviceProvisionUiState(statusMessage = notice)
            _settingsNotice.value = notice
        }
    }

    fun clearDeviceProvisionState() {
        deviceProvisionJob?.cancel()
        deviceProvisionJob = null
        _deviceProvisionUiState.value = DeviceProvisionUiState()
    }

    fun release() {
        streamScanJob?.cancel()
        deviceProvisionJob?.cancel()
        runtimeRediscoveryJob?.cancel()
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun launchProvisionAction(
        loadingState: (DeviceProvisionUiState) -> DeviceProvisionUiState,
        action: suspend (DeviceProvisionCoordinator) -> Unit
    ) {
        deviceProvisionJob?.cancel()
        deviceProvisionJob = scope.launch {
            _deviceProvisionUiState.value = loadingState(_deviceProvisionUiState.value)
            try {
                val settings = settingsDao.getSettingsSync() ?: VideoStreamSettings()
                action(DeviceProvisionCoordinator(settings.normalized().baseUrl))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
                    isLoadingInfo = false, isScanningWifi = false, isSubmittingWifi = false,
                    isClearingWifi = false, isWaitingForReconnect = false, isFindingProvisionedDevice = false,
                    errorMessage = error.message ?: "Device provisioning failed.", statusMessage = null
                )
            }
        }
    }

    private suspend fun awaitProvisionedDeviceReconnect(expectedDeviceId: String?, targetWifiSsid: String) {
        delay(PROVISIONING_RESTART_GRACE_PERIOD_MS)
        _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
            isWaitingForReconnect = false, isFindingProvisionedDevice = true,
            statusMessage = "The device is restarting. Looking for its new IP automatically...",
            errorMessage = null
        )
        var lastRediscoveryMode = ProvisionRediscoveryMode.NoCandidateLan
        val outcome = withTimeoutOrNull(PROVISIONING_REDISCOVERY_TIMEOUT_MS) {
            while (true) {
                val currentSettings = settingsDao.getSettingsSync() ?: VideoStreamSettings()
                val directInfo = runCatching {
                    DeviceProvisionCoordinator(currentSettings.normalized().baseUrl).fetchDeviceInfo()
                }.getOrNull()
                when {
                    directInfo?.isStaMode == true -> return@withTimeoutOrNull ProvisionReconnectOutcome(
                        discoveredDevice = directInfo.toDiscoveredDevice(currentSettings.normalized()), deviceInfo = directInfo
                    )
                    directInfo?.isProvisioningFailureFallback == true -> return@withTimeoutOrNull ProvisionReconnectOutcome(
                        deviceInfo = directInfo, failureMessage = buildProvisionFailureMessage(directInfo)
                    )
                }
                val knownMdnsUrl = _deviceProvisionUiState.value.deviceInfo?.mdnsUrl
                val rediscovery = lanStreamScanner.rediscoverProvisionedDevice(
                    settings = currentSettings, expectedDeviceId = expectedDeviceId, knownMdnsUrl = knownMdnsUrl
                )
                lastRediscoveryMode = rediscovery.mode
                val found = rediscovery.discoveredDevice
                if (found != null) {
                    val refreshedInfo = runCatching { DeviceProvisionCoordinator(found.toBaseUrl()).fetchDeviceInfo() }.getOrNull()
                    when {
                        refreshedInfo?.isStaMode == true -> return@withTimeoutOrNull ProvisionReconnectOutcome(discoveredDevice = found, deviceInfo = refreshedInfo)
                        refreshedInfo?.isProvisioningFailureFallback == true -> return@withTimeoutOrNull ProvisionReconnectOutcome(discoveredDevice = found, deviceInfo = refreshedInfo, failureMessage = buildProvisionFailureMessage(refreshedInfo))
                        else -> return@withTimeoutOrNull ProvisionReconnectOutcome(discoveredDevice = found)
                    }
                }
                _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
                    statusMessage = buildProvisionRediscoveryStatusMessage(lastRediscoveryMode)
                )
                delay(PROVISIONING_REDISCOVERY_RETRY_MS)
            }
        } as ProvisionReconnectOutcome?

        if (outcome?.failureMessage != null) {
            _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
                isFindingProvisionedDevice = false,
                deviceInfo = outcome.deviceInfo ?: _deviceProvisionUiState.value.deviceInfo,
                errorMessage = outcome.failureMessage, statusMessage = null
            )
            return
        }
        val discoveredDevice = outcome?.discoveredDevice
        if (discoveredDevice == null) {
            _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
                isFindingProvisionedDevice = false,
                errorMessage = buildProvisionRediscoveryTimeoutMessage(lastRediscoveryMode),
                statusMessage = null
            )
            return
        }
        val updatedSettings = persistProvisionedDevice(discoveredDevice, targetWifiSsid)
        onReconnectStream()
        val refreshedInfo = outcome.deviceInfo ?: runCatching {
            DeviceProvisionCoordinator(updatedSettings.baseUrl).fetchDeviceInfo()
        }.getOrElse { discoveredDevice.toRuntimeInfo(targetWifiSsid) }
        _deviceProvisionUiState.value = _deviceProvisionUiState.value.copy(
            isFindingProvisionedDevice = false, deviceInfo = refreshedInfo,
            statusMessage = "Wi-Fi was saved and the device is now on ${refreshedInfo.staSsid.ifBlank { targetWifiSsid }}. New IP: ${refreshedInfo.ip}. Reconnecting automatically now.",
            errorMessage = null
        )
        _settingsNotice.value = "Device discovered at ${refreshedInfo.ip}. Stream reconnecting automatically."
    }

    private suspend fun persistProvisionedDevice(device: DiscoveredStreamDevice, targetWifiSsid: String): VideoStreamSettings {
        val currentSettings = settingsDao.getSettingsSync() ?: VideoStreamSettings()
        val updatedSettings = currentSettings.copy(
            ipAddress = device.host,
            port = device.preferredPort,
            deviceId = device.deviceId.ifBlank { currentSettings.deviceId },
            mdnsUrl = device.mdnsUrl.ifBlank { currentSettings.mdnsUrl },
            deviceProfile = VideoStreamSettings.DEVICE_PROFILE_ESP32,
            preferredWifiSsid = targetWifiSsid
        ).normalized()
        settingsDao.insert(updatedSettings)
        return updatedSettings
    }

    private fun isDeviceProvisionFlowBusy(): Boolean {
        val state = _deviceProvisionUiState.value
        return state.isLoadingInfo || state.isScanningWifi || state.isSubmittingWifi ||
            state.isClearingWifi || state.isWaitingForReconnect || state.isFindingProvisionedDevice
    }

    private suspend fun migrateChangeDetectionDefaultsIfNeeded(currentSettings: VideoStreamSettings) {
        if (migrationPreferences.getBoolean(KEY_CHANGE_DETECTION_DEFAULTS_MIGRATED, false)) return
        val migrated = currentSettings.copy(
            changeDetectionEnabled = VideoStreamSettings.DEFAULT_CHANGE_DETECTION_ENABLED,
            changeThresholdPercent = VideoStreamSettings.DEFAULT_CHANGE_THRESHOLD_PERCENT
        )
        if (migrated != currentSettings) settingsDao.insert(migrated)
        migrationPreferences.edit().putBoolean(KEY_CHANGE_DETECTION_DEFAULTS_MIGRATED, true).apply()
    }

    private suspend fun migrateResolutionDefaultsIfNeeded(currentSettings: VideoStreamSettings) {
        if (migrationPreferences.getBoolean(KEY_RESOLUTION_DEFAULTS_MIGRATED, false)) return
        val normalized = currentSettings.normalized()
        if (normalized != currentSettings) {
            settingsDao.insert(normalized)
        }
        migrationPreferences.edit().putBoolean(KEY_RESOLUTION_DEFAULTS_MIGRATED, true).apply()
    }

}

private data class ProvisionReconnectOutcome(
    val discoveredDevice: DiscoveredStreamDevice? = null,
    val deviceInfo: DeviceRuntimeInfo? = null,
    val failureMessage: String? = null
)

private fun buildProvisionStatusMessage(info: DeviceRuntimeInfo): String = when {
    info.isProvisioningFailureFallback -> buildProvisionFailureMessage(info)
    info.isApMode -> "Device is in hotspot provisioning mode. Submit target Wi-Fi first."
    else -> "Device is on ${info.staSsid.ifBlank { "external Wi-Fi" }} at ${info.ip}."
}

private fun buildProvisionFailureMessage(info: DeviceRuntimeInfo): String = when (info.wifiConnectResult) {
    "wifi_unsupported_ssid_length" -> "Wi-Fi was saved, but the device fell back to hotspot mode because the chip/driver does not support this SSID length (${info.wifiSsidBytes} bytes)."
    "wifi_connect_failed" -> "Wi-Fi was saved, but the device could not connect and fell back to hotspot mode. Disconnect reason: ${info.wifiDisconnectReason}."
    else -> "Wi-Fi was saved, but the device fell back to hotspot mode. Result: ${info.wifiConnectResult.ifBlank { "unknown" }}."
}

private fun buildProvisionRediscoveryStatusMessage(mode: ProvisionRediscoveryMode): String = when (mode) {
    ProvisionRediscoveryMode.NoCandidateLan ->
        "Waiting for a reachable LAN or hotspot subnet, then searching for the device's new IP..."
    ProvisionRediscoveryMode.SearchingKnownLan ->
        "Trying the device's previous IP and mDNS address first..."
    ProvisionRediscoveryMode.SearchingActiveLan ->
        "Searching the current LAN for the device's new IP..."
    ProvisionRediscoveryMode.SearchingHotspotSubnet ->
        "The device is joining the phone hotspot. Searching the hotspot subnet for its new IP..."
    ProvisionRediscoveryMode.SearchingOtherLan ->
        "Searching local private subnets for the device's new IP..."
}

private fun buildProvisionRediscoveryTimeoutMessage(mode: ProvisionRediscoveryMode): String = when (mode) {
    ProvisionRediscoveryMode.NoCandidateLan ->
        "Wi-Fi was sent to the device, but the app could not find any reachable LAN or hotspot subnet yet. Reconnect the phone to the target network or keep the hotspot enabled, then try reading device status again."
    ProvisionRediscoveryMode.SearchingHotspotSubnet ->
        "Wi-Fi was sent to the device, but its new IP was not found on the phone hotspot yet. Keep the hotspot enabled and try reading device status again in a moment."
    else ->
        "Wi-Fi was sent to the device, but its new IP was not found yet. Reconnect the phone to the target LAN and try reading device status again."
}

private fun buildRuntimeRediscoveryFailureMessage(mode: ProvisionRediscoveryMode): String = when (mode) {
    ProvisionRediscoveryMode.NoCandidateLan ->
        "The camera address may have changed, but no reachable hotspot or LAN subnet is available yet."
    ProvisionRediscoveryMode.SearchingHotspotSubnet ->
        "The camera address changed with the hotspot, but it was not found on the hotspot subnet yet."
    else ->
        "The camera address may have changed, but it was not found on the current LAN yet. You can rescan devices manually."
}

private fun DeviceRuntimeInfo.toDiscoveredDevice(settings: VideoStreamSettings): DiscoveredStreamDevice =
    DiscoveredStreamDevice(
        host = ip.ifBlank { settings.ipAddress },
        preferredPort = httpPort.takeIf { it in 1..65535 } ?: settings.port,
        streamPort = streamPort.takeIf { it in 1..65535 } ?: VideoStreamSettings.DEFAULT_STREAM_PORT,
        statusPort = httpPort.takeIf { it in 1..65535 } ?: settings.port,
        kind = DiscoveredStreamDeviceKind.Esp32Camera,
        deviceId = deviceId.ifBlank { settings.deviceId },
        mdnsUrl = mdnsUrl.ifBlank { settings.mdnsUrl }
    )

private fun DiscoveredStreamDevice.toBaseUrl(): String =
    if (preferredPort == VideoStreamSettings.DEFAULT_PORT) "http://$host" else "http://$host:$preferredPort"

private fun DiscoveredStreamDevice.toRuntimeInfo(targetWifiSsid: String): DeviceRuntimeInfo =
    DeviceRuntimeInfo(
        deviceId = deviceId, mode = "sta", staSsid = targetWifiSsid, ip = host,
        httpPort = preferredPort, streamPort = streamPort, mdnsUrl = mdnsUrl,
        mdnsActive = mdnsUrl.isNotBlank(), streamUrl = streamUrl
    )

private fun sortDiscoveredDevices(devices: List<DiscoveredStreamDevice>): List<DiscoveredStreamDevice> =
    devices.sortedWith(compareBy({ it.kind.name }, { it.host }))
