package com.example.watcher.data.repository

import com.example.watcher.data.fitness.currentFitnessEpochDay
import com.example.watcher.data.model.FitnessExerciseResultEntity
import com.example.watcher.data.model.FitnessSessionResultEntity
import com.example.watcher.data.model.FitnessStrategySpecEntity
import com.example.watcher.data.model.FitnessUserProfileEntity
import com.example.watcher.data.model.FitnessWeeklyLedgerEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.max
import kotlin.math.roundToInt

class FitnessTrainingLedger {
    private val gson = Gson()

    fun weekStartEpochDay(epochDay: Long = currentFitnessEpochDay()): Long {
        val daysSinceMonday = ((epochDay + 3) % 7 + 7) % 7
        return epochDay - daysSinceMonday
    }

    fun createWeeklyLedger(
        profile: FitnessUserProfileEntity,
        spec: FitnessStrategySpecEntity,
        weekStartEpochDay: Long = weekStartEpochDay()
    ): FitnessWeeklyLedgerEntity {
        val remaining = initialRemainingBudget(spec.weeklyBudgetJson)
        return FitnessWeeklyLedgerEntity(
            profileId = profile.profileId,
            strategyVersion = spec.strategyVersion,
            weekStartEpochDay = weekStartEpochDay,
            weekIndex = weekIndex(spec, weekStartEpochDay),
            weeklyBudgetJson = spec.weeklyBudgetJson,
            actualsJson = "{}",
            remainingBudgetJson = gson.toJson(remaining),
            readinessTrendJson = "[]",
            painTrendJson = "[]"
        )
    }

    fun buildDailyContext(
        profile: FitnessUserProfileEntity,
        spec: FitnessStrategySpecEntity,
        ledger: FitnessWeeklyLedgerEntity,
        nextDayOffset: Int
    ): String {
        val context = JsonObject()
        context.addProperty("date_epoch_day", currentFitnessEpochDay() + nextDayOffset)
        context.addProperty("available_time_min", defaultSessionDuration(profile))
        context.add("equipment", fixedEquipment())
        context.add("readiness", readiness(profile, ledger))
        context.add("pain", pain(profile, ledger))
        context.add("remaining_weekly_budget", ledger.remainingBudgetJson.asObject())
        context.addProperty("strategy_version", spec.strategyVersion)
        return gson.toJson(context)
    }

    fun validatePlan(
        plan: FitnessWorkoutPlanEntity,
        exercises: List<FitnessWorkoutExerciseEntity>,
        ledger: FitnessWeeklyLedgerEntity
    ): FitnessPlanValidationResult {
        if (plan.strategyVersion != ledger.strategyVersion) {
            return FitnessPlanValidationResult(false, "daily plan strategy_version does not match active ledger")
        }
        val remaining = ledger.remainingBudgetJson.asObject()
        val expected = plan.expectedBudgetUsageJson.asObject().takeIf { it.size() > 0 }
            ?: expectedUsageFromExercises(exercises)
        val exceeded = mutableListOf<String>()
        expected.entrySet().forEach { (key, value) ->
            val planned = value.asBudgetAmountOrNull() ?: return@forEach
            val left = remaining.remainingFor(key) ?: return@forEach
            if (planned > left + 0.01f) exceeded += "$key planned=$planned remaining=$left"
        }
        return if (exceeded.isEmpty()) {
            FitnessPlanValidationResult(true)
        } else {
            FitnessPlanValidationResult(false, "daily plan exceeds remaining weekly budget: ${exceeded.joinToString("; ")}")
        }
    }

