package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.ui.components.WatcherCard


@Composable
internal fun ClassroomRecordingControlCard(
    status: VideoProcessingStatus,
    onStop: () -> Unit,
    onOpenResult: (() -> Unit)? = null
) {
    WatcherCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("功能控制", style = MaterialTheme.typography.titleLarge)
            ClassroomControlLine("已录制", formatDuration(status.recordedDurationSeconds))
            ClassroomControlLine("分片", "${status.recordedSegmentCount} / ${status.segmentCount}")
            ClassroomControlLine("已分析", status.analyzedSegmentCount.toString())
            ClassroomControlLine("待处理", status.pendingSegmentCount.toString())
            ClassroomControlLine("阶段", status.message.ifBlank { "录制中" })
            ClassroomControlLine("语音源", speechProviderLabel(status))
            ClassroomControlLine("转写", realtimeConnectionLabel(status.realtimeConnectionState))
            status.realtimeSpeechFallbackReason
                ?.takeIf(String::isNotBlank)
                ?.let { ClassroomControlLine("降级", it) }
            ClassroomControlLine("延迟", if (status.realtimeAudioLagMs > 0L) "${status.realtimeAudioLagMs}ms" else "-")
            ClassroomControlLine("待补偿", status.realtimeBackfillSegmentCount.toString())
            ClassroomControlLine("丢帧", status.realtimeDroppedFrameCount.toString())
            ClassroomControlLine("待发送", status.realtimePendingFrameCount.toString())
            ClassroomControlLine("语音 log", shortAsrLogId(status.realtimeAsrLogId))
            status.realtimeSpeechSessionId
                .takeIf(String::isNotBlank)
                ?.let { ClassroomControlLine("Session", it.takeLast(12)) }
            val resultAvailable = onOpenResult != null
            Button(
                onClick = onOpenResult ?: onStop,
                enabled = resultAvailable || !status.stopRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (resultAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    contentColor = if (resultAvailable) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onError
                    }
                )
            ) {
                Text(
                    when {
                        resultAvailable && status.isBusy -> "查看本次结果（生成中）"
                        resultAvailable -> "查看本次结果"
                        status.stopRequested -> "正在停止..."
                        else -> "停止录课"
                    }
                )
            }
        }
    }
}

private fun speechProviderLabel(status: VideoProcessingStatus): String {
    val provider = when (status.realtimeSpeechProvider.lowercase()) {
        "ast" -> "AST"
        "asr" -> "ASR"
        else -> "-"
    }
    return if (status.realtimeSpeechFallbackReason.isNullOrBlank()) {
        provider
    } else {
        "AST → ASR"
    }
}

@Composable
private fun ClassroomControlLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
