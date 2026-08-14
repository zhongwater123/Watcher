package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.ui.components.RoseFourLoader
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.formFieldColors
import com.example.watcher.ui.viewmodel.UiState
import com.example.watcher.ui.viewmodel.toConciseMonitorTask

internal enum class ConciseMonitorGuideState {
    Idle,
    ReferenceEditing,
    DirectEditing,
    Generated
}

@Composable
internal fun ConciseMonitorSetupCard(
    uiState: UiState,
    requestText: TextFieldValue,
    currentTask: IntentResult?,
    pendingBaselinePath: String?,
    pendingBaselineBase64: String?,
    monitorStatus: MonitorStatus,
    isPageFocused: Boolean,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onAnalyze: () -> Unit,
    onClearPendingBaselineImage: () -> Unit,
    onPickBaselineImage: () -> Unit,
    onSaveAndStartMonitoring: (IntentResult) -> Boolean,
    onGuideStateChange: (ConciseMonitorGuideState) -> Unit
) {
    var selectedPath by remember { mutableStateOf<ConciseMonitorSetupPath?>(null) }
    var awaitingGeneration by remember { mutableStateOf(false) }
    var sawGenerationLoading by remember { mutableStateOf(false) }
    var generatedTask by remember { mutableStateOf<IntentResult?>(null) }
    var startingTask by remember { mutableStateOf<IntentResult?>(null) }
    var summaryTask by remember { mutableStateOf<IntentResult?>(null) }
    val hasReferenceImage = pendingBaselinePath != null || pendingBaselineBase64 != null
    val baselinePreview = rememberDecodedBitmap(path = pendingBaselinePath, base64 = pendingBaselineBase64)

    LaunchedEffect(monitorStatus.isRunning, currentTask) {
        if (monitorStatus.isRunning && currentTask != null) {
            summaryTask = currentTask.toConciseMonitorTask()
            selectedPath = null
            generatedTask = null
            awaitingGeneration = false
            sawGenerationLoading = false
            startingTask = null
            onGuideStateChange(ConciseMonitorGuideState.Idle)
        }
    }

    LaunchedEffect(uiState, currentTask, awaitingGeneration, sawGenerationLoading) {
        if (!awaitingGeneration) return@LaunchedEffect
        when (uiState) {
            UiState.Loading -> sawGenerationLoading = true
            is UiState.Success -> if (sawGenerationLoading && currentTask != null) {
                generatedTask = currentTask.toConciseMonitorTask()
                awaitingGeneration = false
                sawGenerationLoading = false
                onGuideStateChange(ConciseMonitorGuideState.Generated)
            }
            is UiState.Error -> if (sawGenerationLoading) {
                awaitingGeneration = false
                sawGenerationLoading = false
            }
            UiState.Idle -> Unit
        }
    }

    summaryTask?.takeIf { monitorStatus.isRunning }?.let {
        ConciseMonitorRunningSummary(monitorStatus = monitorStatus)
        return
    }

    ConciseDynamicSetupFrame(
        selectedPath = selectedPath,
        isPageFocused = isPageFocused,
        onSelectPath = { path ->
            selectedPath = path
            generatedTask = null
            awaitingGeneration = false
            sawGenerationLoading = false
            startingTask = null
            onGuideStateChange(
                if (path == ConciseMonitorSetupPath.Reference) {
                    ConciseMonitorGuideState.ReferenceEditing
                } else {
                    ConciseMonitorGuideState.DirectEditing
                }
            )
        },
        onBack = {
            selectedPath = null
            generatedTask = null
            awaitingGeneration = false
            sawGenerationLoading = false
            startingTask = null
            onGuideStateChange(ConciseMonitorGuideState.Idle)
        }
    ) {
        when (selectedPath) {
            ConciseMonitorSetupPath.Reference -> {
                ConciseReferenceSetupContent(
                    requestText = requestText,
                    hasReferenceImage = hasReferenceImage,
                    baselinePreview = baselinePreview,
                    uiState = uiState,
                    generating = awaitingGeneration || uiState is UiState.Loading,
                    generatedTask = generatedTask,
                    starting = startingTask != null,
                    onRequestTextChange = onRequestTextChange,
                    onPickBaselineImage = onPickBaselineImage,
                    onGenerate = {
                        generatedTask = null
                        startingTask = null
                        awaitingGeneration = true
                        sawGenerationLoading = false
                        onAnalyze()
                    },
                    onStart = { task ->
                        if (onSaveAndStartMonitoring(task.toConciseMonitorTask())) {
                            startingTask = task.toConciseMonitorTask()
                        }
                    },
                    onReset = {
                        generatedTask = null
                        startingTask = null
                        awaitingGeneration = false
                        sawGenerationLoading = false
                        onGuideStateChange(ConciseMonitorGuideState.ReferenceEditing)
                    }
                )
            }

            ConciseMonitorSetupPath.Direct -> {
                ConciseDirectSetupContent(
                    requestText = requestText,
                    uiState = uiState,
                    generating = awaitingGeneration || uiState is UiState.Loading,
                    generatedTask = generatedTask,
                    starting = startingTask != null,
                    onRequestTextChange = onRequestTextChange,
                    onGenerate = {
                        onClearPendingBaselineImage()
                        generatedTask = null
                        startingTask = null
                        awaitingGeneration = true
                        sawGenerationLoading = false
                        onAnalyze()
                    },
                    onStart = { task ->
                        if (onSaveAndStartMonitoring(task.toConciseMonitorTask())) {
                            startingTask = task.toConciseMonitorTask()
                        }
                    },
                    onReset = {
                        generatedTask = null
                        startingTask = null
                        awaitingGeneration = false
                        sawGenerationLoading = false
                        onGuideStateChange(ConciseMonitorGuideState.DirectEditing)
                    }
                )
            }

            null -> Unit
        }
    }
}

