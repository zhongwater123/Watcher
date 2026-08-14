package com.example.watcher.data.council.agent.core

/** Pure analysis output from an agent. No side effects — those happen via tool calls. */
data class CouncilAgentOpinion(
    val summary: String,
    val findings: List<String>,
    val risks: List<String>,
    val nextActions: List<String>,
    val voteLevel: String,
    val voteReason: String,
    val confidence: Int
)
