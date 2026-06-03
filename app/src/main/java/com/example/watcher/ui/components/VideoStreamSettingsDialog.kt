package com.example.watcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.R
import com.example.watcher.data.model.DeviceRuntimeInfo
import com.example.watcher.data.model.DeviceProvisionUiState
import com.example.watcher.data.model.DiscoveredStreamDevice
import com.example.watcher.data.model.DiscoveredStreamDeviceKind
import com.example.watcher.data.model.LEGACY_PROVISION_WIFI_SSID_MAX_BYTES
import com.example.watcher.data.model.StreamScanUiState
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.model.exceedsLegacyProvisionWifiSsidLimit
import com.example.watcher.data.model.normalizedProvisionWifiSsid
import com.example.watcher.data.model.provisionWifiSsidUtf8Length
import com.example.watcher.data.model.validateProvisionWifiPassword

@Composable
fun VideoStreamSettingsDialog(
    settings: VideoStreamSettings?,
    scanState: StreamScanUiState,
    provisionState: DeviceProvisionUiState,
    onDismiss: () -> Unit,
    onScanDevices: () -> Unit,
    onLoadDeviceInfo: () -> Unit,
    onScanProvisionWifi: () -> Unit,
    onSubmitProvisionWifi: (String, String) -> Unit,
    onClearProvisionedWifi: () -> Unit,
    onSave: (VideoStreamSettings) -> Unit
) {
    var ipAddress by remember { mutableStateOf(settings?.ipAddress ?: VideoStreamSettings.DEFAULT_DEVICE_IP) }
    var port by remember { mutableStateOf(settings?.port?.toString() ?: VideoStreamSettings.DEFAULT_PORT.toString()) }
    var deviceId by remember { mutableStateOf(settings?.deviceId.orEmpty()) }
    var mdnsUrl by remember { mutableStateOf(settings?.mdnsUrl.orEmpty()) }
    var resolution by remember { mutableStateOf(settings?.resolution ?: VideoStreamSettings.DEFAULT_RESOLUTION) }
    var qualityInput by remember { mutableStateOf(settings?.quality?.toString() ?: "10") }
    var ledControlEnabled by remember { mutableStateOf(settings?.ledControlEnabled ?: true) }
    var ledAutoLightEnabled by remember { mutableStateOf(settings?.ledAutoLightEnabled ?: true) }
    var ledTargetBrightness by remember { mutableStateOf(settings?.ledTargetBrightness?.toString() ?: "100") }
    var changeDetectionEnabled by remember {
        mutableStateOf(settings?.changeDetectionEnabled ?: VideoStreamSettings.DEFAULT_CHANGE_DETECTION_ENABLED)
    }
    var changeThresholdPercent by remember {
        mutableStateOf(
            settings?.changeThresholdPercent?.toString()
                ?: VideoStreamSettings.DEFAULT_CHANGE_THRESHOLD_PERCENT.toString()
        )
    }
    var notificationCooldownSeconds by remember {
        mutableStateOf(settings?.notificationCooldownSeconds?.toString() ?: "20")
    }
    var videoAnalysisStreamingEnabled by remember {
        mutableStateOf(settings?.videoAnalysisStreamingEnabled ?: false)
    }
    var deviceProfile by remember {
        mutableStateOf(settings?.deviceProfile ?: VideoStreamSettings.DEVICE_PROFILE_ESP32)
    }
    var wifiSsid by remember { mutableStateOf(settings?.preferredWifiSsid.orEmpty()) }
    var wifiPassword by remember { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(
        settings?.ipAddress,
        settings?.port,
        settings?.deviceId,
        settings?.mdnsUrl,
        settings?.resolution,
        settings?.quality,
        settings?.ledControlEnabled,
        settings?.ledAutoLightEnabled,
        settings?.ledTargetBrightness,
        settings?.changeDetectionEnabled,
        settings?.changeThresholdPercent,
        settings?.notificationCooldownSeconds,
        settings?.videoAnalysisStreamingEnabled,
        settings?.deviceProfile,
        settings?.preferredWifiSsid
    ) {
        settings?.let {
            ipAddress = it.ipAddress
            port = it.port.toString()
            deviceId = it.deviceId
            mdnsUrl = it.mdnsUrl
            resolution = it.resolution
            qualityInput = it.quality.toString()
            ledControlEnabled = it.ledControlEnabled
            ledAutoLightEnabled = it.ledAutoLightEnabled
            ledTargetBrightness = it.ledTargetBrightness.toString()
            changeDetectionEnabled = it.changeDetectionEnabled
            changeThresholdPercent = it.changeThresholdPercent.toString()
            notificationCooldownSeconds = it.notificationCooldownSeconds.toString()
            videoAnalysisStreamingEnabled = it.videoAnalysisStreamingEnabled
            deviceProfile = it.deviceProfile
            wifiSsid = it.preferredWifiSsid
        }
    }

    LaunchedEffect(Unit) {
        val isFirstSetup = settings == null || ipAddress == VideoStreamSettings.DEFAULT_DEVICE_IP
        if (isFirstSetup && !scanState.isScanning && scanState.devices.isEmpty()) {
            onScanDevices()
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)
    )
    val supportsDeviceControl = deviceProfile == VideoStreamSettings.DEVICE_PROFILE_ESP32
    val provisioningBusy = provisionState.isLoadingInfo ||
        provisionState.isScanningWifi ||
        provisionState.isSubmittingWifi ||
        provisionState.isClearingWifi ||
        provisionState.isWaitingForReconnect ||
        provisionState.isFindingProvisionedDevice
    val normalizedWifiSsid = normalizedProvisionWifiSsid(wifiSsid)
    val wifiSsidBytes = provisionWifiSsidUtf8Length(wifiSsid)
    val exceedsLegacySsidLimit = normalizedWifiSsid.isNotBlank() && exceedsLegacyProvisionWifiSsidLimit(wifiSsid)
    val wifiPasswordError = validateProvisionWifiPassword(wifiPassword)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "扫描并选择设备即可开始使用，更多选项在「高级设置」中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsGroup(title = "设备类型") {
                    DeviceProfileToggle(
                        currentProfile = deviceProfile,
                        onSelectProfile = { selected ->
                            deviceProfile = selected
                            if (selected == VideoStreamSettings.DEVICE_PROFILE_MJPEG_ONLY) {
                                ledControlEnabled = false
                                ledAutoLightEnabled = false
                            }
                        }
                    )
                    DeviceProfileHint(deviceProfile = deviceProfile)
                }

                SettingsGroup(title = "连接配置") {
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text(stringResource(R.string.settings_camera_host)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.settings_stream_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !supportsDeviceControl,
                        colors = textFieldColors
                    )
                    if (supportsDeviceControl) {
                        Text(
                            text = "ESP32-CAM 默认控制地址为 192.168.4.1，视频流固定使用 81 端口。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                ipAddress = VideoStreamSettings.DEFAULT_DEVICE_IP
                                port = VideoStreamSettings.DEFAULT_PORT.toString()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("恢复热点默认地址")
                        }
                    }
                    FilledTonalButton(
                        onClick = onScanDevices,
                        enabled = !scanState.isScanning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(if (scanState.isScanning) "正在扫描设备..." else "扫描局域网设备")
                    }
                    if (scanState.isScanning) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    scanState.statusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    scanState.errorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    scanState.devices.forEach { device ->
                        ScanResultCard(
                            device = device,
                            selected = isSelectedDevice(
                                currentHost = ipAddress,
                                currentPort = port.toIntOrNull(),
                                deviceProfile = deviceProfile,
                                device = device
                            ),
                            onSelect = {
                                val resolvedSettings = device.toVideoStreamSettings(settings ?: VideoStreamSettings())
                                ipAddress = resolvedSettings.ipAddress
                                port = resolvedSettings.port.toString()
                                deviceId = resolvedSettings.deviceId
                                mdnsUrl = resolvedSettings.mdnsUrl
                                deviceProfile = resolvedSettings.deviceProfile
                                if (device.kind == DiscoveredStreamDeviceKind.MjpegOnly) {
                                    ledControlEnabled = false
                                    ledAutoLightEnabled = false
                                }
                            }
                        )
                    }
                    if (!supportsDeviceControl) {
                        Text(
                            text = "通用 MJPEG 模式下，扫描结果只会用于视频流接入，不会启用设备控制。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (supportsDeviceControl) {
                    SettingsGroup(title = "设备配网") {
                        Text(
                            text = "首次使用时，先连接设备热点，再在这里读取状态、扫描 Wi-Fi 并写入路由器配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = onLoadDeviceInfo,
                            enabled = !provisioningBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(if (provisionState.isLoadingInfo) "正在读取设备状态..." else "读取设备状态")
                        }
                        FilledTonalButton(
                            onClick = onScanProvisionWifi,
                            enabled = !provisioningBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(if (provisionState.isScanningWifi) "正在扫描 Wi-Fi..." else "扫描设备周边 Wi-Fi")
                        }
                        if (provisioningBusy) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        provisionState.deviceInfo?.let { info ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "设备 ID：${info.deviceId.ifBlank { "--" }}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = if (info.isApMode) {
                                            "当前模式：热点配网（${info.apSsid}）"
                                        } else {
                                            "当前模式：已连接 ${info.staSsid.ifBlank { "外部 Wi-Fi" }}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "当前地址：${info.ip.ifBlank { VideoStreamSettings.DEFAULT_DEVICE_IP }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "视频流：${info.streamUrl.ifBlank { "http://${info.ip}:81/stream" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (info.hasWifiConnectFailure) {
                                        Text(
                                            text = buildDeviceProvisionFailureHint(info),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        provisionState.statusMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        provisionState.errorMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        OutlinedTextField(
                            value = wifiSsid,
                            onValueChange = { wifiSsid = it },
                            label = { Text("目标 Wi-Fi 名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = {
                                Text(
                                    if (exceedsLegacySsidLimit) {
                                        "当前为 $wifiSsidBytes 字节。App 已允许提交；但现有设备固件大概率仍按 $LEGACY_PROVISION_WIFI_SSID_MAX_BYTES 字节限制处理，硬件适配前可能会被拒绝。"
                                    } else {
                                        "当前 $wifiSsidBytes 字节。中文 Wi-Fi 名称可直接输入并提交。"
                                    }
                                )
                            },
                            colors = textFieldColors
                        )
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Wi-Fi 密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = wifiPasswordError != null,
                            supportingText = {
                                Text(
                                    wifiPasswordError
                                        ?: "留空表示开放网络；非空密码必须为 8 到 64 个字符。"
                                )
                            },
                            colors = textFieldColors
                        )
                        provisionState.wifiNetworks.forEach { network ->
                            WifiNetworkCard(
                                ssid = network.ssid,
                                rssi = network.rssi,
                                secure = network.secure,
                                selected = wifiSsid == network.ssid,
                                onSelect = { wifiSsid = network.ssid }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { onSubmitProvisionWifi(wifiSsid, wifiPassword) },
                                enabled = !provisioningBusy &&
                                    normalizedWifiSsid.isNotEmpty() &&
                                    wifiPasswordError == null,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    when {
                                        provisionState.isSubmittingWifi -> "提交中..."
                                        provisionState.isWaitingForReconnect -> "等待设备重启..."
                                        provisionState.isFindingProvisionedDevice -> "正在查找新 IP..."
                                        else -> "写入 Wi-Fi 并重启"
                                    }
                                )
                            }
                            TextButton(
                                onClick = onClearProvisionedWifi,
                                enabled = !provisioningBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (provisionState.isClearingWifi) "清除中..." else "清除已保存 Wi-Fi")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "高级设置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = advancedExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsGroup(title = "画质") {
                            Text(
                                text = stringResource(R.string.settings_stream_resolution),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ProfileChip(
                                    title = "VGA",
                                    selected = resolution == VideoStreamSettings.FALLBACK_RESOLUTION,
                                    modifier = Modifier.weight(1f),
                                    onClick = { resolution = VideoStreamSettings.FALLBACK_RESOLUTION }
                                )
                                ProfileChip(
                                    title = "HD",
                                    selected = resolution == VideoStreamSettings.HD_RESOLUTION,
                                    modifier = Modifier.weight(1f),
                                    onClick = { resolution = VideoStreamSettings.HD_RESOLUTION }
                                )
                            }
                            Text(
                                text = stringResource(R.string.settings_stream_resolution_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = qualityInput,
                                onValueChange = {
                                    val filtered = it.filter(Char::isDigit).take(2)
                                    val numeric = filtered.toIntOrNull()
                                    qualityInput = when {
                                        filtered.isEmpty() -> ""
                                        numeric == null -> qualityInput
                                        numeric > 63 -> "63"
                                        else -> filtered
                                    }
                                },
                                label = { Text(stringResource(R.string.settings_jpeg_quality)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = supportsDeviceControl,
                                supportingText = {
                                    Text(stringResource(R.string.settings_jpeg_quality_hint))
                                },
                                colors = textFieldColors
                            )
                        }

                        SettingsGroup(title = stringResource(R.string.settings_lighting_section)) {
                            SettingSwitch(
                                label = stringResource(R.string.settings_led_control),
                                checked = ledControlEnabled,
                                enabled = supportsDeviceControl,
                                onCheckedChange = { ledControlEnabled = it }
                            )
                            SettingSwitch(
                                label = stringResource(R.string.settings_auto_light),
                                checked = ledAutoLightEnabled,
                                enabled = supportsDeviceControl && ledControlEnabled,
                                onCheckedChange = { ledAutoLightEnabled = it }
                            )
                            OutlinedTextField(
                                value = ledTargetBrightness,
                                onValueChange = {
                                    val filtered = it.filter(Char::isDigit)
                                    ledTargetBrightness = (filtered.toIntOrNull() ?: 100).coerceIn(0, 255).toString()
                                },
                                label = { Text(stringResource(R.string.settings_target_brightness)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = supportsDeviceControl && ledControlEnabled && ledAutoLightEnabled,
                                colors = textFieldColors
                            )
                        }

                        SettingsGroup(title = stringResource(R.string.settings_execution_section)) {
                            SettingSwitch(
                                label = stringResource(R.string.settings_change_detection),
                                checked = changeDetectionEnabled,
                                onCheckedChange = { changeDetectionEnabled = it }
                            )
                            OutlinedTextField(
                                value = changeThresholdPercent,
                                onValueChange = {
                                    val filtered = it.filter(Char::isDigit)
                                    changeThresholdPercent = (
                                        filtered.toIntOrNull()
                                            ?: VideoStreamSettings.DEFAULT_CHANGE_THRESHOLD_PERCENT
                                        ).coerceIn(1, 100).toString()
                                },
                                label = { Text(stringResource(R.string.settings_change_threshold)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = changeDetectionEnabled,
                                colors = textFieldColors
                            )
                            OutlinedTextField(
                                value = notificationCooldownSeconds,
                                onValueChange = {
                                    val filtered = it.filter(Char::isDigit)
                                    notificationCooldownSeconds = (filtered.toIntOrNull() ?: 20).coerceIn(5, 300).toString()
                                },
                                label = { Text(stringResource(R.string.settings_alert_cooldown)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = textFieldColors
                            )
                            SettingSwitch(
                                label = "视频分析流式输出",
                                checked = videoAnalysisStreamingEnabled,
                                onCheckedChange = { videoAnalysisStreamingEnabled = it }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        VideoStreamSettings(
                            id = 1,
                            ipAddress = ipAddress.trim(),
                            port = port.toIntOrNull() ?: VideoStreamSettings.DEFAULT_PORT,
                            deviceId = deviceId,
                            mdnsUrl = mdnsUrl,
                            resolution = resolution,
                            quality = (qualityInput.toIntOrNull() ?: 10).coerceIn(4, 63),
                            brightness = settings?.brightness ?: 0,
                            contrast = settings?.contrast ?: 0,
                            enabled = settings?.enabled ?: false,
                            ledControlEnabled = ledControlEnabled,
                            ledAutoLightEnabled = ledAutoLightEnabled,
                            ledTargetBrightness = ledTargetBrightness.toIntOrNull() ?: 100,
                            changeDetectionEnabled = changeDetectionEnabled,
                            changeThresholdPercent = changeThresholdPercent.toIntOrNull()
                                ?: VideoStreamSettings.DEFAULT_CHANGE_THRESHOLD_PERCENT,
                            notificationCooldownSeconds = notificationCooldownSeconds.toIntOrNull() ?: 20,
                            videoAnalysisStreamingEnabled = videoAnalysisStreamingEnabled,
                            deviceProfile = deviceProfile,
                            preferredWifiSsid = wifiSsid
                        )
                    )
                },
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            content()
        }
    }
}

@Composable
private fun DeviceProfileToggle(
    currentProfile: String,
    onSelectProfile: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProfileChip(
            title = "ESP32-CAM",
            selected = currentProfile == VideoStreamSettings.DEVICE_PROFILE_ESP32,
            modifier = Modifier.weight(1f),
            onClick = { onSelectProfile(VideoStreamSettings.DEVICE_PROFILE_ESP32) }
        )
        ProfileChip(
            title = "通用 MJPEG",
            selected = currentProfile == VideoStreamSettings.DEVICE_PROFILE_MJPEG_ONLY,
            modifier = Modifier.weight(1f),
            onClick = { onSelectProfile(VideoStreamSettings.DEVICE_PROFILE_MJPEG_ONLY) }
        )
    }
}

@Composable
private fun ProfileChip(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    }
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeviceProfileHint(deviceProfile: String) {
    val message = if (deviceProfile == VideoStreamSettings.DEVICE_PROFILE_MJPEG_ONLY) {
        "按通用视频流设备处理时，仅连接视频流，不会下发补光和相机参数。"
    } else {
        "按 ESP32-CAM 处理时，可直接在这里完成热点配网并下发相机控制参数。"
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ScanResultCard(
    device: DiscoveredStreamDevice,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = device.host,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (device.kind) {
                            DiscoveredStreamDeviceKind.Esp32Camera -> "ESP32-CAM"
                            DiscoveredStreamDeviceKind.MjpegOnly -> "通用 MJPEG"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Button(
                    onClick = onSelect,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (selected) "已选中" else "使用此设备")
                }
            }
            Text(
                text = "视频流：${device.streamUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            device.statusUrl?.let { statusUrl ->
                Text(
                    text = "控制接口：$statusUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun WifiNetworkCard(
    ssid: String,
    rssi: Int,
    secure: Boolean,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
    }
    Surface(
        modifier = Modifier.clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = ssid,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "信号 ${rssi} dBm${if (secure) " | 已加密" else " | 开放网络"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (selected) "已选择" else "点按使用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

private fun isSelectedDevice(
    currentHost: String,
    currentPort: Int?,
    deviceProfile: String,
    device: DiscoveredStreamDevice
): Boolean {
    val currentProfile = VideoStreamSettings.normalizeDeviceProfile(deviceProfile)
    val expectedProfile = when (device.kind) {
        DiscoveredStreamDeviceKind.Esp32Camera -> VideoStreamSettings.DEVICE_PROFILE_ESP32
        DiscoveredStreamDeviceKind.MjpegOnly -> VideoStreamSettings.DEVICE_PROFILE_MJPEG_ONLY
    }
    return currentHost.trim() == device.host &&
        currentPort == device.preferredPort &&
        currentProfile == expectedProfile
}

private fun buildDeviceProvisionFailureHint(info: DeviceRuntimeInfo): String {
    return when (info.wifiConnectResult) {
        "wifi_unsupported_ssid_length" -> {
            "设备已保存该 Wi-Fi，但芯片/驱动不支持当前 SSID 字节长度。当前 ${info.wifiSsidBytes} 字节。"
        }
        "wifi_connect_failed" -> {
            "设备已保存该 Wi-Fi，但重连失败并回退热点。状态=${info.wifiConnectStatus}，断开原因=${info.wifiDisconnectReason}。"
        }
        else -> {
            "设备配网后未成功联网，结果=${info.wifiConnectResult}，状态=${info.wifiConnectStatus}。"
        }
    }
}
