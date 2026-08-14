package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.model.ClassroomNoteFollowupContextStage
import com.example.watcher.data.model.ClassroomNoteFollowupEntity
import com.example.watcher.data.model.ClassroomNoteFollowupSourceRef
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.VideoSegmentRun
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val CLASSROOM_COMPLETION_LOG_TAG = "ClassroomCompletion"

data class ClassroomNoteFollowupTurn(
    val id: Long,
    val question: String,
    val answer: String
)

data class ClassroomNoteFollowupContext(
    val stage: ClassroomNoteFollowupContextStage,
    val noteText: String,
    val summaryText: String,
    val knowledgeTreeText: String,
    val evidenceRefs: List<ClassroomNoteFollowupSourceRef>,
    val conversationTurns: List<ClassroomNoteFollowupTurn>
)

data class ClassroomNoteFollowupAnswerResult(
    val answer: String,
    val supplement: String,
    val sourceRefs: List<ClassroomNoteFollowupSourceRef>,
    val rawResponse: String
)

internal object ClassroomNoteFollowupContextFactory {
    fun resolveStage(run: VideoProcessRun, streamingBuffer: String = ""): ClassroomNoteFollowupContextStage {
        return when {
            run.markdownNote.isNotBlank() &&
                run.status in setOf(VideoRunStatus.Completed, VideoRunStatus.CompletedDegraded) ->
                ClassroomNoteFollowupContextStage.Final
            run.markdownNote.isNotBlank() || run.outlineMarkdown.isNotBlank() ->
                ClassroomNoteFollowupContextStage.Outline
            run.status == VideoRunStatus.Summarizing ->
                ClassroomNoteFollowupContextStage.Summarizing
            streamingBuffer.isNotBlank() || run.rawModelSummary.isNotBlank() || run.finalSummary.isNotBlank() ->
                ClassroomNoteFollowupContextStage.Draft
            else -> ClassroomNoteFollowupContextStage.Draft
        }
    }

    fun build(
        run: VideoProcessRun,
        streamingBuffer: String,
        transcripts: List<VideoSpeechTranscriptEntity>,
        timelineEvents: List<TimelineEventEntity>,
        segments: List<VideoSegmentRun>,
        previousTurns: List<ClassroomNoteFollowupEntity>
    ): ClassroomNoteFollowupContext {
        val noteText = run.markdownNote
            .ifBlank { run.outlineMarkdown }
            .ifBlank { streamingBuffer }
            .ifBlank { run.rawModelSummary }
        val summaryText = listOf(run.finalSummary, run.finalConclusion)
            .filter(String::isNotBlank)
            .joinToString(separator = "\n")
        val evidenceRefs = buildEvidenceRefs(transcripts, timelineEvents, segments)
        val turns = previousTurns
            .filter { it.question.isNotBlank() && it.answer.isNotBlank() }
            .map { turn ->
                ClassroomNoteFollowupTurn(
                    id = turn.id,
                    question = turn.question,
                    answer = turn.answer.take(MAX_TURN_ANSWER_CHARS)
                )
            }
        return ClassroomNoteFollowupContext(
            stage = resolveStage(run, streamingBuffer),
            noteText = noteText.take(MAX_NOTE_CHARS),
            summaryText = summaryText.take(MAX_SUMMARY_CHARS),
            knowledgeTreeText = run.classroomKnowledgeTreeJson.take(MAX_KNOWLEDGE_TREE_CHARS),
            evidenceRefs = evidenceRefs,
            conversationTurns = turns
        )
    }

