package com.example.watcher.agentframework.runtime

import com.example.watcher.agentframework.core.AgentAction
import com.example.watcher.agentframework.core.AgentConversationItem
import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentMemoryWrite
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentToolCall
import com.google.gson.Gson

interface AgentModelGateway {
    suspend fun generate(
        systemPrompt: String,
        messages: List<AgentConversationItem>
    ): String
}

class JsonProtocolAgentBrain(
    private val gateway: AgentModelGateway
) : AgentBrain {
    private val gson = Gson()

    override suspend fun decide(request: AgentBrainRequest): AgentDecision {
        val systemPrompt = buildSystemPrompt(request)
        val userPrompt = buildUserPrompt(request)
        val messages = request.session.history.takeLast(12) + AgentConversationItem(
            role = AgentMessageRole.User,
            content = userPrompt
        )
        val raw = gateway.generate(systemPrompt, messages)
        return parseResponse(raw)
    }

    private fun buildSystemPrompt(request: AgentBrainRequest): String = buildString {
        appendLine("You are an autonomous agent.")
        appendLine("Agent name: ${request.definition.name}")
        if (request.definition.description.isNotBlank()) {
            appendLine("Description: ${request.definition.description}")
        }
        appendLine("Goal: ${request.definition.goal}")
        appendLine("Instruction: ${request.definition.systemInstruction}")
        appendLine()
        appendLine("Return JSON only.")
        appendLine("Schema:")
        appendLine("{")
        appendLine("  \"thinking\": \"short internal reasoning\",")
        appendLine("  \"reply\": \"optional user-facing reply\",")
        appendLine("  \"memory\": [")
        appendLine("    {\"scope\": \"working|episodic\", \"content\": \"memory text\"}")
        appendLine("  ],")
        appendLine("  \"action\": {")
        appendLine("    \"type\": \"continue|tool_calls|wait|finish\",")
        appendLine("    \"reason\": \"why this action is chosen\",")
        appendLine("    \"success\": true,")
        appendLine("    \"resumeAfterMillis\": 0,")
        appendLine("    \"calls\": [")
        appendLine("      {\"id\": \"call_1\", \"name\": \"tool_name\", \"arguments\": {}}")
        appendLine("    ]")
        appendLine("  }")
        appendLine("}")
        appendLine("If tools are not needed, do not invent tool calls.")
    }

    private fun buildUserPrompt(request: AgentBrainRequest): String = buildString {
        appendLine("Current session status:")
        appendLine("- status: ${request.session.status}")
        appendLine("- stepCount: ${request.session.stepCount}")
        appendLine("- idleTurns: ${request.session.idleTurns}")
        appendLine("- lastReply: ${request.session.lastReply.orEmpty()}")
        appendLine()
        appendLine("Recent inputs:")
        if (request.recentInputs.isEmpty()) appendLine("- empty")
        request.recentInputs.forEach { input ->
            appendLine("- ${input.kind}: ${input.content}")
        }
        appendLine()
        appendLine("Working memory:")
        if (request.memory.working.isEmpty()) appendLine("- empty")
        request.memory.working.forEach { appendLine("- ${it.content}") }
        appendLine()
        appendLine("Episodic memory:")
        if (request.memory.episodic.isEmpty()) appendLine("- empty")
        request.memory.episodic.forEach { appendLine("- ${it.content}") }
        appendLine()
        appendLine("Knowledge:")
        if (request.knowledge.entries.isEmpty()) appendLine("- empty")
        request.knowledge.entries.forEach { appendLine("- ${it.content}") }
        appendLine()
        appendLine("Available tools:")
        if (request.availableTools.isEmpty()) {
            appendLine("- none")
        } else {
            request.availableTools.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description}")
                tool.parameters.forEach { param ->
                    appendLine("  param ${param.name} (${param.type}) required=${param.required}: ${param.description}")
                }
            }
        }
        appendLine()
        appendLine("Decide the next action that best advances the goal.")
    }

    private fun parseResponse(raw: String): AgentDecision {
        val json = extractJson(raw)
        val map = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as Map<String, Any?>
        }.getOrElse {
            return AgentDecision(
                thinking = "fallback",
                reply = raw.take(300),
                action = AgentAction.Finish(
                    reason = "Model response was not valid JSON",
                    success = false
                )
            )
        }

        val thinking = map["thinking"] as? String ?: ""
        val reply = map["reply"] as? String
        val memoryWrites = parseMemory(map["memory"])
        val action = parseAction(map["action"])
        return AgentDecision(
            thinking = thinking,
            reply = reply,
            memoryWrites = memoryWrites,
            action = action
        )
    }

    private fun parseMemory(value: Any?): List<AgentMemoryWrite> {
        val items = value as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val content = map["content"] as? String ?: return@mapNotNull null
            val scope = when ((map["scope"] as? String)?.lowercase()) {
                "episodic" -> AgentMemoryScope.Episodic
                else -> AgentMemoryScope.Working
            }
            AgentMemoryWrite(scope = scope, content = content)
        }
    }

    private fun parseAction(value: Any?): AgentAction {
        val map = value as? Map<*, *> ?: return AgentAction.Continue
        return when ((map["type"] as? String)?.lowercase()) {
            "tool_calls" -> AgentAction.UseTools(calls = parseToolCalls(map["calls"]))
            "wait" -> AgentAction.Wait(
                reason = map["reason"] as? String ?: "wait",
                resumeAfterMillis = (map["resumeAfterMillis"] as? Number)?.toLong() ?: 0L
            )

            "finish" -> AgentAction.Finish(
                reason = map["reason"] as? String ?: "finished",
                success = map["success"] as? Boolean ?: true
            )

            "request_approval" -> {
                @Suppress("UNCHECKED_CAST")
                val args = map["toolArguments"] as? Map<String, Any?> ?: emptyMap()
                AgentAction.RequestApproval(
                    reason = map["reason"] as? String ?: "approval needed",
                    pendingAction = map["pendingAction"] as? String ?: "",
                    riskLevel = map["riskLevel"] as? String ?: "medium",
                    toolName = map["toolName"] as? String,
                    toolArguments = args
                )
            }

            else -> AgentAction.Continue
        }
    }

    private fun parseToolCalls(value: Any?): List<AgentToolCall> {
        val items = value as? List<*> ?: return emptyList()
        return items.mapIndexedNotNull { index, item ->
            val map = item as? Map<*, *> ?: return@mapIndexedNotNull null
            val name = map["name"] as? String ?: return@mapIndexedNotNull null
            @Suppress("UNCHECKED_CAST")
            val arguments = map["arguments"] as? Map<String, Any?> ?: emptyMap()
            AgentToolCall(
                id = map["id"] as? String ?: "call_$index",
                name = name,
                arguments = arguments
            )
        }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            trimmed.substring(start, end + 1)
        } else {
            trimmed
        }
    }
}
