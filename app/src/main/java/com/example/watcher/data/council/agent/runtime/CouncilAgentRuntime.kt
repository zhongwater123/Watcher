package com.example.watcher.data.council.agent.runtime

import android.util.Log
import com.example.watcher.data.council.agent.core.*
import com.example.watcher.data.council.agent.tools.CouncilAgentToolExecutor

/**
 * Execution engine for a single agent. Handles the think→tool→think loop.
 * Stateless per-call — all state lives in the backend, tools, and memory.
 */
class CouncilAgentRuntime(
    private val backend: CouncilAgentBackend,
    private val toolExecutor: CouncilAgentToolExecutor,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS
) {
    companion object {
        private const val TAG = "CouncilAgentRuntime"
        private const val DEFAULT_MAX_ITERATIONS = 3
    }

    fun availableTools(): List<CouncilAgentToolSchema> = toolExecutor.availableSchemas()

    /**
     * Execute an agent request through the full tool-calling loop.
     * Returns the agent's final opinion after all tool calls are resolved.
     */
    suspend fun execute(agentId: String, request: CouncilAgentRequest): CouncilAgentOpinion {
        val systemPrompt = buildSystemPrompt(request)
        val userMessage = buildUserMessage(request)
        val messages = mutableListOf(CouncilAgentMessage.user(userMessage))
        val tools = request.availableTools

        for (iteration in 1..maxIterations) {
            Log.d(TAG, "Agent $agentId: iteration $iteration")
            val response = backend.call(systemPrompt, messages, tools)

            when (response) {
                is CouncilAgentResponse.FinalAnswer -> {
                    Log.d(TAG, "Agent $agentId: final answer at iteration $iteration")
                    return response.opinion
                }
                is CouncilAgentResponse.ToolCalls -> {
                    Log.d(TAG, "Agent $agentId: ${response.calls.size} tool calls at iteration $iteration")
                    // Execute each tool and add results to conversation
                    for (call in response.calls) {
                        val result = toolExecutor.execute(agentId, call)
                        val resultJson = com.google.gson.Gson().toJson(result.result)
                        messages += CouncilAgentMessage.toolResult(call.id, resultJson)
                        Log.d(TAG, "Agent $agentId: tool ${call.name} → ${if (result.success) "ok" else result.error}")
                    }
                }
            }
        }

        // Max iterations reached — force a final answer call without tools
        Log.w(TAG, "Agent $agentId: max iterations reached, forcing final answer")
        val forced = backend.call(
            systemPrompt,
            messages + CouncilAgentMessage.user("请立即给出你的最终分析意见，不要再调用工具。直接输出 JSON 格式的 final_answer。"),
            emptyList()
        )
        return when (forced) {
            is CouncilAgentResponse.FinalAnswer -> forced.opinion
            is CouncilAgentResponse.ToolCalls -> CouncilAgentOpinion(
                summary = "分析超时，未能给出完整结论",
                findings = emptyList(),
                risks = emptyList(),
                nextActions = emptyList(),
                voteLevel = "watch",
                voteReason = "分析轮次耗尽",
                confidence = 30
            )
        }
    }

    private fun buildSystemPrompt(request: CouncilAgentRequest): String = buildString {
        val p = request.profile
        appendLine("# 角色")
        appendLine("你是直播分析智囊团中的「${p.name}」。")
        if (p.description.isNotBlank()) appendLine("职责：${p.description}")
        appendLine()
        if (p.persona.isNotBlank()) {
            appendLine("# 工作风格")
            appendLine(p.persona)
            appendLine()
        }
        appendLine("# 专业视角")
        appendLine(p.perspective)
        appendLine()
        appendLine("# 使命")
        appendLine("在整场直播中持续为用户服务。你始终站在用户利益一侧。")
        appendLine()

        // Tool instructions
        if (request.availableTools.isNotEmpty()) {
            appendLine("# 可用工具")
            appendLine("你可以调用以下工具来辅助分析。需要时返回 tool_calls，不需要时直接返回 final_answer。")
            request.availableTools.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description}")
            }
            appendLine()
        }

        appendLine("# 输出格式")
        appendLine("严格返回一个 JSON 对象，所有文本使用简体中文。")
        appendLine("如果需要调用工具：{\"type\":\"tool_calls\",\"calls\":[{\"id\":\"call_1\",\"name\":\"工具名\",\"arguments\":{...}}]}")
        appendLine("如果给出最终意见：{\"type\":\"final_answer\",\"opinion\":{\"summary\":\"...\",\"findings\":[...],\"risks\":[...],\"nextActions\":[...],\"voteLevel\":\"pass|watch|warn|alert\",\"voteReason\":\"...\",\"confidence\":0-100}}")
    }

    private fun buildUserMessage(request: CouncilAgentRequest): String = buildString {
        val ctx = request.context
        appendLine("[任务]")
        appendLine("- 场景类型：${ctx.sceneType}")
        if (ctx.speakerRole.isNotBlank()) appendLine("- 用户是：${ctx.speakerRole}")
        if (ctx.targetRole.isNotBlank()) appendLine("- 对方是：${ctx.targetRole}")
        appendLine("- 分析目标：${ctx.objective.ifBlank { "帮助用户快速判断当前局势和风险" }}")
        appendLine("- 重点关注：${ctx.focus.ifBlank { "保护用户利益，识别下一步最佳行动" }}")
        if (ctx.background.isNotBlank()) appendLine("- 背景：${ctx.background}")
        appendLine("- 分析轮次：第${ctx.roundNumber}轮")
        appendLine()

        appendLine("[近期画面]")
        if (ctx.recentVisual.isEmpty()) appendLine("- 暂无")
        else ctx.recentVisual.forEach { (ts, text) -> appendLine("- [${formatTime(ts)}] $text") }
        appendLine()

        appendLine("[近期语音]（麦克风拾取，未标注说话人的为未能确认身份）")
        if (ctx.recentSpeech.isEmpty()) appendLine("- 暂无")
        else ctx.recentSpeech.forEach { (ts, text) -> appendLine("- [${formatTime(ts)}] $text") }
        appendLine()

        appendLine("[压缩记忆]")
        appendLine("- 长期记忆：${ctx.memoryA.ifBlank { "暂无" }}")
        appendLine("- 短期记忆：${ctx.memoryB.ifBlank { "暂无" }}")
        appendLine()

        // Discussion context
        if (request.type == CouncilAgentRequestType.DISCUSS_ASK || request.type == CouncilAgentRequestType.DISCUSS_REPLY) {
            request.discussionContext?.let { dc ->
                appendLine("[讨论上下文]")
                dc.allOpinions.forEach { op ->
                    appendLine("  【专家意见】${op.summary} (vote=${op.voteLevel}, confidence=${op.confidence})")
                }
                if (dc.previousTurns.isNotEmpty()) {
                    appendLine("  已有讨论：")
                    dc.previousTurns.takeLast(6).forEach { t ->
                        appendLine("  ${t.fromAgent}→${t.toAgent}: ${t.message}")
                    }
                }
                if (request.type == CouncilAgentRequestType.DISCUSS_REPLY) {
                    appendLine("  @你的提问来自：${dc.questionFrom}")
                    appendLine("  问题：${dc.question}")
                    if (!dc.questionReason.isNullOrBlank()) appendLine("  原因：${dc.questionReason}")
                }
                appendLine()
            }
        }

        appendLine("[分析要求]")
        appendLine("- 聚焦增量变化：重点分析与之前相比有什么新发现")
        appendLine("- findings 必须有据可查，引用画面或语音内容")
        appendLine("- risks 每条格式：「[高/中/低] 风险描述」")
        appendLine("- nextActions 使用「你应该…」句式，面向用户")
        appendLine("- 你可以使用工具来查询知识、写入记忆、请求画面观察等")
    }

    private fun formatTime(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }
}
