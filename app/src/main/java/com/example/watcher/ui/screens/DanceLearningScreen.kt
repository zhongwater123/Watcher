package com.example.watcher.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.local.pose.PoseVideoSession
import com.example.watcher.ui.components.StatusPill
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanceLearningScreen(
    sessions: List<PoseVideoSession>,
    onPickVideo: (Uri) -> Unit,
    onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onProcessSession: (PoseVideoSession) -> Unit = {},
    onPreviewSession: (PoseVideoSession) -> Unit = {},
    onSegmentSession: (PoseVideoSession) -> Unit = {},
    onPracticeSession: (PoseVideoSession) -> Unit = {},
    onBack: () -> Unit
) {
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(onPickVideo)
    }

    var renameTarget by remember { mutableStateOf<PoseVideoSession?>(null) }
    var deleteTarget by remember { mutableStateOf<PoseVideoSession?>(null) }
    var choiceTarget by remember { mutableStateOf<PoseVideoSession?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("舞蹈学习") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { videoPickerLauncher.launch("video/mp4") }) {
                        Icon(Icons.Default.Add, contentDescription = "导入视频")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (sessions.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.padding(16.dp)
                        )
                        Text(
                            text = "还没有舞蹈项目",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击右上角 + 导入一段舞蹈视频",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                // Video grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        VideoProjectCard(
                            session = session,
                            onClick = {
                                if (session.processingStatus == PoseVideoSession.ProcessingStatus.PENDING) {
                                    onProcessSession(session)
                                } else {
                                    choiceTarget = session
                                }
                            },
                            onRename = { renameTarget = session },
                            onDelete = { deleteTarget = session }
                        )
                    }
                }
            }
        }
    }

    // Rename dialog
    renameTarget?.let { session ->
        RenameDialog(
            currentTitle = session.title,
            onConfirm = { newTitle ->
                onRenameSession(session.id, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目") },
            text = { Text("确认删除「${session.title}」？视频文件和姿态数据将一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Choice dialog for READY/SEGMENTED sessions
    choiceTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { choiceTarget = null },
            title = { Text(session.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        choiceTarget = null
                        onPreviewSession(session)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("预览效果")
                    }
                    TextButton(onClick = {
                        choiceTarget = null
                        onProcessSession(session)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("继续优化")
                    }
                    TextButton(onClick = {
                        choiceTarget = null
                        onSegmentSession(session)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("切分动作")
                    }
                    TextButton(onClick = {
                        choiceTarget = null
                        onPracticeSession(session)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("学习舞蹈")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choiceTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoProjectCard(
    session: PoseVideoSession,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val thumbnailBitmap = remember(session.thumbnailPath) {
                    session.thumbnailPath?.takeIf { it.isNotBlank() && File(it).exists() }?.let {
                        BitmapFactory.decodeFile(it)
                    }
                }
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap.asImageBitmap(),
                        contentDescription = session.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }

                // Duration badge
                if (session.sourceVideoDurationMs > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = formatDuration(session.sourceVideoDurationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Info section
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Status + meta
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val (statusText, statusColor) = when (session.processingStatus) {
                        PoseVideoSession.ProcessingStatus.SEGMENTED -> "已切分" to Color(0xFF1565C0)
                        PoseVideoSession.ProcessingStatus.READY -> "可预览" to Color(0xFF0E8B65)
                        else -> "待处理" to MaterialTheme.colorScheme.outline
                    }
                    StatusPill(text = statusText, accent = statusColor)
                }

                if (session.processingStatus == PoseVideoSession.ProcessingStatus.READY && session.frameCount > 0) {
                    Text(
                        text = "${session.frameCount} 帧 · ${session.sourceFps}fps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Processing progress
                if (session.processingStatus == PoseVideoSession.ProcessingStatus.PENDING) {
                    LinearProgressIndicator(
                        progress = { session.processingProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        // Long-press context menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}


@Composable
private fun RenameDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("项目名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${"%02d".format(seconds)}"
}
