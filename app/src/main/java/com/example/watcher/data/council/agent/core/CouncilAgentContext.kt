package com.example.watcher.data.council.agent.core

/** All information available to an agent for a single analysis round. */
data class CouncilAgentContext(
    // Scene config
    val sceneType: String,
    val objective: String,
    val focus: String,
    val speakerRole: String,
    val targetRole: String,
    val background: String,

    // Real-time data (timestamped)
    val recentVisual: List<Pair<Long, String>>,
    val recentSpeech: List<Pair<Long, String>>,
    val memoryA: String,
    val memoryB: String,

    // Round info
    val roundNumber: Int,
    val timestamp: Long = System.currentTimeMillis()
)
