package com.example.watcher.agentframework.core

data class AgentDefinition(
    val agentId: String,
    val name: String,
    val systemInstruction: String,
    val goal: String,
    val description: String = "",
    val metadata: Map<String, String> = emptyMap()
)

data class AgentRunConfig(
    val maxSteps: Int = 8,
    val maxToolCallsPerStep: Int = 4,
    val maxConsecutiveFailures: Int = 2,
    val maxIdleTurns: Int = 2,
    val maxRuntimeMillis: Long = 60_000L,
    val maxWaitMillis: Long = 5_000L,
    val defaultWaitMillis: Long = 300L,
    val maxHistoryItems: Int = 30,
    val toolTimeoutMillis: Long = 30_000L,
    val enableReflectionCorrection: Boolean = true,
    val maxCorrectionAttemptsPerStep: Int = 2,
    val maxCorrectionAttemptsPerRun: Int = 8
)
