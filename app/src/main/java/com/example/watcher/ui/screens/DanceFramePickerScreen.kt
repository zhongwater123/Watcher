package com.example.watcher.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FramePickerResult(
    val title: String,
    val coverTimeMs: Long,
    val clipStartMs: Long,
    val clipEndMs: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanceFramePickerScreen(
    videoDurationMs: Long,
    videoReady: Boolean = true,
    onExtractFrame: (timeMs: Long) -> Bitmap?,
    onConfirm: (FramePickerResult) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var coverPosition by remember { mutableFloatStateOf(0f) }
    var clipRange by remember { mutableStateOf(0f..1f) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Active preview position — follows whichever slider was last touched
    var activePreviewPosition by remember { mutableFloatStateOf(0f) }

    val coverTimeMs = (coverPosition * videoDurationMs).toLong()
    val clipStartMs = (clipRange.start * videoDurationMs).toLong()
    val clipEndMs = (clipRange.endInclusive * videoDurationMs).toLong()
    val activePreviewMs = (activePreviewPosition * videoDurationMs).toLong()

    // Extract frame for ANY slider interaction
    val roundedPreviewMs = (activePreviewMs / 200L) * 200L
    LaunchedEffect(roundedPreviewMs, videoReady) {
        if (!videoReady || videoDurationMs <= 0) return@LaunchedEffect
        val frame = withContext(Dispatchers.IO) { onExtractFrame(roundedPreviewMs) }
        previewBitmap = frame
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("配置项目") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "取消")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Frame preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val frame = previewBitmap
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "封面预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = if (!videoReady) "正在准备视频..." else "拖动下方进度条选择封面",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Cover frame selector
            Column {
                Text(
                    text = "封面帧: ${formatTimeMs(coverTimeMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = coverPosition,
                    onValueChange = {
                        coverPosition = it
                        activePreviewPosition = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = videoReady
                )
            }

            // Clip range selector
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "使用区间",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${formatTimeMs(clipStartMs)} – ${formatTimeMs(clipEndMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                RangeSlider(
                    value = clipRange,
                    onValueChange = { range ->
                        val prevRange = clipRange
                        clipRange = range
                        // Preview follows whichever end the user is dragging
                        activePreviewPosition = if (range.start != prevRange.start) range.start else range.endInclusive
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = videoReady
                )
            }

            // Title input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("项目名称") },
                placeholder = { Text("为这段舞蹈命名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Confirm button
            Button(
                onClick = {
                    onConfirm(FramePickerResult(
                        title = title,
                        coverTimeMs = coverTimeMs,
                        clipStartMs = clipStartMs,
                        clipEndMs = clipEndMs
                    ))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = videoReady
            ) {
                Text("确认并保存")
            }
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${"%02d".format(seconds)}"
}
