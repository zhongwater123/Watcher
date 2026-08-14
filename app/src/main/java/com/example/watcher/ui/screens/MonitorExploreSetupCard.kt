package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.baselineSourceLabel
import com.example.watcher.data.model.monitorModeLabel
import com.example.watcher.data.model.targetTriggerLabel
import com.example.watcher.ui.components.ActionRow
import com.example.watcher.ui.components.EmptyHint
import com.example.watcher.ui.components.FormField
import com.example.watcher.ui.components.StepBlock
import com.example.watcher.ui.components.StepProgressRow
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.formFieldColors
import com.example.watcher.ui.viewmodel.UiState

@Composable
internal fun MonitorGuideCard(
    uiState: UiState,
    requestText: TextFieldValue,
    isListening: Boolean,
    currentTask: IntentResult?,
    pendingBaselinePath: String?,
    pendingBaselineBase64: String?,
    title: String,
    requirement: String,
    interval: String,
    prompt: String,
    onTitleChange: (String) -> Unit,
    onRequirementChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onStartListening: () -> Unit,
    onAnalyze: () -> Unit,
    onSaveTask: (IntentResult) -> Unit,
    onRefreshBaseline: () -> Unit,
    onPickBaselineImage: () -> Unit,
    onCopyJson: () -> Unit,
    editedTask: IntentResult?
) {
    WatcherCard {
        val baselinePreviewSource = preferredBaselinePreviewSource(
            currentTask = currentTask,
            pendingBaselinePath = pendingBaselinePath,
            pendingBaselineBase64 = pendingBaselineBase64
        )
        val baselinePreview = rememberDecodedBitmap(
            path = baselinePreviewSource.path,
            base64 = baselinePreviewSource.base64
        )
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("新建监控任务", style = MaterialTheme.typography.titleLarge)
            StepProgressRow(
                steps = listOf(
                    StepState("输入需求", requestText.text.isNotBlank(), currentTask == null),
                    StepState("确认配置", currentTask != null, currentTask != null)
                )
            )

            StepBlock(number = 1, title = "描述你想监控什么") {
                OutlinedTextField(
                    value = requestText,
                    onValueChange = onRequestTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("监控需求") },
                    placeholder = { Text("例如：有人靠近床铺并停留超过 10 秒时提醒我。") },
                    colors = formFieldColors()
                )
                if (uiState is UiState.Error) {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPickBaselineImage,
                        enabled = uiState !is UiState.Loading,
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "上传图片",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                    FilledTonalButton(
                        onClick = onStartListening,
                        enabled = !isListening && uiState !is UiState.Loading,
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isListening) "正在录入…" else "语音输入",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                Button(
                    onClick = onAnalyze,
                    enabled = requestText.text.isNotBlank() && uiState !is UiState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Text(if (uiState is UiState.Loading) "正在生成任务规划…" else "生成任务规划")
                }
                Text(
                    text = "如果你要盯的是某个具体的人或物，先上传参考图，效果通常会更好。\n如果你只关心某类特征或动作，可以直接用文字描述。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                baselinePreview?.let {
                    FrameTile(
                        title = "待分析参考图片",
                        bitmap = it,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            StepBlock(number = 2, title = "检查并微调任务参数") {
                if (currentTask == null) {
                    EmptyHint(text = "先生成监控任务，这里才会解锁配置。")
                } else {
                    FormField(label = "任务标题", value = title, onValueChange = onTitleChange)
                    FormField(label = "监控目标", value = requirement, onValueChange = onRequirementChange)
                    FormField(
                        label = "巡检间隔（秒）",
                        value = interval,
                        onValueChange = onIntervalChange,
                        keyboardType = KeyboardType.Number
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("监控模式", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = monitorModeLabel(currentTask.monitorMode),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (currentTask.monitorMode == MonitorMode.ReferenceTarget) {
                                Text(
                                    text = targetTriggerLabel(currentTask.targetTrigger),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "基准图来源：${baselineSourceLabel(currentTask.baselineSource)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FormField(label = "提示词", value = prompt, onValueChange = onPromptChange, minLines = 4)
                    ActionRow(
                        primaryLabel = "保存任务",
                        onPrimaryClick = { editedTask?.let(onSaveTask) },
                        primaryEnabled = editedTask != null,
                        secondaryLabel = "刷新基准帧",
                        onSecondaryClick = onRefreshBaseline,
                        secondaryEnabled = true,
                        secondaryIcon = Icons.Default.Refresh
                    )
                    TextButton(onClick = onPickBaselineImage) {
                        Text("重新上传图片")
                    }
                    TextButton(onClick = onCopyJson) {
                        androidx.compose.material3.Icon(Icons.Default.ContentCopy, contentDescription = null)
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                        Text("复制 JSON")
                    }
                }
            }
        }
    }
}