@Composable
private fun ConciseDirectSetupContent(
    requestText: TextFieldValue,
    uiState: UiState,
    generating: Boolean,
    generatedTask: IntentResult?,
    starting: Boolean,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onGenerate: () -> Unit,
    onStart: (IntentResult) -> Unit,
    onReset: () -> Unit
) {
    if (generatedTask != null) {
        ConciseMonitorConfirmation(
            task = generatedTask,
            starting = starting,
            onStart = onStart,
            onReset = onReset
        )
        return
    }

    ConciseMonitorInput(
        requestText = requestText,
        label = "你想让Watcher看管什么？",
        placeholder = "告诉我你想看住什么",
        generating = generating,
        uiState = uiState,
        buttonEnabled = requestText.text.trim().isNotEmpty() && !generating,
        buttonLabel = "生成看管方案",
        onRequestTextChange = onRequestTextChange,
        onGenerate = onGenerate
    )
}

@Composable
private fun ConciseReferenceSetupContent(
    requestText: TextFieldValue,
    hasReferenceImage: Boolean,
    baselinePreview: Bitmap?,
    uiState: UiState,
    generating: Boolean,
    generatedTask: IntentResult?,
    starting: Boolean,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onPickBaselineImage: () -> Unit,
    onGenerate: () -> Unit,
    onStart: (IntentResult) -> Unit,
    onReset: () -> Unit
) {
    if (generatedTask != null) {
        ConciseMonitorConfirmation(
            task = generatedTask,
            referencePreview = baselinePreview,
            starting = starting,
            onStart = onStart,
            onReset = onReset
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clickable(enabled = !generating, onClick = onPickBaselineImage),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        ) {
            if (baselinePreview != null) {
                Image(
                    bitmap = baselinePreview.asImageBitmap(),
                    contentDescription = "参考图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hasReferenceImage) "参考图已选择" else "添加TA的照片\n能让Watcher更好的注意",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        ConciseMonitorInput(
            requestText = requestText,
            label = "你想让Watcher帮你留意什么？",
            placeholder = "图中的TA需要我看到就叫你？",
            generating = generating,
            uiState = uiState,
            buttonEnabled = requestText.text.trim().isNotEmpty() && hasReferenceImage && !generating,
            buttonLabel = "生成看管方案",
            onRequestTextChange = onRequestTextChange,
            onGenerate = onGenerate
        )
    }
}

@Composable
private fun ConciseMonitorInput(
    requestText: TextFieldValue,
    label: String,
    placeholder: String,
    generating: Boolean,
    uiState: UiState,
    buttonEnabled: Boolean,
    buttonLabel: String,
    onRequestTextChange: (TextFieldValue) -> Unit,
    onGenerate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = requestText,
            onValueChange = onRequestTextChange,
            enabled = !generating,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            },
            placeholder = {
                Text(
                    text = placeholder,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            },
            colors = formFieldColors()
        )
        if (uiState is UiState.Error) {
            Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = { if (buttonEnabled) onGenerate() },
            enabled = buttonEnabled,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
            )
        ) {
            if (generating) {
                RoseFourLoader(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在理解需求")
            } else {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun ConciseMonitorConfirmation(
    task: IntentResult,
    referencePreview: Bitmap? = null,
    starting: Boolean,
    onStart: (IntentResult) -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        referencePreview?.let { bitmap ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "待确认参考图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = Color(0xFFFAFAF8).copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.07f)
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConciseConfirmationRow(label = "任务目标", value = task.userRequirement)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onReset,
                enabled = !starting,
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("重新描述")
            }
            Button(
                onClick = { onStart(task) },
                enabled = !starting,
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32),
                    contentColor = Color.White
                )
            ) {
                if (starting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("启动中")
                } else {
                    Text("开始看管")
                }
            }
        }
    }
}

@Composable
private fun ConciseConfirmationRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConciseMonitorRunningSummary(monitorStatus: MonitorStatus) {
    val isAlert = monitorStatus.lastResult == CheckResult.ALERT
    val accent = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val container = if (isAlert) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    WatcherCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = container
            ) {
                Icon(
                    imageVector = if (isAlert) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = accent
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val remark = monitorStatus.lastRemark.trim()
                Text(
                    text = if (isAlert) "告警状态" else "正在看管",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                if (remark.isNotBlank()) {
                    ConciseTypewriterText(
                        text = remark,
                        style = MaterialTheme.typography.titleMedium,
                        cursorColor = Color.Black,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        keepCursorAfterComplete = true,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
