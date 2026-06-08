package com.example.watcher.localagent.litert

import com.example.watcher.data.local.litert.LiteRtLlmProvider
import com.example.watcher.data.remote.ChatMessage
import com.example.watcher.localagent.brain.LocalAgentBrain
import com.example.watcher.localagent.brain.LocalAgentBrainMessage
import com.example.watcher.localagent.brain.LocalAgentBrainStatus

class LiteRtLocalAgentBrain(
    private val provider: LiteRtLlmProvider,
    private val statusProvider: () -> LocalAgentBrainStatus
) : LocalAgentBrain {

    override val status: LocalAgentBrainStatus
        get() = statusProvider()

    override suspend fun generate(
        systemInstruction: String,
        messages: List<LocalAgentBrainMessage>
    ): String {
        return provider.chat(
            systemPrompt = systemInstruction,
            messages = messages.map { message ->
                ChatMessage(
                    role = message.role,
                    content = message.content
                )
            }
        )
    }
}
