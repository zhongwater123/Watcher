package com.example.watcher.data.fitness.agent.planning

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

class FitnessWorkoutPlanningResponseParser(
    private val gson: Gson = Gson()
) {
    fun parse(
        rawResponse: String,
        input: FitnessWorkoutGenerationInput
    ): FitnessGeneratedWorkout {
        val sessionRoot = rawResponse
            .extractFitnessAgentJsonObject()
            .fitnessObjectOrSelf("session_plan")
        val returnedStrategyVersion = sessionRoot.fitnessString(
            "strategy_version",
            sessionRoot.fitnessString("strategyVersion", "")
        )
        require(
            returnedStrategyVersion.isBlank() ||
                returnedStrategyVersion == input.strategyVersion
        ) {
            "Workout agent returned stale strategy_version."
        }
        val exerciseArray = sessionRoot.fitnessArrayOrEmpty(
            "exercises",
            "workout_exercises",
            "movements"
        )
        require(exerciseArray.size() > 0) { "Workout agent returned empty exercises." }

        val plan = FitnessGeneratedWorkoutPlan(
            title = sessionRoot.fitnessString("title", "今日训练计划"),
            objective = sessionRoot.fitnessString("session_goal", "完成一次稳定、可持续的训练"),
            plannedDateEpochDay = input.currentEpochDay + input.nextDayOffset,
            estimatedMinutes = sessionRoot
                .fitnessIntOrNull("estimated_duration_min", "estimatedMinutes")
                ?.coerceIn(15, 120)
                ?: 45,
            intensityLabel = sessionRoot.fitnessString("intensity_label", "中等"),
            warmupJson = gson.toJson(sessionRoot.fitnessArrayOrEmpty("warmup")),
            cooldownJson = gson.toJson(sessionRoot.fitnessArrayOrEmpty("cooldown")),
            coachNotes = sessionRoot.fitnessString("coach_notes", ""),
            sessionId = sessionRoot.fitnessString(
                "session_id",
                "${input.currentEpochDay + input.nextDayOffset}-${input.strategyVersion}-fitness-session"
            ),
            strategyVersion = input.strategyVersion,
            dailyContextJson = input.dailyContextJson,
            sessionPlanJson = sessionRoot.toString(),
            expectedBudgetUsageJson = gson.toJson(
                sessionRoot.fitnessObjectOrEmpty(
                    "expected_weekly_budget_usage",
                    "expected_budget_usage",
                    "expectedBudgetUsage"
                )
            ),
            adjustmentsJson = gson.toJson(sessionRoot.fitnessArrayOrEmpty("adjustments")),
            stopRulesJson = gson.toJson(sessionRoot.fitnessArrayOrEmpty("stop_rules")),
            rawJson = rawResponse
        )
        val exercises = exerciseArray.mapIndexed { index, element ->
            parseExercise(index, element.asFitnessExerciseObject())
        }
        return FitnessGeneratedWorkout(plan = plan, exercises = exercises)
    }

    private fun parseExercise(
        index: Int,
        item: JsonObject
    ): FitnessGeneratedWorkoutExercise {
        val rawEquipment = item.fitnessString("equipment", "瑜伽垫")
        val equipment = normalizeFitnessEquipment(rawEquipment)
        require(equipment in allowedFitnessEquipment) {
            "Workout agent returned unsupported equipment: $rawEquipment"
        }
        val repRange = item.fitnessRangeArray("rep_range", "reps")
        val restRange = item.fitnessRangeArray("rest_seconds", "restSeconds")
        val workSets = item.fitnessIntOrNull("work_sets", "sets")?.coerceAtLeast(0) ?: 3
        val repMin = repRange.fitnessNumberAt(0)?.toInt()?.coerceAtLeast(0) ?: 0
        val repMax = repRange.fitnessNumberAt(1)?.toInt()?.coerceAtLeast(repMin) ?: 0
        val restMin = restRange.fitnessNumberAt(0)?.toInt()?.coerceAtLeast(0) ?: 0
        val restMax = restRange.fitnessNumberAt(1)?.toInt()?.coerceAtLeast(restMin) ?: 0
        return FitnessGeneratedWorkoutExercise(
            sortOrder = index,
            category = item.fitnessString("category", "训练"),
            equipment = equipment,
            name = item.fitnessString("exercise", item.fitnessString("name", "基础训练")),
            sets = workSets,
            reps = if (repMin > 0 && repMax > 0) {
                "$repMin-$repMax"
            } else {
                item.fitnessString("reps", "")
            },
            durationSeconds = item
                .fitnessIntOrNull("durationSeconds", "duration_seconds")
                ?.coerceAtLeast(0)
                ?: 0,
            restSeconds = if (restMax > 0) {
                restMax
            } else {
                item.fitnessIntOrNull("restSeconds", "rest_seconds")?.coerceIn(0, 240) ?: 60
            },
            intensity = item.fitnessString("intensity", "舒适偏挑战"),
            movementPattern = item.fitnessString("movement_pattern", ""),
            targetMusclesJson = gson.toJson(item.fitnessArrayOrEmpty("target_muscles")),
            warmupSetsJson = gson.toJson(item.fitnessArrayOrEmpty("warmup_sets")),
            repRangeMin = repMin,
            repRangeMax = repMax,
            targetRir = item.fitnessFloatOrNull("target_rir", "targetRir") ?: 0f,
            restSecondsMin = restMin,
            restSecondsMax = restMax,
            tempo = item.fitnessString("tempo", ""),
            loadSelectionRule = item.fitnessString("load_selection_rule", ""),
            progressionRule = item.fitnessString("progression_rule", ""),
            substitutionsJson = gson.toJson(item.normalizedFitnessSubstitutions()),
            stopCondition = item.fitnessString("stop_condition", ""),
            priority = item.fitnessString("priority", ""),
            notes = item.fitnessString("notes", "")
        )
    }
}

private val allowedFitnessEquipment = setOf(
    "跑步机", "单车", "太空漫步机", "划船机", "推胸", "推肩", "高位下拉", "夹胸", "蹬腿", "髋内外展",
    "史密斯架", "平卧推", "哑铃凳", "绳索", "引体向上", "罗马椅", "瑜伽垫", "瑜伽球", "泡沫轴", "腹肌板"
)

private fun normalizeFitnessEquipment(value: String): String {
    return when (val trimmed = value.trim()) {
        "推胸器械", "坐姿推胸", "坐姿推胸机", "推胸机" -> "推胸"
        "推肩器械", "推肩机", "坐姿推肩" -> "推肩"
        "高位下拉器械", "高位下拉机", "下拉机" -> "高位下拉"
        "夹胸器械", "夹胸机", "蝴蝶机" -> "夹胸"
        "蹬腿器械", "蹬腿机", "坐姿蹬腿" -> "蹬腿"
        "髋内外展器械", "髋内外展机" -> "髋内外展"
        "卧式单车", "动感单车", "健身单车" -> "单车"
        else -> trimmed
    }
}

private fun JsonObject.normalizedFitnessSubstitutions(): JsonArray {
    val normalized = JsonArray()
    fitnessArrayOrEmpty("substitutions").forEach { element ->
        if (!element.isJsonObject) return@forEach
        val item = element.asJsonObject.deepCopy()
        val equipment = item.fitnessString("equipment", "")
        if (equipment.isNotBlank()) {
            val normalizedEquipment = normalizeFitnessEquipment(equipment)
            if (normalizedEquipment !in allowedFitnessEquipment) return@forEach
            item.addProperty("equipment", normalizedEquipment)
        }
        normalized.add(item)
    }
    return normalized
}
