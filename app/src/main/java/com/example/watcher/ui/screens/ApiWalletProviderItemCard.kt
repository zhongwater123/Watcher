package com.example.watcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.repository.ProviderConnectivitySnapshot
import com.example.watcher.data.repository.ProviderConnectivityStatus

@Composable
internal fun ProviderItemCard(
    provider: LlmProviderEntity,
    isDefault: Boolean,
    isTesting: Boolean,
    connectivity: ProviderConnectivitySnapshot,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onExport: () -> Unit = {}
) {
    val defaultAccent = Color(0xFFB7791F)
    val defaultContainer = Color(0xFFFFF6E5)
    val usageProbes = remember(provider.id, provider.enabled, provider.endpoint, isDefault) {
        buildUsageProbes(provider = provider, isDefault = isDefault)
    }
    var usageExpanded by remember(provider.id) { mutableStateOf(false) }
    val effectiveStatus = if (isTesting) {
        ProviderConnectivityStatus.Untested
    } else {
        connectivity.status
    }
    val statusLabel = when {
        isTesting -> "检测中"
        connectivity.status == ProviderConnectivityStatus.Verified -> "已验证"
        connectivity.status == ProviderConnectivityStatus.Failed -> "失败"
        else -> "未检测"
    }
    val statusAccent = when {
        isTesting -> MaterialTheme.colorScheme.primary
        connectivity.status == ProviderConnectivityStatus.Verified -> Color(0xFF18794E)
        connectivity.status == ProviderConnectivityStatus.Failed -> MaterialTheme.colorScheme.error
        else -> Color(0xFF9A6700)
    }
    val statusContainer = when {
        isTesting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        connectivity.status == ProviderConnectivityStatus.Verified -> Color(0xFF18794E).copy(alpha = 0.12f)
        connectivity.status == ProviderConnectivityStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else -> Color(0xFF9A6700).copy(alpha = 0.12f)
    }
    val statusMessage = when {
        isTesting -> "正在用当前接口地址和 API 密钥测试连通性。"
        connectivity.status == ProviderConnectivityStatus.Verified -> buildString {
            append("连通性验证成功")
            connectivity.lastTestedAt?.let {
                append(" · ")
                append(formatDateTime(it))
            }
            connectivity.message?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(it)
            }
        }
        connectivity.status == ProviderConnectivityStatus.Failed -> buildString {
            append("最近一次测试失败")
            connectivity.lastTestedAt?.let {
                append(" · ")
                append(formatDateTime(it))
            }
            connectivity.message?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(it)
            }
        }
        else -> "建议先测试该供应商，再将其设为全局默认。"
    }
    val testButtonLabel = when {
        isTesting -> "测试中..."
        effectiveStatus == ProviderConnectivityStatus.Verified -> "重新测试"
        effectiveStatus == ProviderConnectivityStatus.Failed -> "重试测试"
        else -> "立即测试"
    }
    val usePrimaryTestButton = effectiveStatus != ProviderConnectivityStatus.Verified
    val cardContainerColor = if (isDefault) {
        defaultContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val cardBorder = if (isDefault) {
        BorderStroke(1.dp, defaultAccent.copy(alpha = 0.35f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor),
        border = cardBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isDefault) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = defaultAccent.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = defaultAccent
                            )
                            Text(
                                text = "默认",
                                style = MaterialTheme.typography.titleSmall,
                                color = defaultAccent
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = provider.name, style = MaterialTheme.typography.titleMedium)
                        if (!provider.enabled) {
                            SummaryBadge(text = "已禁用")
                        }
                    }
                    Text(
                        text = provider.modelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = provider.endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = maskApiKey(provider.apiKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusContainer
                    ) {
                        Text(
                            text = statusLabel,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusAccent
                        )
                    }
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.Share, contentDescription = "导出配置")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑供应商")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除供应商",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            UsageFootprintCard(
                probes = usageProbes,
                providerEnabled = provider.enabled,
                expanded = usageExpanded,
                onToggleExpanded = { usageExpanded = !usageExpanded }
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (usePrimaryTestButton) {
                        Button(
                            onClick = onTest,
                            enabled = !isTesting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (effectiveStatus == ProviderConnectivityStatus.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        ) {
                            Text(testButtonLabel)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onTest,
                            enabled = !isTesting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(testButtonLabel)
                        }
                    }
                    if (!isDefault) {
                        OutlinedButton(
                            onClick = onSetDefault,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("☆", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("设为默认", modifier = Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            color = defaultAccent.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = defaultAccent
                                )
                                Text(
                                    text = "默认",
                                    modifier = Modifier.padding(start = 8.dp),
                                    color = defaultAccent
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (provider.enabled) "可参与全局解析" else "已排除全局解析",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (provider.enabled) {
                                    "这个钱包可被选为应用级默认供应商。"
                                } else {
                                    "禁用后仍会保留该钱包，但不会被自动选中。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = provider.enabled,
                            onCheckedChange = onToggleEnabled
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageFootprintCard(
    probes: List<ProviderUsageProbe>,
    providerEnabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val activeCount = probes.count { it.state == ProviderUsageState.Active }
    val selectableCount = probes.count { it.state == ProviderUsageState.Selectable }
    val fallbackCount = probes.count { it.state == ProviderUsageState.Fallback }
    val summary = buildString {
        append("已生效 $activeCount 项")
        if (selectableCount > 0) append(" · 可切换 $selectableCount 项")
        if (fallbackCount > 0) append(" · 回退 $fallbackCount 项")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "使用足迹",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (providerEnabled) {
                            "当前覆盖范围：$summary"
                        } else {
                            "当前已禁用全局解析。$summary"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onToggleExpanded) {
                    Text(if (expanded) "收起详情" else "查看详情")
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            if (expanded) {
                Text(
                    text = if (providerEnabled) {
                        "该探针反映的是应用当前的接线情况。显示为“已生效”表示该功能现在就会使用这个供应商。"
                    } else {
                        "该供应商已禁用全局解析；在支持已保存 Brain 绑定的功能中，仍可能被手动选中。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                probes.forEach { probe ->
                    UsageProbeRow(probe)
                }
            }
        }
    }
}

@Composable
private fun UsageProbeRow(probe: ProviderUsageProbe) {
    val (accent, container, label) = when (probe.state) {
        ProviderUsageState.Active -> Triple(
            Color(0xFF18794E),
            Color(0xFF18794E).copy(alpha = 0.12f),
            "当前生效"
        )

        ProviderUsageState.Selectable -> Triple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            "可切换"
        )

        ProviderUsageState.Fallback -> Triple(
            Color(0xFF9A6700),
            Color(0xFF9A6700).copy(alpha = 0.12f),
            "回退"
        )

        ProviderUsageState.NotIntegrated -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surface,
            "未接入"
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = probe.feature,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = probe.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = container
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
    }
}
