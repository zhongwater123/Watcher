package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.watcher.data.gateway.GatewayRuntimeStatus
import com.example.watcher.ui.components.WatcherCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun GatewaySettingsCard(
    isRunning: Boolean,
    status: GatewayRuntimeStatus,
    port: Int,
    apiKey: String,
    localIp: String,
    onToggle: (Boolean) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val effectiveIp = status.localIp.ifBlank { localIp }
    val effectivePort = status.listeningPort ?: port
    val baseUrl = "http://$effectiveIp:$effectivePort"

    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Gateway API", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (isRunning) "运行中，局域网 Agent 可发现和调用" else "已关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Switch(
                    checked = isRunning,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoRow(label = "地址", value = baseUrl, onCopy = {
                        clipboard.setText(AnnotatedString(baseUrl))
                    })
                    InfoRow(label = "监听端口", value = effectivePort.toString())
                    InfoRow(label = "服务状态", value = if (status.isRunning) "running" else "stopped")
                    InfoRow(label = "API Key", value = maskGatewayApiKey(apiKey), onCopy = {
                        clipboard.setText(AnnotatedString(apiKey))
                    })
                    if (isRunning) {
                        InfoRow(label = "能力发现", value = "$baseUrl/api/capabilities")
                        InfoRow(label = "健康检查", value = "$baseUrl/api/health")
                        InfoRow(label = "mDNS 服务", value = "_watcher._tcp")
                    }
                    InfoRow(label = "启动时间", value = formatGatewayTimestamp(status.startedAt))
                    InfoRow(label = "最近请求", value = buildLastRequestSummary(status))
                    InfoRow(label = "最近错误", value = status.lastError ?: "无")
                }
            }

            if (isRunning) {
                Text(
                    "Gateway 面向同一局域网内已信任的 PC Agent。首次绑定建议使用手机确认流，API Key 仅作为兼容备用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun buildLastRequestSummary(status: GatewayRuntimeStatus): String {
    val method = status.lastRequestMethod
    val path = status.lastRequestPath
    val at = formatGatewayTimestamp(status.lastRequestAt)
    return if (method != null && path != null) {
        "$method $path @ $at"
    } else {
        "暂无"
    }
}

private fun formatGatewayTimestamp(timestamp: Long?): String {
    if (timestamp == null) return "暂无"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun maskGatewayApiKey(apiKey: String): String {
    if (apiKey.isBlank()) return "未生成"
    if (apiKey.length <= 8) return "*".repeat(apiKey.length)
    return "${apiKey.take(4)}****${apiKey.takeLast(4)}"
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onCopy != null) {
            TextButton(onClick = onCopy) {
                Text("复制", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
