package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.watcher.data.repository.AsrConfigSource
import com.example.watcher.data.repository.AsrConnectivityStatus
import com.example.watcher.data.repository.DEFAULT_DOUBAO_STREAMING_ASR_RESOURCE_ID
import com.example.watcher.data.repository.DEFAULT_VOLCENGINE_AST_RESOURCE_ID
import com.example.watcher.data.repository.VOLCENGINE_ASR_WS_URL
import com.example.watcher.data.repository.VOLCENGINE_AST_WS_URL
import com.example.watcher.data.repository.VolcengineAstAuthMode
import com.example.watcher.ui.viewmodel.AsrConfigDraft
import com.example.watcher.ui.viewmodel.AsrConfigUiState
import com.example.watcher.ui.viewmodel.AstConfigDraft
import com.example.watcher.ui.viewmodel.AstConfigUiState

@Composable
internal fun AsrConfigCard(
    state: AsrConfigUiState,
    onDraftChange: ((AsrConfigDraft) -> AsrConfigDraft) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit
) {
    val sourceLabel = when (state.source) {
        AsrConfigSource.Wallet -> "钱包配置"
        AsrConfigSource.LegacyRuntime -> "旧配置待迁移"
        AsrConfigSource.BuildConfigFallback -> "开发期 fallback"
        AsrConfigSource.Missing -> "未配置"
    }
    val statusLabel = when {
        state.isTesting -> "检测中"
        state.connectivity.status == AsrConnectivityStatus.Verified -> "已验证"
        state.connectivity.status == AsrConnectivityStatus.Failed -> "失败"
        else -> "未检测"
    }
    val statusAccent = when {
        state.isTesting -> MaterialTheme.colorScheme.primary
        state.connectivity.status == AsrConnectivityStatus.Verified -> Color(0xFF18794E)
        state.connectivity.status == AsrConnectivityStatus.Failed -> MaterialTheme.colorScheme.error
        else -> Color(0xFF9A6700)
    }
    val statusContainer = when {
        state.isTesting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        state.connectivity.status == AsrConnectivityStatus.Verified -> Color(0xFF18794E).copy(alpha = 0.12f)
        state.connectivity.status == AsrConnectivityStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else -> Color(0xFF9A6700).copy(alpha = 0.12f)
    }
    val helperText = when (state.source) {
        AsrConfigSource.Wallet ->
            "Live、Council 和视频分析的语音输入均使用这里保存的豆包流式语音识别配置。"
        AsrConfigSource.LegacyRuntime ->
            "检测到旧运行时语音配置。这些值不会直接用于当前火山 ASR；请确认后重新保存到 API 钱包。"
        AsrConfigSource.BuildConfigFallback ->
            "当前内容来自 local.properties。点击保存后，后续语音识别会优先走 API 钱包。"
        AsrConfigSource.Missing ->
            "默认按豆包大模型流式语音识别来配。通常只需要填 App Key 和 Access Key。"
    }
    val connectivityMessage = when {
        state.isTesting -> "正在建立会话并验证初始化请求。"
        !state.connectivity.message.isNullOrBlank() -> state.connectivity.message
        else -> "固定接入双向流式优化版：$VOLCENGINE_ASR_WS_URL"
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "豆包语音识别",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "独立于 LLM 供应商钱包，服务 Live、Council 和视频分析中的语音输入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryBadge(text = "来源：$sourceLabel", highlighted = state.source == AsrConfigSource.Wallet)
                SummaryBadge(text = statusLabel)
            }

            Text(
                text = helperText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "固定接入",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "接口：$VOLCENGINE_ASR_WS_URL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "默认资源：$DEFAULT_DOUBAO_STREAMING_ASR_RESOURCE_ID",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = state.draft.appKey,
                onValueChange = { value -> onDraftChange { it.copy(appKey = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("豆包 App Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = state.draft.accessKey,
                onValueChange = { value -> onDraftChange { it.copy(accessKey = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("豆包 Access Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = state.draft.resourceId,
                onValueChange = { value -> onDraftChange { it.copy(resourceId = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("资源 ID") },
                singleLine = true,
                supportingText = {
                    Text("默认已填小时版资源 ID；只有你使用并发版或其他套餐时才需要修改。")
                }
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = statusContainer
            ) {
                Text(
                    text = connectivityMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusAccent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !state.isSaving && !state.isTesting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("清空")
                }
                FilledTonalButton(
                    onClick = onTest,
                    enabled = !state.isSaving && !state.isTesting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (state.isTesting) "验证中..." else "验证配置")
                }
                Button(
                    onClick = onSave,
                    enabled = !state.isSaving && !state.isTesting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (state.isSaving) "保存中..." else "保存")
                }
            }
        }
    }
}

@Composable
internal fun AstConfigCard(
    state: AstConfigUiState,
    onDraftChange: ((AstConfigDraft) -> AstConfigDraft) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit
) {
    val sourceLabel = when (state.source) {
        AsrConfigSource.Wallet -> "钱包配置"
        AsrConfigSource.LegacyRuntime -> "旧配置待迁移"
        AsrConfigSource.BuildConfigFallback -> "开发期 fallback"
        AsrConfigSource.Missing -> "未配置"
    }
    val statusLabel = when {
        state.isTesting -> "检测中"
        state.connectivity.status == AsrConnectivityStatus.Verified -> "已验证"
        state.connectivity.status == AsrConnectivityStatus.Failed -> "失败"
        else -> "未检测"
    }
    val statusAccent = when {
        state.isTesting -> MaterialTheme.colorScheme.primary
        state.connectivity.status == AsrConnectivityStatus.Verified -> Color(0xFF18794E)
        state.connectivity.status == AsrConnectivityStatus.Failed -> MaterialTheme.colorScheme.error
        else -> Color(0xFF9A6700)
    }
    val statusContainer = when {
        state.isTesting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        state.connectivity.status == AsrConnectivityStatus.Verified -> Color(0xFF18794E).copy(alpha = 0.12f)
        state.connectivity.status == AsrConnectivityStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else -> Color(0xFF9A6700).copy(alpha = 0.12f)
    }
    val connectivityMessage = when {
        state.isTesting -> "正在建立 AST WebSocket 并等待 SessionStarted。"
        !state.connectivity.message.isNullOrBlank() -> state.connectivity.message
        else -> "固定接入 AST S2T：$VOLCENGINE_AST_WS_URL"
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "火山同传 AST",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "一键录课复杂语种模式使用。输出仍写入课堂实时字幕。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryBadge(text = "来源：$sourceLabel", highlighted = state.source == AsrConfigSource.Wallet)
                SummaryBadge(text = statusLabel)
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "固定接入",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "接口：$VOLCENGINE_AST_WS_URL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "默认资源：$DEFAULT_VOLCENGINE_AST_RESOURCE_ID",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "模式：S2T · source/target=zhen · 译文中文优先，原文兜底。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = state.draft.authMode == VolcengineAstAuthMode.ApiKey,
                    onClick = { onDraftChange { it.copy(authMode = VolcengineAstAuthMode.ApiKey) } },
                    label = { Text("X-Api-Key") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                FilterChip(
                    selected = state.draft.authMode == VolcengineAstAuthMode.AppKeyAccessKey,
                    onClick = { onDraftChange { it.copy(authMode = VolcengineAstAuthMode.AppKeyAccessKey) } },
                    label = { Text("AppKey + AccessKey") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            if (state.draft.authMode == VolcengineAstAuthMode.ApiKey) {
                OutlinedTextField(
                    value = state.draft.apiKey,
                    onValueChange = { value -> onDraftChange { it.copy(apiKey = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("AST API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            } else {
                OutlinedTextField(
                    value = state.draft.appKey,
                    onValueChange = { value -> onDraftChange { it.copy(appKey = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("AST App Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = state.draft.accessKey,
                    onValueChange = { value -> onDraftChange { it.copy(accessKey = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("AST Access Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            OutlinedTextField(
                value = state.draft.resourceId,
                onValueChange = { value -> onDraftChange { it.copy(resourceId = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("资源 ID") },
                singleLine = true,
                supportingText = {
                    Text("默认使用 AST S2T 服务资源 ID；如你的控制台资源不同再修改。")
                }
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = statusContainer
            ) {
                Text(
                    text = connectivityMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusAccent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !state.isSaving && !state.isTesting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("清空")
                }
                FilledTonalButton(
                    onClick = onTest,
                    enabled = !state.isSaving && !state.isTesting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (state.isTesting) "验证中..." else "验证配置")
                }
                Button(
                    onClick = onSave,
                    enabled = !state.isSaving && !state.isTesting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (state.isSaving) "保存中..." else "保存")
                }
            }
        }
    }
}
