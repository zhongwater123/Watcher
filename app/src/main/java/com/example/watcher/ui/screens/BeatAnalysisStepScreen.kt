package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.watcher.data.local.pose.BeatAnalysisProcessor
import com.example.watcher.data.local.pose.PoseVideoSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatAnalysisStepScreen(
    session: PoseVideoSession,
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf<BeatAnalysisProcessor.AnalysisProgress>(
        BeatAnalysisProcessor.AnalysisProgress.ExtractingAudio
    ) }
    var isFinished by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session.id) {
        try {
            val processor = BeatAnalysisProcessor(context)
            withContext(Dispatchers.IO) {
                processor.analyze(session) { p -> progress = p }
            }
            isFinished = true
        } catch (e: Exception) {
            errorMessage = e.message
            isFinished = true
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("节拍分析") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StepRow("提取音频", stepStatus(progress, 0))
                    StepRow("本地节拍分析", stepStatus(progress, 1))
                    StepRow("上传音频", stepStatus(progress, 2))
                    StepRow("AI 节拍校准", stepStatus(progress, 3))
                    StepRow("写入节拍文件", stepStatus(progress, 4))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                // Check if fallback succeeded
                val fallbackDone = progress is BeatAnalysisProcessor.AnalysisProgress.Failed &&
                    (progress as BeatAnalysisProcessor.AnalysisProgress.Failed).fallbackPath != null
                if (fallbackDone) {
                    Text(
                        "已使用本地分析结果作为备用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isFinished) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("完成")
                }
            } else {
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("跳过节拍分析")
                }
            }
        }
    }
}

private enum class BeatStepState { PENDING, ACTIVE, DONE, ERROR }

@Composable
private fun StepRow(label: String, state: BeatStepState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            BeatStepState.DONE -> Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            BeatStepState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            BeatStepState.ERROR -> Icon(
                Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
            BeatStepState.PENDING -> Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                BeatStepState.ACTIVE -> MaterialTheme.colorScheme.onSurface
                BeatStepState.DONE -> MaterialTheme.colorScheme.primary
                BeatStepState.ERROR -> MaterialTheme.colorScheme.error
                BeatStepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }
        )
    }
}

private fun stepStatus(progress: BeatAnalysisProcessor.AnalysisProgress, stepIndex: Int): BeatStepState {
    val currentStep = when (progress) {
        is BeatAnalysisProcessor.AnalysisProgress.ExtractingAudio -> 0
        is BeatAnalysisProcessor.AnalysisProgress.RunningDSP -> 1
        is BeatAnalysisProcessor.AnalysisProgress.UploadingAudio -> 2
        is BeatAnalysisProcessor.AnalysisProgress.WaitingLLM -> 3
        is BeatAnalysisProcessor.AnalysisProgress.WritingBeatFile -> 4
        is BeatAnalysisProcessor.AnalysisProgress.Complete -> 5
        is BeatAnalysisProcessor.AnalysisProgress.Failed -> -1
    }
    return when {
        progress is BeatAnalysisProcessor.AnalysisProgress.Failed -> {
            if (stepIndex < 5) BeatStepState.ERROR else BeatStepState.PENDING
        }
        stepIndex < currentStep -> BeatStepState.DONE
        stepIndex == currentStep -> BeatStepState.ACTIVE
        else -> BeatStepState.PENDING
    }
}
