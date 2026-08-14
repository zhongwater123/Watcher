package com.example.watcher.data.council.agent.tools

import com.example.watcher.data.council.agent.core.CouncilAgentToolSchema
import com.example.watcher.data.repository.SceneMemoryManager

/** Submits a visual observation request to consumer B/C pipeline. */
class CouncilObservationRequestTool(
    private val sceneMemoryManager: SceneMemoryManager
) : CouncilAgentTool {

    override val schema = CouncilAgentToolSchema(
        name = "request_observation",
        description = "请求摄像头在下一轮画面分析中重点关注指定的视觉细节。注意：摄像头只能看到画面，听不到声音，只能请求肉眼可见的内容（表情、手势、物品、文字、动作等）。",
        parameters = mapOf(
            "request" to param("string", "需要关注的画面细节描述", required = true),
            "reason" to param("string", "请求原因，说明这个观察对分析的价值")
        )
    )

    override suspend fun execute(agentId: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val request = arguments["request"] as? String ?: return mapOf("error" to "missing request")
        val reason = arguments["reason"] as? String ?: ""
        val labeled = "$request（${agentId}${if (reason.isNotBlank()) "：$reason" else ""}）"
        sceneMemoryManager.appendExpertRequests(listOf(labeled))
        return mapOf("status" to "submitted", "message" to "观察请求已提交，将在后续画面分析中生效")
    }
}
