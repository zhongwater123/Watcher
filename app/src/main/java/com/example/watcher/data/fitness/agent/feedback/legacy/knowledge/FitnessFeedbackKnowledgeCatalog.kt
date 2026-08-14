package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs.*
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity

object FitnessFeedbackKnowledgeCatalog {
    private val packs = listOf(
        FitnessSeatedShoulderPressFeedbackKnowledge.pack,
        FitnessSeatedChestPressFeedbackKnowledge.pack,
        FitnessSeatedHipAbductionAdductionFeedbackKnowledge.pack,
        FitnessLatPulldownFeedbackKnowledge.pack,
        FitnessPecDeckFeedbackKnowledge.pack,
        FitnessLegPressFeedbackKnowledge.pack,
        FitnessCableMachineFeedbackKnowledge.pack,
        FitnessSmithMachineFeedbackKnowledge.pack,
        FitnessAdjustableDumbbellBenchFeedbackKnowledge.pack,
        FitnessFlatBenchPressMachineFeedbackKnowledge.pack,
        FitnessAdjustableAbBenchFeedbackKnowledge.pack,
        FitnessRomanChairFeedbackKnowledge.pack,
        FitnessYogaBallFeedbackKnowledge.pack,
        FitnessFoamRollerFeedbackKnowledge.pack,
        FitnessYogaMatFeedbackKnowledge.pack
    )

    fun findFor(exercise: FitnessWorkoutExerciseEntity?): FitnessFeedbackKnowledgeDefinition? {
        return exercise?.let { target ->
            findFor(
                name = target.name,
                equipment = target.equipment,
                movementPattern = target.movementPattern,
                category = target.category
            )
        }
    }

    fun findFor(
        name: String,
        equipment: String,
        movementPattern: String = "",
        category: String = ""
    ): FitnessFeedbackKnowledgeDefinition? {
        return packs.firstOrNull {
            it.matches(
                name = name,
                equipment = equipment,
                movementPattern = movementPattern,
                category = category
            )
        }
    }

    fun supportedCanonicalEquipment(): Set<String> {
        return packs.mapNotNull { pack -> pack.canonicalEquipment.takeIf { it.isNotBlank() } }.toSet()
    }

    private fun FitnessFeedbackKnowledgeDefinition.matches(
        name: String,
        equipment: String,
        movementPattern: String,
        category: String
    ): Boolean {
        if (canonicalEquipment.isNotBlank() && equipment == canonicalEquipment) return true
        val haystack = listOf(name, equipment, movementPattern, category).joinToString(" ").lowercase()
        return aliases.any { alias -> haystack.contains(alias.lowercase()) }
    }
}
