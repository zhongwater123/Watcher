package com.example.watcher.localagent.brain

data class LocalAgentBrainStatus(
    val isReady: Boolean,
    val label: String,
    val errorMessage: String? = null
)
