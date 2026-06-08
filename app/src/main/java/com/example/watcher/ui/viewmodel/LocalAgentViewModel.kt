package com.example.watcher.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.watcher.data.local.litert.LiteRtEngineState
import com.example.watcher.localagent.LocalAgentFactory
import com.example.watcher.localagent.adkprobe.LocalAgentProbeReport
import com.example.watcher.localagent.adkprobe.LocalAgentQuickstartAgent
import com.example.watcher.localagent.brain.LocalAgentBrainStatus
import com.example.watcher.localagent.litert.LiteRtLocalAgentBrain
import com.example.watcher.localagent.runtime.LocalAgentRuntime
import com.example.watcher.localagent.runtime.LocalAgentRuntimeEvent
import com.example.watcher.watcherApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalAgentUiState(
    val quickstartReport: LocalAgentProbeReport,
    val localBrainStatus: LocalAgentBrainStatus,
    val localAgentName: String,
    val messages: List<LocalAgentMessage> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null
) {
    val canSend: Boolean
        get() = localBrainStatus.isReady && !isSending
}

data class LocalAgentMessage(
    val kind: LocalAgentMessageKind,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LocalAgentMessageKind {
    USER,
    AGENT,
    EVENT,
    ERROR
}

class LocalAgentViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.watcherApplication().agentFrameworkContainer
    private val engineManager = container.liteRtEngineManager
    private val localBrain = LiteRtLocalAgentBrain(
        provider = container.liteRtProvider,
        statusProvider = { engineManager.status.value.toLocalAgentBrainStatus() }
    )
    private val localAgent = LocalAgentFactory.createDeviceAssistant(localBrain)
    private val runtime = LocalAgentRuntime(localAgent)

    private val _uiState = MutableStateFlow(
        LocalAgentUiState(
            quickstartReport = inspectQuickstart(),
            localBrainStatus = localBrain.status,
            localAgentName = runtime.agentName
        )
    )
    val uiState: StateFlow<LocalAgentUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            engineManager.status.collect {
                _uiState.update { current ->
                    current.copy(localBrainStatus = localBrain.status)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val current = _uiState.value
        if (trimmed.isBlank() || !current.canSend) return

        _uiState.update {
            it.copy(
                messages = it.messages + LocalAgentMessage(
                    kind = LocalAgentMessageKind.USER,
                    content = trimmed
                ),
                isSending = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            runCatching {
                runtime.sendMessage(trimmed).collect { event ->
                    handleRuntimeEvent(event)
                }
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                _uiState.update {
                    it.copy(
                        messages = it.messages + LocalAgentMessage(
                            kind = LocalAgentMessageKind.ERROR,
                            content = message
                        ),
                        errorMessage = message
                    )
                }
            }
            _uiState.update { it.copy(isSending = false) }
        }
    }

    private fun handleRuntimeEvent(event: LocalAgentRuntimeEvent) {
        when (event) {
            is LocalAgentRuntimeEvent.Text -> {
                if (event.author == "user") return
                appendMessage(
                    kind = LocalAgentMessageKind.AGENT,
                    content = event.content
                )
            }
            is LocalAgentRuntimeEvent.ToolCall -> appendMessage(
                kind = LocalAgentMessageKind.EVENT,
                content = "functionCall ${event.name} ${event.args}"
            )
            is LocalAgentRuntimeEvent.ToolResult -> appendMessage(
                kind = LocalAgentMessageKind.EVENT,
                content = "functionResponse ${event.name} ${event.response}"
            )
            is LocalAgentRuntimeEvent.Error -> {
                val message = listOfNotNull(event.code, event.message).joinToString(": ")
                _uiState.update {
                    it.copy(
                        messages = it.messages + LocalAgentMessage(
                            kind = LocalAgentMessageKind.ERROR,
                            content = message
                        ),
                        errorMessage = message
                    )
                }
            }
        }
    }

    private fun appendMessage(
        kind: LocalAgentMessageKind,
        content: String
    ) {
        if (content.isBlank()) return
        _uiState.update {
            it.copy(
                messages = it.messages + LocalAgentMessage(
                    kind = kind,
                    content = content
                )
            )
        }
    }

    private fun inspectQuickstart(): LocalAgentProbeReport {
        return runCatching {
            LocalAgentQuickstartAgent.inspect()
        }.getOrElse { error ->
            LocalAgentProbeReport(
                dependencyLoaded = false,
                generatedToolsAvailable = false,
                agentDefinitionCreated = false,
                agentName = "",
                toolNames = emptyList(),
                errorMessage = error.message ?: error::class.java.simpleName
            )
        }
    }

    private fun com.example.watcher.data.local.litert.LiteRtEngineStatus.toLocalAgentBrainStatus(): LocalAgentBrainStatus {
        val modelName = modelConfig?.modelPath
            ?.substringAfterLast('\\')
            ?.substringAfterLast('/')
        return LocalAgentBrainStatus(
            isReady = state == LiteRtEngineState.Ready,
            label = when (state) {
                LiteRtEngineState.Ready -> "Ready: ${modelName ?: "LiteRT-LM"}"
                LiteRtEngineState.Initializing -> "Initializing local model"
                LiteRtEngineState.Error -> "Error"
                LiteRtEngineState.Closing -> "Closing local model"
                LiteRtEngineState.Idle -> "Idle"
                LiteRtEngineState.NotConfigured -> "LiteRT-LM not configured"
            },
            errorMessage = errorMessage
        )
    }
}