    fun buildSessionResult(
        plan: FitnessWorkoutPlanEntity,
        exercises: List<FitnessWorkoutExerciseEntity>,
        completionLevel: String,
        fatigueLevel: String,
        painSignal: String,
        noteOption: String,
        completedAt: Long,
        mediaPipeRepCountsByExerciseId: Map<Long, Int> = emptyMap()
    ): FitnessSessionResultDraft {
        val completionRate = completionRate(completionLevel)
        val painScore = painScore(painSignal)
        val sessionRpe = sessionRpe(fatigueLevel)
        val painEvents = JsonArray()
        if (painScore >= REPLAN_PAIN_SCORE) {
            val event = JsonObject()
            event.addProperty("score", painScore)
            event.addProperty("signal", painSignal)
            painEvents.add(event)
        }
        val raw = JsonObject()
        raw.addProperty("completion_level", completionLevel)
        raw.addProperty("fatigue_level", fatigueLevel)
        raw.addProperty("pain_signal", painSignal)
        raw.addProperty("note_option", noteOption)
        raw.add("actual_work", expectedUsageFromExercises(exercises, completionRate))
        raw.add("media_pipe_rep_counts", mediaPipeRepCountsByExerciseId.toJsonObject())
        val result = FitnessSessionResultEntity(
            profileId = plan.profileId,
            planId = plan.id,
            sessionId = plan.sessionId,
            strategyVersion = plan.strategyVersion,
            completionRate = completionRate,
            actualDurationMin = (plan.estimatedMinutes * completionRate).roundToInt(),
            sessionRpe = sessionRpe,
            painEventsJson = gson.toJson(painEvents),
            unexpectedFatigue = fatigueLevel == "太累了",
            userFeedback = noteOption,
            postSessionReadiness = max(0, (75 - sessionRpe * 5 - painScore * 4).roundToInt()),
            rawJson = gson.toJson(raw),
            completedAt = completedAt
        )
        val exerciseResults = exercises.map { exercise ->
            FitnessExerciseResultEntity(
                sessionResultId = 0,
                exerciseId = exercise.id,
                actualSets = (exercise.sets * completionRate).roundToInt(),
                actualReps = mediaPipeRepCountsByExerciseId[exercise.id]?.toString().orEmpty(),
                actualRpe = sessionRpe,
                actualRir = exercise.targetRir,
                completionStatus = completionLevel,
                painScore = painScore,
                substituted = false,
                unfinishedReason = if (completionRate < 1f) noteOption else ""
            )
        }
        return FitnessSessionResultDraft(result, exerciseResults)
    }

