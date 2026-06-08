package com.example.watcher.localagent.adk

import com.example.watcher.localagent.brain.LocalAgentBrain
import com.example.watcher.localagent.brain.LocalAgentBrainMessage
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalAgentAdkModel(
    private val brain: LocalAgentBrain,
    override val name: String = "watcher-local-agent-brain"
) : Model {

    override fun generateContent(
        request: LlmRequest,
        stream: Boolean
    ): Flow<LlmResponse> = flow {
        val response = brain.generate(
            systemInstruction = request.config.systemInstruction.toPromptText(),
            messages = request.contents.toBrainMessages()
        )
        emit(
            LlmResponse(
                content = Content(
                    role = Role.MODEL,
                    parts = listOf(Part(text = response))
                ),
                partial = false
            )
        )
    }

    private fun List<Content>.toBrainMessages(): List<LocalAgentBrainMessage> {
        return mapNotNull { content ->
            val text = content.parts.toPromptText()
            if (text.isBlank()) {
                null
            } else {
                LocalAgentBrainMessage(
                    role = content.role ?: Role.USER,
                    content = text
                )
            }
        }
    }

    private fun Content?.toPromptText(): String {
        return this?.parts?.toPromptText().orEmpty()
    }

    private fun List<Part>.toPromptText(): String {
        return mapNotNull { part ->
            val text = part.text
            val functionCall = part.functionCall
            val functionResponse = part.functionResponse
            when {
                !text.isNullOrBlank() -> text
                functionCall != null -> functionCall.toPromptText()
                functionResponse != null -> functionResponse.toPromptText()
                else -> null
            }
        }.joinToString("\n")
    }

    private fun FunctionCall.toPromptText(): String {
        return "Function call: $name args=$args"
    }

    private fun FunctionResponse.toPromptText(): String {
        return "Function response: $name result=$response"
    }
}
