package com.example.watcher.localagent.adkprobe

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent

object LocalAgentQuickstartAgent {
    @JvmField
    val rootAgent = LlmAgent(
        name = "local_agent_quickstart_probe",
        description = "Verifies the Kotlin ADK quickstart shape inside Watcher.",
        model = ProbeModel(),
        instruction = Instruction(
            "You are a local quickstart probe. Use the getCurrentTime tool when asked for time."
        ),
        tools = TimeService().generatedTools()
    )

    fun inspect(): LocalAgentProbeReport {
        return LocalAgentProbeReport(
            dependencyLoaded = true,
            generatedToolsAvailable = rootAgent.tools.isNotEmpty(),
            agentDefinitionCreated = true,
            agentName = rootAgent.name,
            toolNames = rootAgent.tools.map { it.name },
            errorMessage = null
        )
    }
}
