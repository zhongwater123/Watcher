package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.watcher.ui.viewmodel.LocalAgentMessage
import com.example.watcher.ui.viewmodel.LocalAgentMessageKind
import com.example.watcher.ui.viewmodel.LocalAgentUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalAgentScreen(
    uiState: LocalAgentUiState,
    onSendMessage: (String) -> Unit,
    onClose: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var input by rememberSaveable { mutableStateOf("") }
    val canSend = uiState.canSend && input.isNotBlank()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Android ADK REPL Shell") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            MessageComposer(
                value = input,
                enabled = uiState.localBrainStatus.isReady && !uiState.isSending,
                canSend = canSend,
                isSending = uiState.isSending,
                onValueChange = { input = it },
                onSend = {
                    val message = input.trim()
                    if (message.isNotBlank()) {
                        input = ""
                        onSendMessage(message)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusPanel(uiState)
            }
            if (uiState.messages.isEmpty()) {
                item {
                    EmptyConversation(uiState.localBrainStatus.isReady)
                }
            } else {
                items(uiState.messages) { message ->
                    MessageBubble(message)
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(uiState: LocalAgentUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = uiState.localAgentName,
                style = MaterialTheme.typography.titleSmall
            )
            StatusSection(title = "ADK quickstart probe") {
                ProbeStatusRow(
                    label = "ADK dependency loaded",
                    isReady = uiState.quickstartReport.dependencyLoaded
                )
                ProbeStatusRow(
                    label = "KSP generated tools available",
                    isReady = uiState.quickstartReport.generatedToolsAvailable
                )
                ProbeStatusRow(
                    label = "Quickstart rootAgent created",
                    isReady = uiState.quickstartReport.agentDefinitionCreated
                )
            }
            StatusSection(title = "Local LiteRT brain") {
                ProbeStatusRow(
                    label = uiState.localBrainStatus.label,
                    isReady = uiState.localBrainStatus.isReady
                )
                uiState.localBrainStatus.errorMessage?.let { error ->
                    StatusText(error)
                }
            }
            uiState.errorMessage?.let { error ->
                StatusText(error)
            }
        }
    }
}

@Composable
private fun EmptyConversation(isReady: Boolean) {
    Text(
        text = if (isReady) {
            "Type a message to run the ADK agent."
        } else {
            "Load LiteRT-LM before sending a message."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp)
    )
}

@Composable
private fun MessageBubble(message: LocalAgentMessage) {
    val isUser = message.kind == LocalAgentMessageKind.USER
    val color = when (message.kind) {
        LocalAgentMessageKind.USER -> MaterialTheme.colorScheme.primaryContainer
        LocalAgentMessageKind.AGENT -> MaterialTheme.colorScheme.surfaceVariant
        LocalAgentMessageKind.EVENT -> MaterialTheme.colorScheme.secondaryContainer
        LocalAgentMessageKind.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (message.kind) {
        LocalAgentMessageKind.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        LocalAgentMessageKind.AGENT -> MaterialTheme.colorScheme.onSurfaceVariant
        LocalAgentMessageKind.EVENT -> MaterialTheme.colorScheme.onSecondaryContainer
        LocalAgentMessageKind.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.medium,
            color = color,
            contentColor = contentColor
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when (message.kind) {
                        LocalAgentMessageKind.USER -> "You"
                        LocalAgentMessageKind.AGENT -> "Agent"
                        LocalAgentMessageKind.EVENT -> "ADK Event"
                        LocalAgentMessageKind.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(
    value: String,
    enabled: Boolean,
    canSend: Boolean,
    isSending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 128.dp),
                placeholder = {
                    Text(if (enabled) "Message" else "LiteRT-LM not ready")
                },
                maxLines = 4
            )
            IconButton(
                onClick = onSend,
                enabled = canSend
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = if (isSending) "Sending" else "Send"
                )
            }
        }
    }
}

@Composable
private fun StatusSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ProbeStatusRow(
    label: String,
    isReady: Boolean
) {
    val tint = if (isReady) Color(0xFF0E8B65) else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = tint
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
