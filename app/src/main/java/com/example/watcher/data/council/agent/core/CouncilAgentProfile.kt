package com.example.watcher.data.council.agent.core

/** Immutable identity of an agent expert. */
data class CouncilAgentProfile(
    val name: String,
    val description: String,
    val persona: String,
    val perspective: String,
    val expertKind: String = "specialist"
)
