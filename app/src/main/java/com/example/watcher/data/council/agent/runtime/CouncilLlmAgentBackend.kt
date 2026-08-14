package com.example.watcher.data.council.agent.runtime

import com.example.watcher.data.council.agent.core.*
import com.example.watcher.data.remote.OpenAiCompatibleProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Backend that calls an LLM to power the agent's thinking.
 * Parses LLM JSON output into either tool_calls or final_answer.
 */
class CouncilLlmAgentBackend(private val provider: OpenAiCompatibleProvider) : CouncilAgentBackend {

    private val gson = Gson()

    override suspend fun call(
        systemPrompt: String,
        messages: List<CouncilAgentMessage>,
        tools: List<CouncilAgentToolSchema>
    ): CouncilAgentResponse {
        val chatMessages = messages.map { msg ->
            com.example.watcher.data.remote.ChatMessage(
                role = when (msg.role) {
                    CouncilAgentMessage.ROLE_TOOL_RESULT -> "user"  // Tool results fed as user messages
                    else -> msg.role
                },
                content = if (msg.role == CouncilAgentMessage.ROLE_TOOL_RESULT) {
                    "[tool_result:${msg.toolCallId}] ${msg.content}"
                } else {
                    msg.content
                }
            )
        }

        val raw = provider.chat(systemPrompt = systemPrompt, messages = chatMessages).trim()
        return parseResponse(raw)
    }

    private fun parseResponse(raw: String): CouncilAgentResponse {
        // Try to extract JSON from the response
        val json = extractJson(raw)

        val map = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            // If not parseable, treat entire response as a summary
            return CouncilAgentResponse.FinalAnswer(fallbackOpinion(raw))
        }

        val type = map["type"] as? String
        return when (type) {
            "tool_calls" -> parseToolCalls(map)
            "final_answer" -> parseFinalAnswer(map)
            else -> {
                // No explicit type — try to interpret as opinion directly
                if (map.containsKey("summary") || map.containsKey("findings")) {
                    CouncilAgentResponse.FinalAnswer(parseOpinionMap(map))
                } else {
                    CouncilAgentResponse.FinalAnswer(fallbackOpinion(raw))
                }
            }
        }
    }

    private fun parseToolCalls(map: Map<String, Any?>): CouncilAgentResponse {
        val calls = try {
            @Suppress("UNCHECKED_CAST")
            val rawCalls = map["calls"] as? List<Map<String, Any?>> ?: emptyList()
            rawCalls.mapIndexed { index, callMap ->
                @Suppress("UNCHECKED_CAST")
                CouncilAgentToolCall(
                    id = callMap["id"] as? String ?: "call_$index",
                    name = callMap["name"] as? String ?: "",
                    arguments = callMap["arguments"] as? Map<String, Any?> ?: emptyMap()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        return if (calls.isNotEmpty()) {
            CouncilAgentResponse.ToolCalls(calls)
        } else {
            CouncilAgentResponse.FinalAnswer(fallbackOpinion(""))
        }
    }

    private fun parseFinalAnswer(map: Map<String, Any?>): CouncilAgentResponse {
        @Suppress("UNCHECKED_CAST")
        val opinionMap = map["opinion"] as? Map<String, Any?> ?: map
        return CouncilAgentResponse.FinalAnswer(parseOpinionMap(opinionMap))
    }

    private fun parseOpinionMap(map: Map<String, Any?>): CouncilAgentOpinion {
        @Suppress("UNCHECKED_CAST")
        return CouncilAgentOpinion(
            summary = (map["summary"] as? String)?.take(220) ?: "暂无明确结论",
            findings = (map["findings"] as? List<String>)?.take(4) ?: emptyList(),
            risks = (map["risks"] as? List<String>)?.take(4) ?: emptyList(),
            nextActions = (map["nextActions"] as? List<String>)?.take(4) ?: emptyList(),
            voteLevel = (map["voteLevel"] as? String) ?: "pass",
            voteReason = (map["voteReason"] as? String)?.take(220) ?: "当前未发现足够强的即时提醒信号",
            confidence = ((map["confidence"] as? Number)?.toInt() ?: 50).coerceIn(0, 100)
        )
    }

    private fun fallbackOpinion(raw: String) = CouncilAgentOpinion(
        summary = raw.take(200).ifBlank { "分析未产出结构化结果" },
        findings = emptyList(),
        risks = emptyList(),
        nextActions = emptyList(),
        voteLevel = "watch",
        voteReason = "响应格式异常，请检查模型输出",
        confidence = 20
    )

    private fun extractJson(raw: String): String {
        // Try to find JSON object in the response (handles markdown code blocks etc.)
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }
}
