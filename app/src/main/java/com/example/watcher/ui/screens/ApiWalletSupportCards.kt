package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.watcher.data.repository.WalletShareManager
import com.example.watcher.ui.viewmodel.ApiWalletUiState

@Composable
internal fun WalletSummaryCard(uiState: ApiWalletUiState) {
    val defaultProvider = uiState.defaultProvider

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "当前全局供应商",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = defaultProvider?.name ?: "尚未选择默认供应商",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = if (defaultProvider != null) {
                        "${defaultProvider.modelName} / ${defaultProvider.endpoint}"
                    } else if (uiState.arkFallbackAvailable) {
                        "当前未选中已保存的钱包项，应用仍可回退到本地 API_KEY。"
                    } else {
                        "当前既没有选中已保存的钱包项，也没有可用的本地回退配置。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Text(
            text = "供 Agent、Brain 和其他全局 LLM 请求统一使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = buildString {
                append("已保存 ${uiState.providers.size} 个")
                append(" · ")
                append("已启用 ${uiState.providers.count { it.enabled }} 个")
                if (uiState.arkFallbackAvailable) {
                    append(" · ")
                    append("本地回退可用")
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun EmptyWalletCard(arkFallbackAvailable: Boolean) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("还没有已保存的供应商", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (arkFallbackAvailable) {
                    "你仍可依赖本地 API_KEY 回退运行，但保存钱包条目后，全局 LLM 来源会更明确且可复用。"
                } else {
                    "请至少创建一个供应商，让应用拥有可用的全局 LLM 钱包条目。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SectionTitle(
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SummaryBadge(
    text: String,
    highlighted: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
internal fun WalletImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var importText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
        title = { Text("导入配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "粘贴其他人分享的 API 钱包配置 JSON，即可一键导入供应商和语音识别配置。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = importText,
                    onValueChange = {
                        importText = it
                        errorMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text("在此粘贴 JSON 配置...") },
                    maxLines = 10
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        clipboardManager.getText()?.let {
                            importText = it.text
                            errorMessage = null
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("粘贴")
                    }
                }
                if (importText.isNotBlank() && !WalletShareManager.canImport(importText)) {
                    Text(
                        text = "格式无法识别，请确认是完整的 Watcher 钱包配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(importText) },
                enabled = importText.isNotBlank() && WalletShareManager.canImport(importText)
            ) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
internal fun WalletExportWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("安全提示") },
        text = {
            Text("导出的配置包含您的 API 密钥和语音识别凭据（明文）。请仅通过可信渠道分享给信任的人，切勿在公开平台发布。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("我已知晓风险，确认导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
