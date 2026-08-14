package com.example.watcher.data.repository

import android.content.Context
import com.example.watcher.data.local.AppDatabase
import com.example.watcher.data.local.ExerciseLibraryDao
import com.example.watcher.data.local.FitnessExerciseWithDetails
import com.example.watcher.data.model.FitnessExerciseEntity
import com.example.watcher.data.model.FitnessExerciseInstructionEntity
import com.example.watcher.data.model.FitnessExerciseInstructionStepEntity
import com.example.watcher.data.model.FitnessExerciseLibraryMetaEntity
import com.example.watcher.data.model.FitnessExerciseSecondaryMuscleEntity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class ExerciseLibraryItem(
    val id: String = "",
    val name: String = "",
    val displayNameZh: String = "",
    val category: String = "",
    val bodyPart: String = "",
    val equipment: String = "",
    val target: String = "",
    val muscleGroup: String = "",
    val secondaryMuscles: List<String> = emptyList(),
    val stepsZh: List<String> = emptyList(),
    val stepsByLanguage: Map<String, List<String>> = emptyMap(),
    val instructionsByLanguage: Map<String, String> = emptyMap(),
    val image: String = "",
    val gifUrl: String = "",
    val attribution: String = "",
    val mediaId: String = "",
    val createdAt: String = "",
    val searchText: String = ""
) {
    val title: String get() = displayNameZh.ifBlank { name }

    fun stepsFor(language: String): List<String> {
        return stepsByLanguage[language].orEmpty()
            .ifEmpty { stepsZh }
            .ifEmpty { instructionsByLanguage[language]?.let(::listOf).orEmpty() }
            .ifEmpty { instructionsByLanguage["zh"]?.let(::listOf).orEmpty() }
            .ifEmpty { instructionsByLanguage["en"]?.let(::listOf).orEmpty() }
    }
}

data class ExerciseLibraryQuery(
    val query: String = "",
    val categories: Set<String> = emptySet(),
    val bodyParts: Set<String> = emptySet(),
    val equipment: Set<String> = emptySet(),
    val targets: Set<String> = emptySet(),
    val page: Int = 0,
    val pageSize: Int = 60
)

data class ExerciseLibraryFacets(
    val categories: List<String> = emptyList(),
    val bodyParts: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val targetsByBodyPart: Map<String, List<String>> = emptyMap(),
    val targetCountsByBodyPart: Map<String, Map<String, Int>> = emptyMap(),
    val equipmentCounts: Map<String, Int> = emptyMap()
)

data class ExerciseLibraryUiState(
    val loading: Boolean = true,
    val exercises: List<ExerciseLibraryItem> = emptyList(),
    val facets: ExerciseLibraryFacets = ExerciseLibraryFacets(),
    val totalCount: Int = 0,
    val errorMessage: String = ""
)

