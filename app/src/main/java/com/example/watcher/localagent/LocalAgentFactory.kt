package com.example.watcher.localagent

import com.example.watcher.localagent.adk.LocalAgentAdkModel
import com.example.watcher.localagent.brain.LocalAgentBrain
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent

object LocalAgentFactory {

    fun createDeviceAssistant(
        brain: LocalAgentBrain
    ): LlmAgent {
        return LlmAgent(
            name = "watcher_local_device_assistant",
            description = "Watcher local on-device assistant powered by the injected local brain.",
            model = LocalAgentAdkModel(brain),
            instruction = Instruction(
                "You are Watcher's local on-device assistant. Answer in concise Simplified Chinese."
            )
        )
    }
}
