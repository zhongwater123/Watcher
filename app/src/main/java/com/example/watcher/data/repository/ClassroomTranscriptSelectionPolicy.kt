package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomTranscriptWeightLevel
import com.example.watcher.data.model.VideoSpeechTranscriptEntity

data class ClassroomTranscriptSelection(
    val transcriptId: Long,
    val selectionOrder: Int,
    val weightLevel: ClassroomTranscriptWeightLevel = ClassroomTranscriptSelectionPolicy.weightForOrder(selectionOrder)
)

data class ClassroomInlineQuestionContext(
    val selectedTranscripts: List<VideoSpeechTranscriptEntity>,
    val contextTranscripts: List<VideoSpeechTranscriptEntity>,
    val contextStartMs: Long,
    val contextEndMs: Long
)

object ClassroomTranscriptSelectionPolicy {
    const val MIN_SELECTIONS_TO_ASK = 3
    private const val BEFORE_CONTEXT_MS = 120_000L
    private const val AFTER_CONTEXT_MS = 60_000L

    fun weightForOrder(order: Int): ClassroomTranscriptWeightLevel {
        return when {
            order <= 1 -> ClassroomTranscriptWeightLevel.Core
            order <= 3 -> ClassroomTranscriptWeightLevel.Important
            else -> ClassroomTranscriptWeightLevel.Context
        }
    }

    fun toggleSelection(
        currentSelections: List<ClassroomTranscriptSelection>,
        transcriptId: Long
    ): List<ClassroomTranscriptSelection> {
        val nextIds = if (currentSelections.any { it.transcriptId == transcriptId }) {
            currentSelections
                .filterNot { it.transcriptId == transcriptId }
                .sortedBy { it.selectionOrder }
                .map { it.transcriptId }
        } else {
            currentSelections
                .sortedBy { it.selectionOrder }
                .map { it.transcriptId } + transcriptId
        }
        return nextIds.mapIndexed { index, id ->
            val order = index + 1
            ClassroomTranscriptSelection(
                transcriptId = id,
                selectionOrder = order,
                weightLevel = weightForOrder(order)
            )
        }
    }

    fun buildQuestionContext(
        transcripts: List<VideoSpeechTranscriptEntity>,
        selections: List<ClassroomTranscriptSelection>
    ): ClassroomInlineQuestionContext {
        val selectedIds = selections.map { it.transcriptId }.toSet()
        val selectedTranscripts = transcripts
            .filter { it.id in selectedIds }
            .sortedBy { transcript -> selections.firstOrNull { it.transcriptId == transcript.id }?.selectionOrder ?: Int.MAX_VALUE }
        if (selectedTranscripts.isEmpty()) {
            return ClassroomInlineQuestionContext(emptyList(), emptyList(), 0L, 0L)
        }
        val selectedStart = selectedTranscripts.minOf { it.globalStartMs }
        val selectedEnd = selectedTranscripts.maxOf { it.globalEndMs.coerceAtLeast(it.globalStartMs) }
        val contextStart = (selectedStart - BEFORE_CONTEXT_MS).coerceAtLeast(0L)
        val contextEnd = selectedEnd + AFTER_CONTEXT_MS
        val contextTranscripts = transcripts
            .filter { transcript ->
                val start = transcript.globalStartMs
                val end = transcript.globalEndMs.coerceAtLeast(start)
                end >= contextStart && start <= contextEnd
            }
            .sortedWith(compareBy<VideoSpeechTranscriptEntity> { it.globalStartMs }.thenBy { it.id })
        return ClassroomInlineQuestionContext(
            selectedTranscripts = selectedTranscripts,
            contextTranscripts = contextTranscripts,
            contextStartMs = contextStart,
            contextEndMs = contextEnd
        )
    }

    fun coreTranscriptTargetMs(
        transcripts: List<VideoSpeechTranscriptEntity>,
        selections: List<ClassroomTranscriptSelection>
    ): Long? {
        val coreTranscriptId = selections.minByOrNull { it.selectionOrder }?.transcriptId ?: return null
        val coreTranscript = transcripts.firstOrNull { it.id == coreTranscriptId } ?: return null
        val start = coreTranscript.globalStartMs
        val end = coreTranscript.globalEndMs.coerceAtLeast(start)
        return start + ((end - start) / 2L)
    }
}
