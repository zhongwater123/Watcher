package com.example.watcher.localagent.adkprobe

data class LocalAgentProbeReport(
    val dependencyLoaded: Boolean,
    val generatedToolsAvailable: Boolean,
    val agentDefinitionCreated: Boolean,
    val agentName: String,
    val toolNames: List<String>,
    val errorMessage: String?
) {
    val isSuccessful: Boolean
        get() = dependencyLoaded && generatedToolsAvailable && agentDefinitionCreated && errorMessage == null
}