    private fun buildEvidenceRefs(
        transcripts: List<VideoSpeechTranscriptEntity>,
        timelineEvents: List<TimelineEventEntity>,
        segments: List<VideoSegmentRun>
    ): List<ClassroomNoteFollowupSourceRef> {
        val transcriptRefs = transcripts
            .filter { it.text.isNotBlank() }
            .takeLast(MAX_TRANSCRIPT_REFS)
            .map {
                ClassroomNoteFollowupSourceRef(
                    type = "transcript",
                    text = it.text.take(MAX_REF_TEXT_CHARS),
                    startMs = it.globalStartMs,
                    endMs = it.globalEndMs.takeIf { end -> end > it.globalStartMs },
                    refId = it.id.takeIf { id -> id > 0 }?.let { id -> "asr-$id" }.orEmpty()
                )
            }
        val timelineRefs = timelineEvents
            .take(MAX_TIMELINE_REFS)
            .map {
                ClassroomNoteFollowupSourceRef(
                    type = "timeline",
                    text = listOf(it.title, it.detail).filter(String::isNotBlank).joinToString("：").take(MAX_REF_TEXT_CHARS),
                    startMs = it.timestampSeconds * 1_000L,
                    endMs = null,
                    refId = it.id.takeIf { id -> id > 0 }?.let { id -> "timeline-$id" }.orEmpty()
                )
            }
        val segmentRefs = segments
            .filter { it.summary.isNotBlank() || it.conclusion.isNotBlank() }
            .take(MAX_SEGMENT_REFS)
            .map {
                ClassroomNoteFollowupSourceRef(
                    type = "segment",
                    text = it.summary.ifBlank { it.conclusion }.take(MAX_REF_TEXT_CHARS),
                    startMs = it.mediaStartMs ?: it.wallClockStartMs,
                    endMs = it.mediaEndMs ?: it.wallClockEndMs,
                    refId = it.id.takeIf { id -> id > 0 }?.let { id -> "segment-$id" }.orEmpty()
                )
            }
        return (timelineRefs + transcriptRefs + segmentRefs).take(MAX_TOTAL_REFS)
    }

    private const val MAX_NOTE_CHARS = 12_000
    private const val MAX_SUMMARY_CHARS = 2_000
    private const val MAX_KNOWLEDGE_TREE_CHARS = 6_000
    private const val MAX_TRANSCRIPT_REFS = 80
    private const val MAX_TIMELINE_REFS = 24
    private const val MAX_SEGMENT_REFS = 24
    private const val MAX_TOTAL_REFS = 120
    private const val MAX_REF_TEXT_CHARS = 180
    private const val MAX_TURN_ANSWER_CHARS = 500
}

internal object ClassroomNoteFollowupResultParser {
    fun parse(rawText: String): ClassroomNoteFollowupAnswerResult {
        val json = extractJson(rawText)
        if (json != null) {
            val answer = json.optString("answer").trim()
            val supplement = json.optString("supplement").trim()
            val refs = parseRefs(json.optJSONArray("courseEvidence"))
            if (answer.isNotBlank()) {
                return ClassroomNoteFollowupAnswerResult(
                    answer = answer,
                    supplement = supplement,
                    sourceRefs = refs,
                    rawResponse = rawText
                )
            }
        }
        return ClassroomNoteFollowupAnswerResult(
            answer = rawText.trim().trim('`'),
            supplement = "",
            sourceRefs = emptyList(),
            rawResponse = rawText
        )
    }

    fun sourceRefsToJson(refs: List<ClassroomNoteFollowupSourceRef>): String {
        val array = JSONArray()
        refs.forEach { ref ->
            array.put(
                JSONObject()
                    .put("type", ref.type)
                    .put("text", ref.text)
                    .put("startMs", ref.startMs ?: JSONObject.NULL)
                    .put("endMs", ref.endMs ?: JSONObject.NULL)
                    .put("refId", ref.refId)
            )
        }
        return array.toString()
    }