    fun applySessionResult(
        ledger: FitnessWeeklyLedgerEntity,
        result: FitnessSessionResultEntity,
        exercises: List<FitnessWorkoutExerciseEntity>
    ): FitnessWeeklyLedgerEntity {
        val actuals = ledger.actualsJson.asObject()
        val remaining = ledger.remainingBudgetJson.asObject()
        val used = expectedUsageFromExercises(exercises, result.completionRate)
        used.entrySet().forEach { (key, value) ->
            val amount = value.asNumberOrNull() ?: return@forEach
            actuals.addProperty(key, (actuals.get(key)?.asNumberOrNull() ?: 0f) + amount)
            val oldRemaining = remaining.get(key)?.asNumberOrNull()
            if (oldRemaining != null) remaining.addProperty(key, max(0f, oldRemaining - amount))
        }
        val painTrend = ledger.painTrendJson.asArray()
        painTrend.add(result.painEventsJson.asArray())
        val readinessTrend = ledger.readinessTrendJson.asArray()
        readinessTrend.add(result.postSessionReadiness)
        val replanReason = replanReason(result)
        return ledger.copy(
            actualsJson = gson.toJson(actuals),
            remainingBudgetJson = gson.toJson(remaining),
            readinessTrendJson = gson.toJson(readinessTrend),
            painTrendJson = gson.toJson(painTrend),
            replanRequired = replanReason.isNotBlank() || ledger.replanRequired,
            replanReason = replanReason.ifBlank { ledger.replanReason },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun initialRemainingBudget(weeklyBudgetJson: String): JsonObject {
        val budget = weeklyBudgetJson.asObject()
        val remaining = JsonObject()
        val muscleGroups = budget.objectOrEmpty("muscle_group_sets")
        muscleGroups.entrySet().forEach { (muscle, value) ->
            val target = value.asBudgetAmountOrNull() ?: return@forEach
            remaining.addProperty("${muscle}_sets", target)
        }
        val cardio = budget.objectOrEmpty("cardio")
        cardio.get("zone2_minutes")?.asBudgetAmountOrNull()?.let { remaining.addProperty("zone2_minutes", it) }
        cardio.get("minutes")?.asBudgetAmountOrNull()?.let {
            if (!remaining.has("zone2_minutes")) remaining.addProperty("zone2_minutes", it)
        }
        budget.get("zone2_minutes")?.asBudgetAmountOrNull()?.let {
            if (!remaining.has("zone2_minutes")) remaining.addProperty("zone2_minutes", it)
        }
        budget.get("cardio_minutes")?.asBudgetAmountOrNull()?.let {
            if (!remaining.has("zone2_minutes")) remaining.addProperty("zone2_minutes", it)
        }
        if (remaining.size() == 0) {
            listOf("chest", "back", "quads", "hamstrings", "glutes", "shoulders").forEach {
                remaining.addProperty("${it}_sets", 6)
            }
            remaining.addProperty("zone2_minutes", 60)
        }
        remaining.addProperty("available_training_days", 3)
        return remaining
    }

    private fun expectedUsageFromExercises(
        exercises: List<FitnessWorkoutExerciseEntity>,
        multiplier: Float = 1f
    ): JsonObject {
        val usage = JsonObject()
        exercises.forEach { exercise ->
            if (exercise.durationSeconds > 0 || exercise.category.contains("有氧")) {
                val minutes = if (exercise.durationSeconds > 0) exercise.durationSeconds / 60f else exercise.sets.toFloat()
                usage.addProperty("zone2_minutes", (usage.get("zone2_minutes")?.asNumberOrNull() ?: 0f) + minutes * multiplier)
            } else {
                exercise.muscles().forEach { muscle ->
                    val key = "${muscle}_sets"
                    usage.addProperty(key, (usage.get(key)?.asNumberOrNull() ?: 0f) + exercise.sets * multiplier)
                }
            }
        }
        return usage
    }

    private fun FitnessWorkoutExerciseEntity.muscles(): List<String> {
        val parsed = targetMusclesJson.asArray().mapNotNull { it.asStringOrNull() }.map(::normalizeMuscle)
        if (parsed.isNotEmpty()) return parsed
        return when {
            name.contains("推胸") || name.contains("卧推") || equipment.contains("推胸") -> listOf("chest")
            name.contains("下拉") || name.contains("划船") || name.contains("引体") -> listOf("back")
            name.contains("蹬腿") || name.contains("深蹲") -> listOf("quads")
            name.contains("髋") || name.contains("臀") -> listOf("glutes")
            name.contains("推肩") || equipment.contains("推肩") -> listOf("shoulders")
            else -> listOf("core")
        }
    }

    private fun normalizeMuscle(muscle: String): String = when (muscle.lowercase()) {
        "胸", "胸部", "chest", "pectorals" -> "chest"
        "背", "背部", "back", "lats" -> "back"
        "股四头", "大腿前侧", "quads", "quadriceps" -> "quads"
        "腘绳肌", "大腿后侧", "hamstrings" -> "hamstrings"
        "臀", "臀部", "glutes" -> "glutes"
        "肩", "肩部", "shoulders", "delts" -> "shoulders"
        "核心", "腹", "core", "abs" -> "core"
        else -> muscle.lowercase()
    }

    private fun replanReason(result: FitnessSessionResultEntity): String {
        return when {
            result.painEventsJson.asArray().size() > 0 -> "pain_score >= $REPLAN_PAIN_SCORE"
            result.completionRate < 0.55f -> "session_completion_rate < 0.55"
            result.unexpectedFatigue -> "unexpected_fatigue"
            else -> ""
        }
    }

    private fun weekIndex(spec: FitnessStrategySpecEntity, weekStartEpochDay: Long): Int {
        val weeks = ((weekStartEpochDay - spec.createdAt / FITNESS_DAY_MS) / 7L).toInt() + 1
        return weeks.coerceAtLeast(1)
    }

    private fun defaultSessionDuration(profile: FitnessUserProfileEntity): Int {
        return when {
            profile.gymVisitsPerWeek >= 3 -> 60
            profile.exerciseFrequency == "几乎不运动" -> 35
            else -> 45
        }
    }

    private fun readiness(profile: FitnessUserProfileEntity, ledger: FitnessWeeklyLedgerEntity): JsonObject {
        val readiness = JsonObject()
        val base = when (profile.sleepQuality) {
            "很好" -> 78
            "一般" -> 66
            "经常熬夜", "睡眠不足" -> 52
            else -> 62
        }
        val fatiguePenalty = if (ledger.replanRequired) 10 else 0
        readiness.addProperty("score", (base - fatiguePenalty).coerceIn(0, 100))
        readiness.addProperty("sleep_quality", profile.sleepQuality)
        return readiness
    }

    private fun pain(profile: FitnessUserProfileEntity, ledger: FitnessWeeklyLedgerEntity): JsonObject {
        val pain = JsonObject()
        profile.injuryPartsJson.asArray().forEach {
            val label = it.asStringOrNull() ?: return@forEach
            if (label != "无") pain.addProperty(label, 2)
        }
        if (ledger.replanRequired) pain.addProperty("recent_warning", REPLAN_PAIN_SCORE)
        return pain
    }

    private fun fixedEquipment(): JsonArray {
        val equipment = JsonArray()
        listOf(
            "跑步机", "单车", "太空漫步机", "划船机", "推胸", "推肩", "高位下拉", "夹胸", "蹬腿", "髋内外展",
            "史密斯架", "平卧推", "哑铃凳", "绳索", "引体向上", "罗马椅", "瑜伽垫", "瑜伽球", "泡沫轴", "腹肌板"
        ).forEach { equipment.add(it) }
        return equipment
    }

    private fun completionRate(level: String): Float = when (level) {
        "全部完成" -> 1f
        "基本完成" -> 0.85f
        "完成一半" -> 0.5f
        else -> 0.75f
    }

    private fun painScore(signal: String): Int = when (signal) {
        "0分" -> 0
        "2分" -> 2
        "4分" -> 4
        "6分" -> 6
        "没有疼痛" -> 0
        "轻微不适" -> 2
        "明显疼痛" -> 4
        else -> 0
    }

    private fun sessionRpe(fatigue: String): Float = when (fatigue) {
        "很轻松" -> 5.5f
        "刚刚好" -> 7f
        "有点累" -> 8f
        "太累了" -> 9f
        else -> 7f
    }

    private fun String.asObject(): JsonObject = runCatching {
        JsonParser.parseString(this).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    }.getOrDefault(JsonObject())

    private fun String.asArray(): JsonArray = runCatching {
        JsonParser.parseString(this).takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    }.getOrDefault(JsonArray())

    private fun JsonObject.objectOrEmpty(name: String): JsonObject {
        val element = get(name)
        return if (element != null && element.isJsonObject) element.asJsonObject else JsonObject()
    }

    private fun JsonObject.remainingFor(key: String): Float? {
        return get(key)?.asBudgetAmountOrNull()
            ?: get("${key}_sets")?.asBudgetAmountOrNull()
            ?: key.removeSuffix("_sets").takeIf { it != key }?.let { get(it)?.asBudgetAmountOrNull() }
    }

    private fun JsonElement.asBudgetAmountOrNull(): Float? = runCatching {
        when {
            isJsonPrimitive && asJsonPrimitive.isNumber -> asFloat
            isJsonPrimitive && asJsonPrimitive.isString -> asString.toFloatOrNull()
            isJsonObject -> {
                val obj = asJsonObject
                obj.get("target")?.asNumberOrNull()
                    ?: obj.get("max")?.asNumberOrNull()
                    ?: obj.get("sets")?.asNumberOrNull()
                    ?: obj.get("minutes")?.asNumberOrNull()
                    ?: obj.get("value")?.asNumberOrNull()
            }
            else -> null
        }
    }.getOrNull()

    private fun JsonElement.asNumberOrNull(): Float? = runCatching {
        if (isJsonPrimitive && asJsonPrimitive.isNumber) asFloat else null
    }.getOrNull()

    private fun JsonElement.asStringOrNull(): String? = runCatching {
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    }.getOrNull()

    private fun Map<Long, Int>.toJsonObject(): JsonObject {
        val obj = JsonObject()
        forEach { (exerciseId, reps) ->
            obj.addProperty(exerciseId.toString(), reps.coerceAtLeast(0))
        }
        return obj
    }

    companion object {
        private const val FITNESS_DAY_MS = 86_400_000L
        private const val REPLAN_PAIN_SCORE = 4
    }
}

data class FitnessPlanValidationResult(
    val isValid: Boolean,
    val reason: String = ""
)

data class FitnessSessionResultDraft(
    val sessionResult: FitnessSessionResultEntity,
    val exerciseResults: List<FitnessExerciseResultEntity>
)