class ExerciseLibraryRepository(
    private val context: Context,
    private val dao: ExerciseLibraryDao = AppDatabase.getDatabase(context).exerciseLibraryDao()
) {
    private val gson = Gson()

    @Volatile
    private var cachedExercises: List<ExerciseLibraryItem>? = null

    suspend fun loadExercises(): List<ExerciseLibraryItem> = withContext(Dispatchers.IO) {
        ensureImported()
        cachedExercises ?: dao.getAllExerciseDetails()
            .map { it.toLibraryItem() }
            .also { cachedExercises = it }
    }

    suspend fun getExerciseDetail(id: String): ExerciseLibraryItem? = withContext(Dispatchers.IO) {
        ensureImported()
        dao.getExerciseDetail(id)?.toLibraryItem()
    }

    suspend fun getFacets(): ExerciseLibraryFacets = withContext(Dispatchers.IO) {
        val exercises = loadExercises()
        buildFacets(exercises)
    }

    suspend fun queryExercises(query: ExerciseLibraryQuery): List<ExerciseLibraryItem> = withContext(Dispatchers.IO) {
        filterExercises(loadExercises(), query)
    }

    private suspend fun ensureImported() {
        val sourceHash = calculateAssetHash(EXERCISE_LIBRARY_JSON)
        val meta = dao.getMeta(DATASET_ID)
        if (meta?.sourceHash == sourceHash && meta.exerciseCount == dao.countExercises()) return

        val sourceExercises = context.assets.open(EXERCISE_LIBRARY_JSON).use { input ->
            input.reader(Charsets.UTF_8).use { reader ->
                val type = object : TypeToken<List<SourceExercise>>() {}.type
                gson.fromJson<List<SourceExercise>>(reader.readText().removePrefix("\uFEFF"), type)
            }
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }

        val exercises = ArrayList<FitnessExerciseEntity>(sourceExercises.size)
        val instructions = ArrayList<FitnessExerciseInstructionEntity>(sourceExercises.size * SUPPORTED_LANGUAGES.size)
        val steps = ArrayList<FitnessExerciseInstructionStepEntity>(sourceExercises.size * 5)
        val muscles = ArrayList<FitnessExerciseSecondaryMuscleEntity>(sourceExercises.size * 2)

        sourceExercises.forEach { source ->
            val secondaryMuscles = source.secondaryMuscles.filter(String::isNotBlank).distinct()
            val displayNameZh = ExerciseChineseLabels.displayNameFor(source)
            val searchText = ExerciseChineseLabels.searchTextFor(source, displayNameZh, secondaryMuscles)

            exercises += FitnessExerciseEntity(
                id = source.id,
                name = source.name,
                displayNameZh = displayNameZh,
                category = source.category,
                bodyPart = source.bodyPart,
                equipment = source.equipment,
                muscleGroup = source.muscleGroup,
                target = source.target,
                mediaId = source.mediaId,
                image = source.image,
                gifUrl = source.gifUrl,
                attribution = source.attribution,
                createdAt = source.createdAt,
                searchText = searchText
            )

            source.instructions.forEach { (language, fullText) ->
                if (language in SUPPORTED_LANGUAGES && fullText.isNotBlank()) {
                    instructions += FitnessExerciseInstructionEntity(source.id, language, fullText)
                }
            }
            source.instructionSteps.forEach { (language, languageSteps) ->
                if (language in SUPPORTED_LANGUAGES) {
                    languageSteps.filter(String::isNotBlank).forEachIndexed { index, step ->
                        steps += FitnessExerciseInstructionStepEntity(source.id, language, index, step)
                    }
                }
            }
            secondaryMuscles.forEach { muscle ->
                muscles += FitnessExerciseSecondaryMuscleEntity(source.id, muscle)
            }
        }

        dao.replaceLibrary(
            meta = FitnessExerciseLibraryMetaEntity(
                datasetId = DATASET_ID,
                sourceHash = sourceHash,
                exerciseCount = exercises.size,
                importedAt = System.currentTimeMillis()
            ),
            exercises = exercises,
            instructions = instructions,
            steps = steps,
            muscles = muscles
        )
        cachedExercises = null
    }

    private fun calculateAssetHash(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun FitnessExerciseWithDetails.toLibraryItem(): ExerciseLibraryItem {
        val stepsByLanguage = steps
            .groupBy { it.language }
            .mapValues { (_, value) -> value.sortedBy { it.stepIndex }.map { it.text } }
        return ExerciseLibraryItem(
            id = exercise.id,
            name = exercise.name,
            displayNameZh = exercise.displayNameZh,
            category = exercise.category,
            bodyPart = exercise.bodyPart,
            equipment = exercise.equipment,
            target = exercise.target,
            muscleGroup = exercise.muscleGroup,
            secondaryMuscles = secondaryMuscles.map { it.muscle },
            stepsZh = stepsByLanguage["zh"].orEmpty(),
            stepsByLanguage = stepsByLanguage,
            instructionsByLanguage = instructions.associate { it.language to it.fullText },
            image = exercise.image,
            gifUrl = exercise.gifUrl,
            attribution = exercise.attribution,
            mediaId = exercise.mediaId,
            createdAt = exercise.createdAt,
            searchText = exercise.searchText
        )
    }

    private data class SourceExercise(
        val id: String = "",
        val name: String = "",
        val category: String = "",
        @SerializedName("body_part") val bodyPart: String = "",
        val equipment: String = "",
        val instructions: Map<String, String> = emptyMap(),
        @SerializedName("instruction_steps") val instructionSteps: Map<String, List<String>> = emptyMap(),
        @SerializedName("muscle_group") val muscleGroup: String = "",
        @SerializedName("secondary_muscles") val secondaryMuscles: List<String> = emptyList(),
        val target: String = "",
        @SerializedName("media_id") val mediaId: String = "",
        val image: String = "",
        @SerializedName("gif_url") val gifUrl: String = "",
        val attribution: String = "",
        @SerializedName("created_at") val createdAt: String = ""
    )

    companion object {
        const val EXERCISE_LIBRARY_ASSET_ROOT = "exercise_library"
        const val EXERCISE_LIBRARY_JSON = "$EXERCISE_LIBRARY_ASSET_ROOT/data/exercises.json"
        private const val DATASET_ID = "hasaneyldrm-exercises-dataset"
        private val SUPPORTED_LANGUAGES = setOf("en", "es", "it", "tr", "ru", "zh", "hi", "pl", "ko", "fr")

        fun buildFacets(exercises: List<ExerciseLibraryItem>): ExerciseLibraryFacets {
            val targetCountsByBodyPart = exercises
                .filter { it.bodyPart.isNotBlank() && it.target.isNotBlank() }
                .groupBy { it.bodyPart }
                .mapValues { (_, bodyPartExercises) ->
                    bodyPartExercises
                        .groupingBy { it.target }
                        .eachCount()
                        .toList()
                        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                        .toMap()
                }
            val targetsByBodyPart = targetCountsByBodyPart.mapValues { (_, counts) -> counts.keys.toList() }
            val equipmentCounts = exercises
                .map { it.equipment }
                .filter(String::isNotBlank)
                .groupingBy { it }
                .eachCount()
            return ExerciseLibraryFacets(
                categories = exercises.map { it.category }.filter(String::isNotBlank).distinct().sorted(),
                bodyParts = exercises.map { it.bodyPart }.filter(String::isNotBlank).distinct().sortedWith(bodyPartComparator),
                equipment = equipmentCounts.toList()
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                    .map { it.first },
                targets = exercises.map { it.target }.filter(String::isNotBlank).distinct().sorted(),
                targetsByBodyPart = targetsByBodyPart,
                targetCountsByBodyPart = targetCountsByBodyPart,
                equipmentCounts = equipmentCounts
            )
        }

        private val bodyPartComparator = compareBy<String> { value ->
            BODY_PART_ORDER.indexOf(value).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }.thenBy { it }

        private val BODY_PART_ORDER = listOf(
            "chest",
            "back",
            "shoulders",
            "upper arms",
            "lower arms",
            "upper legs",
            "lower legs",
            "waist",
            "cardio",
            "neck"
        )

        fun filterExercises(
            exercises: List<ExerciseLibraryItem>,
            query: ExerciseLibraryQuery
        ): List<ExerciseLibraryItem> {
            val normalizedQuery = normalizeSearchValue(query.query)
            val filtered = exercises.filter { item ->
                val matchesQuery = normalizedQuery.isBlank() || normalizeSearchValue(item.searchText).contains(normalizedQuery)
                val matchesCategory = query.categories.isEmpty() || item.category in query.categories
                val matchesBodyPart = query.bodyParts.isEmpty() || item.bodyPart in query.bodyParts
                val matchesEquipment = query.equipment.isEmpty() || item.equipment in query.equipment
                val matchesTarget = query.targets.isEmpty() || item.target in query.targets
                matchesQuery && matchesCategory && matchesBodyPart && matchesEquipment && matchesTarget
            }
            val pageSize = query.pageSize.coerceAtLeast(1)
            val from = query.page.coerceAtLeast(0)
                .toLong()
                .times(pageSize.toLong())
                .coerceAtMost(filtered.size.toLong())
                .toInt()
            val to = from.toLong()
                .plus(pageSize.toLong())
                .coerceAtMost(filtered.size.toLong())
                .toInt()
            return filtered.subList(from, to)
        }

        fun filterExercises(
            exercises: List<ExerciseLibraryItem>,
            query: String,
            bodyPart: String,
            equipment: String
        ): List<ExerciseLibraryItem> {
            return filterExercises(
                exercises = exercises,
                query = ExerciseLibraryQuery(
                    query = query,
                    bodyParts = setOfNotBlank(bodyPart),
                    equipment = setOfNotBlank(equipment),
                    pageSize = Int.MAX_VALUE
                )
            )
        }

        fun findBestMatch(
            exercises: List<ExerciseLibraryItem>,
            exerciseName: String
        ): ExerciseLibraryItem? {
            val normalizedName = normalizeExerciseName(exerciseName)
            if (normalizedName.isBlank()) return null

            exercises.firstOrNull {
                normalizeExerciseName(it.name) == normalizedName ||
                    normalizeExerciseName(it.displayNameZh) == normalizedName
            }?.let { return it }

            exercises.firstOrNull { item ->
                val candidate = normalizeExerciseName("${item.name} ${item.displayNameZh} ${item.searchText}")
                candidate.contains(normalizedName)
            }?.let { return it }

            val tokens = normalizedName.split(" ").filter { it.length >= 2 }
            if (tokens.isEmpty()) return null
            return exercises
                .mapNotNull { item ->
                    val candidate = normalizeExerciseName("${item.name} ${item.displayNameZh} ${item.searchText}")
                    val score = tokens.count { token -> candidate.contains(token) }
                    if (score <= 0) null else item to score
                }
                .filter { (_, score) -> score >= minOf(2, tokens.size) }
                .maxWithOrNull(
                    compareBy<Pair<ExerciseLibraryItem, Int>> { it.second }
                        .thenByDescending { it.first.displayNameZh.length + it.first.name.length }
                )
                ?.first
        }

        fun normalizeExerciseName(value: String): String = normalizeSearchValue(value)

        private fun setOfNotBlank(value: String): Set<String> = value.takeIf(String::isNotBlank)?.let(::setOf).orEmpty()

        private fun normalizeSearchValue(value: String): String {
            return value
                .lowercase()
                .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
                .trim()
                .replace("\\s+".toRegex(), " ")
        }
    }

    private object ExerciseChineseLabels {
        private val exactNames = mapOf(
            "3/4 sit-up" to "四分之三仰卧起坐",
            "barbell bench press" to "杠铃卧推",
            "barbell full squat" to "杠铃深蹲",
            "barbell deadlift" to "杠铃硬拉",
            "dumbbell biceps curl" to "哑铃肱二头肌弯举",
            "dumbbell lateral raise" to "哑铃侧平举",
            "pull-up" to "引体向上",
            "push-up" to "俯卧撑",
            "plank" to "平板支撑"
        )

        private val bodyPartLabels = mapOf(
            "back" to "背部",
            "cardio" to "有氧",
            "chest" to "胸部",
            "lower arms" to "前臂",
            "lower legs" to "小腿",
            "neck" to "颈部",
            "shoulders" to "肩部",
            "upper arms" to "上臂",
            "upper legs" to "大腿",
            "waist" to "腰腹"
        )

        private val equipmentLabels = mapOf(
            "assisted" to "辅助器械",
            "band" to "弹力带",
            "barbell" to "杠铃",
            "body weight" to "自重",
            "bosu ball" to "波速球",
            "cable" to "绳索",
            "dumbbell" to "哑铃",
            "ez barbell" to "EZ 杠",
            "kettlebell" to "壶铃",
            "leverage machine" to "固定器械",
            "medicine ball" to "药球",
            "olympic barbell" to "奥杆",
            "resistance band" to "阻力带",
            "roller" to "泡沫轴",
            "rope" to "绳",
            "sled machine" to "雪橇机",
            "smith machine" to "史密斯机",
            "stability ball" to "瑜伽球",
            "weighted" to "负重",
            "wheel roller" to "健腹轮"
        )

        private val targetLabels = mapOf(
            "abs" to "腹肌",
            "adductors" to "内收肌",
            "biceps" to "肱二头肌",
            "calves" to "小腿",
            "cardiovascular system" to "心肺",
            "delts" to "三角肌",
            "forearms" to "前臂",
            "glutes" to "臀肌",
            "hamstrings" to "腘绳肌",
            "lats" to "背阔肌",
            "levator scapulae" to "肩胛提肌",
            "pectorals" to "胸肌",
            "quads" to "股四头肌",
            "serratus anterior" to "前锯肌",
            "spine" to "竖脊肌",
            "traps" to "斜方肌",
            "triceps" to "肱三头肌",
            "upper back" to "上背"
        )

        private val movementLabels = listOf(
            "pull-up" to "引体向上",
            "push-up" to "俯卧撑",
            "sit-up" to "仰卧起坐",
            "bench press" to "卧推",
            "deadlift" to "硬拉",
            "squat" to "深蹲",
            "lunge" to "弓步",
            "curl" to "弯举",
            "extension" to "伸展",
            "press" to "推举",
            "row" to "划船",
            "raise" to "平举",
            "fly" to "飞鸟",
            "crunch" to "卷腹",
            "plank" to "平板支撑",
            "bridge" to "桥式",
            "stretch" to "拉伸",
            "shrug" to "耸肩",
            "rotation" to "旋转",
            "twist" to "转体",
            "walk" to "行走",
            "run" to "跑步"
        )

        fun displayNameFor(exercise: SourceExercise): String {
            val normalized = exercise.name.lowercase()
            exactNames[normalized]?.let { return it }
            val equipment = equipmentLabels[exercise.equipment].orEmpty()
            val target = targetLabels[exercise.target].orEmpty()
                .ifBlank { bodyPartLabels[exercise.bodyPart].orEmpty() }
            val movement = movementLabels.firstOrNull { (token, _) -> normalized.contains(token) }?.second.orEmpty()
            return listOf(equipment, target, movement)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("")
                .ifBlank { exercise.name }
        }

        fun searchTextFor(
            exercise: SourceExercise,
            displayNameZh: String,
            secondaryMuscles: List<String>
        ): String {
            val localizedValues = listOf(
                bodyPartLabels[exercise.bodyPart],
                equipmentLabels[exercise.equipment],
                targetLabels[exercise.target],
                targetLabels[exercise.muscleGroup],
                simpleMovementAliasFor(exercise)
            ) + secondaryMuscles.mapNotNull { targetLabels[it] }
            return buildList {
                add(exercise.name)
                add(displayNameZh)
                add(exercise.category)
                add(exercise.bodyPart)
                add(exercise.equipment)
                add(exercise.target)
                add(exercise.muscleGroup)
                addAll(secondaryMuscles)
                addAll(localizedValues.filterNotNull())
            }.joinToString(" ")
        }

        private fun simpleMovementAliasFor(exercise: SourceExercise): String {
            val equipment = equipmentLabels[exercise.equipment].orEmpty()
            val movement = movementLabels.firstOrNull { (token, _) -> exercise.name.lowercase().contains(token) }?.second.orEmpty()
            return (equipment + movement).takeIf { it.isNotBlank() }.orEmpty()
        }
    }
}
