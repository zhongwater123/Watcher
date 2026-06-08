package com.example.watcher.localagent.brain

interface LocalAgentBrain {
    val status: LocalAgentBrainStatus

    suspend fun generate(
        systemInstruction: String,
        messages: List<LocalAgentBrainMessage>
    ): String
}
