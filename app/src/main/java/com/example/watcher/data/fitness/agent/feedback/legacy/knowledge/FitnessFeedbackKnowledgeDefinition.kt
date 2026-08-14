package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge

data class FitnessFeedbackKnowledgeDefinition(
    val id: String,
    val tag: String = "fitness_live.$id",
    val displayName: String,
    val canonicalEquipment: String = "",
    val aliases: List<String>,
    val initialObserverFocus: String
)