    fun sourceRefsFromJson(jsonText: String): List<ClassroomNoteFollowupSourceRef> {
        if (jsonText.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(jsonText)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ClassroomNoteFollowupSourceRef(
                    type = item.optString("type"),
                    text = item.optString("text"),
                    startMs = item.optNullableLong("startMs"),
                    endMs = item.optNullableLong("endMs"),
                    refId = item.optString("refId")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun extractJson(rawText: String): JSONObject? {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(rawText.substring(start, end + 1)) }.getOrNull()
    }

    private fun parseRefs(array: JSONArray?): List<ClassroomNoteFollowupSourceRef> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            runCatching {
                val item = array.getJSONObject(index)
                ClassroomNoteFollowupSourceRef(
                    type = item.optString("type"),
                    text = item.optString("text"),
                    startMs = item.optNullableLong("startMs"),
                    endMs = item.optNullableLong("endMs"),
                    refId = item.optString("refId")
                )
            }.getOrNull()
        }.filterNotNull().filter { it.text.isNotBlank() }
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (has(name) && !isNull(name)) optLong(name) else null
    }
}

internal class ClassroomNoteFollowupProcessor(
    private val apiService: DoubaoApiService,
    private val planningModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun answer(
        runId: Long,
        traceId: String,
        task: VideoProcessTaskDraft,
        question: String,
        context: ClassroomNoteFollowupContext
    ): ClassroomNoteFollowupAnswerResult {
        check(apiKey.isNotBlank()) { "API_KEY is missing. Set it in local.properties first." }
        val traceContext = VideoAiTraceContext(
            traceId = traceId.ifBlank { "classroom-followup-$runId" },
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomNoteFollowupProcessor",
            model = planningModel,
            requestKind = "classroom_note_followup"
        )
        val prompt = ClassroomPromptBuilder.noteFollowupPrompt(question, task, context)
        Log.d(
            CLASSROOM_COMPLETION_LOG_TAG,
            "Followup prompt run=$runId stage=${context.stage.value} promptLength=${prompt.length} " +
                "noteLength=${context.noteText.length} refs=${context.evidenceRefs.size} turns=${context.conversationTurns.size}"
        )
        val startedAt = System.currentTimeMillis()
        traceLogger.beginNode(
            traceContext,
            aiTracePayload(
                "questionLength" to question.length,
                "contextStage" to context.stage.value,
                "evidenceRefCount" to context.evidenceRefs.size,
                "conversationTurnCount" to context.conversationTurns.size
            )
        )
        traceLogger.logPrompt(traceContext, basePrompt = "classroom_note_followup", renderedPrompt = prompt)
        return try {
            val rawText = retryRemoteCall {
                apiService.analyzeIntent(
                    authorization = "Bearer $apiKey",
                    request = DoubaoRequest(
                        model = planningModel,
                        input = listOf(
                            Message(
                                role = "user",
                                content = listOf(ContentItem(type = "input_text", text = prompt))
                            )
                        )
                    )
                ).requireOutputText("classroom note followup")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val parsed = ClassroomNoteFollowupResultParser.parse(rawText)
            Log.d(
                CLASSROOM_COMPLETION_LOG_TAG,
                "Followup response run=$runId durationMs=$durationMs rawLength=${rawText.length} " +
                    "answerLength=${parsed.answer.length} refs=${parsed.sourceRefs.size} supplement=${parsed.supplement.isNotBlank()}"
            )
            traceLogger.logResponse(traceContext, rawText, durationMs)
            traceLogger.logParsed(
                context = traceContext,
                parsedSummary = parsed.answer,
                parsedJson = aiTracePayload(
                    "parseStatus" to "success",
                    "sourceRefCount" to parsed.sourceRefs.size,
                    "hasSupplement" to parsed.supplement.isNotBlank()
                ),
                parseStatus = "success"
            )
            traceLogger.finishNode(traceContext, durationMs)
            parsed
        } catch (error: Throwable) {
            traceLogger.logError(traceContext, error, System.currentTimeMillis() - startedAt)
            throw error
        }
    }

    private suspend fun <T> retryRemoteCall(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(REMOTE_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException || !error.isRetryableRemoteFailure() || attempt == REMOTE_RETRY_ATTEMPTS - 1) {
                    throw error
                }
                lastError = error
                delay(REMOTE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Remote call failed.")
    }

    private fun Throwable.isRetryableRemoteFailure(): Boolean {
        val text = message.orEmpty()
        return this is IOException ||
            text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
    }

    private companion object {
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}
