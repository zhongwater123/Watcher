package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomTranscriptWeightLevel
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomTranscriptSelectionPolicyTest {
    @Test
    fun inlineQuestionRequiresCoreAndTwoImportantSelections() {
        assertEquals(3, ClassroomTranscriptSelectionPolicy.MIN_SELECTIONS_TO_ASK)
    }

    @Test
    fun weightLevelsFollowSelectionOrder() {
        assertEquals(ClassroomTranscriptWeightLevel.Core, ClassroomTranscriptSelectionPolicy.weightForOrder(1))
        assertEquals(ClassroomTranscriptWeightLevel.Important, ClassroomTranscriptSelectionPolicy.weightForOrder(2))
        assertEquals(ClassroomTranscriptWeightLevel.Important, ClassroomTranscriptSelectionPolicy.weightForOrder(3))
        assertEquals(ClassroomTranscriptWeightLevel.Context, ClassroomTranscriptSelectionPolicy.weightForOrder(4))
        assertEquals(ClassroomTranscriptWeightLevel.Context, ClassroomTranscriptSelectionPolicy.weightForOrder(9))
    }

    @Test
    fun togglingExistingSelectionReordersRemainingItems() {
        val selections = listOf(
            ClassroomTranscriptSelection(transcriptId = 10, selectionOrder = 1),
            ClassroomTranscriptSelection(transcriptId = 11, selectionOrder = 2),
            ClassroomTranscriptSelection(transcriptId = 12, selectionOrder = 3),
            ClassroomTranscriptSelection(transcriptId = 13, selectionOrder = 4)
        )

        val updated = ClassroomTranscriptSelectionPolicy.toggleSelection(selections, transcriptId = 11)

        assertEquals(listOf(10L, 12L, 13L), updated.map { it.transcriptId })
        assertEquals(listOf(1, 2, 3), updated.map { it.selectionOrder })
        assertEquals(
            listOf(
                ClassroomTranscriptWeightLevel.Core,
                ClassroomTranscriptWeightLevel.Important,
                ClassroomTranscriptWeightLevel.Important
            ),
            updated.map { it.weightLevel }
        )
    }

    @Test
    fun contextWindowUsesTwoMinutesBeforeAndOneMinuteAfterSelectedRange() {
        val transcripts = listOf(
            transcript(1, start = 0, end = 1_000, text = "before too far"),
            transcript(2, start = 30_000, end = 31_000, text = "before in range"),
            transcript(3, start = 150_000, end = 151_000, text = "core"),
            transcript(4, start = 180_000, end = 181_000, text = "context"),
            transcript(5, start = 230_000, end = 231_000, text = "after in range"),
            transcript(6, start = 245_000, end = 246_000, text = "after too far")
        )
        val selected = listOf(
            ClassroomTranscriptSelection(transcriptId = 3, selectionOrder = 1),
            ClassroomTranscriptSelection(transcriptId = 4, selectionOrder = 2)
        )

        val context = ClassroomTranscriptSelectionPolicy.buildQuestionContext(transcripts, selected)

        assertEquals(30_000L, context.contextStartMs)
        assertEquals(241_000L, context.contextEndMs)
        assertEquals(listOf(2L, 3L, 4L, 5L), context.contextTranscripts.map { it.id })
        assertEquals(listOf(3L, 4L), context.selectedTranscripts.map { it.id })
        assertTrue(context.contextTranscripts.none { it.text == "before too far" })
        assertTrue(context.contextTranscripts.none { it.text == "after too far" })
    }

    @Test
    fun coreTranscriptTargetUsesFirstSelectionMidpoint() {
        val transcripts = listOf(
            transcript(1, start = 10_000, end = 13_000, text = "first"),
            transcript(2, start = 20_000, end = 24_000, text = "second")
        )
        val selected = listOf(
            ClassroomTranscriptSelection(transcriptId = 2, selectionOrder = 2),
            ClassroomTranscriptSelection(transcriptId = 1, selectionOrder = 1)
        )

        val targetMs = ClassroomTranscriptSelectionPolicy.coreTranscriptTargetMs(transcripts, selected)

        assertEquals(11_500L, targetMs)
    }

    private fun transcript(id: Long, start: Long, end: Long, text: String): VideoSpeechTranscriptEntity {
        return VideoSpeechTranscriptEntity(
            id = id,
            runId = 7,
            timestamp = start,
            displayTimestamp = "${start / 1000}s",
            text = text,
            globalStartMs = start,
            globalEndMs = end
        )
    }
}
