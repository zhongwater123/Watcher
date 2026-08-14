package com.example.watcher.data.fitness.agent.planning

internal object FitnessProfilePromptFormatter {
    fun format(profile: FitnessPlanningProfile): String {
        return """
            goalType=${profile.goalType}
            previousAttempt=${profile.previousAttempt}
            targetParts=${profile.targetPartsJson}
            currentWeightKg=${profile.currentWeightKg}
            targetWeightKg=${profile.targetWeightKg ?: "not_required"}
            gender=${profile.gender}
            age=${profile.age}
            heightCm=${profile.heightCm}
            currentBodyType=${profile.currentBodyType}
            targetBodyType=${profile.targetBodyType}
            injuryParts=${profile.injuryPartsJson}
            sedentary=${profile.sedentaryLevel}
            sleep=${profile.sleepQuality}
            diet=${profile.dietHabitsJson}
            exerciseFrequency=${profile.exerciseFrequency}
            preferredPlaces=${profile.preferredPlacesJson}
            gymVisitsPerWeek=${profile.gymVisitsPerWeek}
            equipmentKnowledge=${profile.equipmentKnowledge}
            plankSeconds=${profile.plankSeconds}
            stairFeeling=${profile.stairFeeling}
        """.trimIndent()
    }
}
