package com.example.watcher.data.fitness.agent.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FitnessPlanningResponseParserTest {
    @Test
    fun strategyParserAcceptsJsonWrappedInModelText() {
        val parsed = FitnessStrategyPlanningResponseParser().parse(
            rawResponse = """
                result:
                {"strategy_spec":{"strategy_version":"v1","goals":[{"title":"建立训练习惯","weeks":4}]}}
            """.trimIndent(),
            input = FitnessStrategyGenerationInput(profile())
        )

        assertEquals("v1", parsed.spec.strategyVersion)
        assertEquals("建立训练习惯", parsed.goals.single().title)
    }

    @Test
    fun strategyParserRejectsMissingJson() {
        assertThrows(IllegalArgumentException::class.java) {
            FitnessStrategyPlanningResponseParser().parse(
                rawResponse = "not json",
                input = FitnessStrategyGenerationInput(profile())
            )
        }
    }

    @Test
    fun workoutParserNormalizesSupportedEquipment() {
        val parsed = FitnessWorkoutPlanningResponseParser().parse(
            rawResponse = """
                {"session_plan":{"strategy_version":"v1","exercises":[
                  {"equipment":"推肩机","exercise":"坐姿推肩","work_sets":3,"rep_range":[8,12]}
                ]}}
            """.trimIndent(),
            input = workoutInput()
        )

        assertEquals("推肩", parsed.exercises.single().equipment)
        assertEquals("8-12", parsed.exercises.single().reps)
    }

    @Test
    fun workoutParserRejectsStaleStrategyVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            FitnessWorkoutPlanningResponseParser().parse(
                rawResponse = """
                    {"session_plan":{"strategy_version":"old","exercises":[
                      {"equipment":"推肩","exercise":"坐姿推肩"}
                    ]}}
                """.trimIndent(),
                input = workoutInput()
            )
        }
    }

    @Test
    fun workoutParserRejectsUnsupportedEquipment() {
        assertThrows(IllegalArgumentException::class.java) {
            FitnessWorkoutPlanningResponseParser().parse(
                rawResponse = """
                    {"session_plan":{"strategy_version":"v1","exercises":[
                      {"equipment":"未知器械","exercise":"实验动作"}
                    ]}}
                """.trimIndent(),
                input = workoutInput()
            )
        }
    }

    private fun workoutInput() = FitnessWorkoutGenerationInput(
        profile = profile(),
        strategyVersion = "v1",
        strategySpecJson = "{}",
        dailyContextJson = "{}",
        remainingWeeklyBudgetJson = "{}",
        nextDayOffset = 0,
        currentEpochDay = 1L
    )

    private fun profile() = FitnessPlanningProfile(
        profileId = "default",
        goalType = "GeneralFitness",
        previousAttempt = "",
        targetPartsJson = "[]",
        currentWeightKg = 65f,
        targetWeightKg = null,
        gender = "",
        age = 28,
        heightCm = 170,
        currentBodyType = "",
        targetBodyType = "",
        injuryPartsJson = "[]",
        sedentaryLevel = "",
        sleepQuality = "",
        dietHabitsJson = "[]",
        exerciseFrequency = "",
        preferredPlacesJson = "[]",
        gymVisitsPerWeek = 0,
        equipmentKnowledge = "",
        plankSeconds = 30,
        stairFeeling = ""
    )
}
