package com.example.watcher.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseLibraryRepositoryTest {
    private val exercises = listOf(
        ExerciseLibraryItem(
            id = "0001",
            name = "dumbbell biceps curl",
            displayNameZh = "哑铃肱二头肌弯举",
            bodyPart = "upper arms",
            equipment = "dumbbell",
            target = "biceps",
            muscleGroup = "forearms",
            secondaryMuscles = listOf("forearms"),
            stepsZh = listOf("站稳。"),
            searchText = "dumbbell biceps curl 哑铃肱二头肌弯举 upper arms dumbbell biceps forearms 哑铃 肱二头肌 前臂 哑铃弯举"
        ),
        ExerciseLibraryItem(
            id = "0002",
            name = "barbell full squat",
            displayNameZh = "杠铃深蹲",
            bodyPart = "upper legs",
            equipment = "barbell",
            target = "glutes",
            muscleGroup = "quadriceps",
            secondaryMuscles = listOf("quadriceps"),
            stepsZh = listOf("下蹲。"),
            searchText = "barbell full squat 杠铃深蹲 upper legs barbell glutes quadriceps 杠铃 臀肌 股四头肌"
        ),
        ExerciseLibraryItem(
            id = "0003",
            name = "barbell bench press",
            displayNameZh = "杠铃卧推",
            bodyPart = "chest",
            equipment = "barbell",
            target = "pectorals",
            muscleGroup = "triceps",
            secondaryMuscles = listOf("triceps"),
            stepsZh = listOf("卧推。"),
            searchText = "barbell bench press 杠铃卧推 chest barbell pectorals triceps 杠铃 胸肌 肱三头肌"
        )
    )

    @Test
    fun buildFacetsCreatesBodyPartTargetRelationshipAndCounts() {
        val facets = ExerciseLibraryRepository.buildFacets(exercises)

        assertEquals(listOf("chest", "upper arms", "upper legs"), facets.bodyParts)
        assertEquals(listOf("pectorals"), facets.targetsByBodyPart["chest"])
        assertEquals(mapOf("biceps" to 1), facets.targetCountsByBodyPart["upper arms"])
        assertEquals(2, facets.equipmentCounts["barbell"])
    }

    @Test
    fun buildFacetsSortsTargetsWithinBodyPartByCount() {
        val moreChestExercises = exercises + ExerciseLibraryItem(
            id = "0004",
            name = "push-up",
            displayNameZh = "俯卧撑",
            bodyPart = "chest",
            equipment = "body weight",
            target = "pectorals",
            searchText = "push-up 俯卧撑 chest pectorals 胸肌"
        ) + ExerciseLibraryItem(
            id = "0005",
            name = "close-grip push-up",
            displayNameZh = "窄距俯卧撑",
            bodyPart = "chest",
            equipment = "body weight",
            target = "triceps",
            searchText = "close-grip push-up 窄距俯卧撑 chest triceps 肱三头肌"
        )

        val facets = ExerciseLibraryRepository.buildFacets(moreChestExercises)

        assertEquals(listOf("pectorals", "triceps"), facets.targetsByBodyPart["chest"])
        assertEquals(mapOf("pectorals" to 2, "triceps" to 1), facets.targetCountsByBodyPart["chest"])
    }

    @Test
    fun filterExercisesCombinesQueryBodyPartEquipmentAndTarget() {
        val result = ExerciseLibraryRepository.filterExercises(
            exercises = exercises,
            query = ExerciseLibraryQuery(
                query = "barbell",
                bodyParts = setOf("chest"),
                equipment = setOf("barbell"),
                targets = setOf("pectorals"),
                pageSize = 20
            )
        )

        assertEquals(listOf("0003"), result.map { it.id })
    }

    @Test
    fun searchFindsChineseAliasAndTargetMuscle() {
        val byAlias = ExerciseLibraryRepository.filterExercises(
            exercises = exercises,
            query = ExerciseLibraryQuery(query = "哑铃弯举", pageSize = 20)
        )
        val byTarget = ExerciseLibraryRepository.filterExercises(
            exercises = exercises,
            query = ExerciseLibraryQuery(query = "肱二头肌", pageSize = 20)
        )

        assertEquals(listOf("0001"), byAlias.map { it.id })
        assertEquals(listOf("0001"), byTarget.map { it.id })
    }

    @Test
    fun filterExercisesPaginatesResults() {
        val result = ExerciseLibraryRepository.filterExercises(
            exercises = exercises,
            query = ExerciseLibraryQuery(page = 1, pageSize = 1)
        )

        assertEquals(listOf("0002"), result.map { it.id })
    }

    @Test
    fun findBestMatchNormalizesPlanExerciseNames() {
        val result = ExerciseLibraryRepository.findBestMatch(
            exercises = exercises,
            exerciseName = "Dumbbell Biceps Curl!"
        )

        assertEquals("0001", result?.id)
    }

    @Test
    fun findBestMatchUsesChineseAlias() {
        val result = ExerciseLibraryRepository.findBestMatch(
            exercises = exercises,
            exerciseName = "哑铃弯举"
        )

        assertEquals("0001", result?.id)
    }

    @Test
    fun findBestMatchReturnsNullForUnknownExercise() {
        val result = ExerciseLibraryRepository.findBestMatch(
            exercises = exercises,
            exerciseName = "不存在的动作"
        )

        assertNull(result)
    }
}
