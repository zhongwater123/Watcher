package com.example.watcher.localagent.adkprobe

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ProbeModel(
    override val name: String = "local-agent-probe-model"
) : Model {
    override fun generateContent(
        request: LlmRequest,
        stream: Boolean
    ): Flow<LlmResponse> {
        return flowOf(
            LlmResponse(
                content = Content(
                    role = Role.MODEL,
                    parts = listOf(Part(text = "Local Agent ADK probe model is not executable."))
                ),
                partial = false
            )
        )
    }
}
