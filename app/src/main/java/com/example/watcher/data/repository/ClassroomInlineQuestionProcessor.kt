package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomInlineQuestionType
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoImageRequest
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.ImageContentItem
import com.example.watcher.data.remote.ImageMessage
import com.example.watcher.data.remote.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException

data class ClassroomInlineQuestionResult(
    val answerText: String,
    val rawResponse: String,
    val visualFramePath: String = "",
    val visualFrameTimestampMs: Long = 0L,
    val visualFrameStatus: String = ""
)

internal class ClassroomInlineQuestionProcessor(
    private val apiService: DoubaoApiService,
    private val planningModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun answer(
        runId: Long,
        traceId: String,
        task: VideoProcessTaskDraft,
        questionType: ClassroomInlineQuestionType,
        selectedTranscripts: List<VideoSpeechTranscriptEntity>,
        contextTranscripts: List<VideoSpeechTranscriptEntity>,
        realtimeInsights: List<String>,
        contextStartMs: Long,
        contextEndMs: Long,
        frameEvidence: ClassroomInlineFrameEvidence? = null
    ): ClassroomInlineQuestionResult {
        check(apiKey.isNotBlank()) { "API_KEY is missing. Set it in local.properties first." }
        val requestKind = if (frameEvidence != null) {
            "classroom_inline_question_image"
        } else {
            "classroom_inline_question_text"
        }
        val context = VideoAiTraceContext(
            traceId = traceId.ifBlank { "classroom-inline-$runId" },
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomInlineQuestionProcessor",
            model = planningModel,
            requestKind = requestKind
        )
        val basePrompt = ClassroomPromptBuilder.inlineQuestionBasePrompt()
        val renderedPrompt = ClassroomPromptBuilder.inlineQuestionPrompt(
            task = task,
            questionType = questionType,
            selectedTranscripts = selectedTranscripts,
            allContextTranscripts = contextTranscripts,
            realtimeInsights = realtimeInsights,
            contextStartMs = contextStartMs,
            contextEndMs = contextEndMs,
            frameEvidence = frameEvidence
        )
        val startedAt = System.currentTimeMillis()
        traceLogger.beginNode(
            context,
            aiTracePayload(
                "questionType" to questionType.value,
                "selectedTranscriptCount" to selectedTranscripts.size,
                "contextTranscriptCount" to contextTranscripts.size,
                "externalKnowledgeAllowed" to true,
                "visualFrameStatus" to (frameEvidence?.status ?: "unavailable"),
                "visualFrameTimestampMs" to (frameEvidence?.frameTimestampMs ?: 0L),
                "visualFramePath" to frameEvidence?.framePath.orEmpty()
            )
        )
        traceLogger.logPrompt(context, basePrompt = basePrompt, renderedPrompt = renderedPrompt)
        traceLogger.logRequest(
            context,
            aiTracePayload(
                "model" to planningModel,
                "promptLength" to renderedPrompt.length,
                "contextStartMs" to contextStartMs,
                "contextEndMs" to contextEndMs,
                "requestKind" to requestKind,
                "visualFrameSource" to frameEvidence?.source.orEmpty(),
                "visualFrameSha256" to frameEvidence?.sha256.orEmpty(),
                "visualFrameBytes" to (frameEvidence?.byteLength ?: 0L),
                "visualFrameWidth" to (frameEvidence?.width ?: 0),
                "visualFrameHeight" to (frameEvidence?.height ?: 0)
            )
        )
        return try {
            val rawText = retryRemoteCall {
                val response = if (frameEvidence != null) {
                    apiService.analyzeImage(
                        authorization = "Bearer $apiKey",
                        request = DoubaoImageRequest(
                            model = planningModel,
                            input = listOf(
                                ImageMessage(
                                    role = "user",
                                    content = listOf(
                                        ImageContentItem(type = "input_text", text = renderedPrompt),
                                        ImageContentItem(type = "input_image", imageUrl = frameEvidence.imageDataUri)
                                    )
                                )
                            )
                        )
                    )
                } else {
                    apiService.analyzeIntent(
                        authorization = "Bearer $apiKey",
                        request = DoubaoRequest(
                            model = planningModel,
                            input = listOf(
                                Message(
                                    role = "user",
                                    content = listOf(ContentItem(type = "input_text", text = renderedPrompt))
                                )
                            )
                        )
                    )
                }
                response.requireOutputText("classroom inline question")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val answer = parseAnswer(rawText)
            traceLogger.logResponse(context, rawText, durationMs)
            traceLogger.logParsed(
                context = context,
                parsedSummary = answer,
                parsedJson = aiTracePayload("parseStatus" to "success", "answerLength" to answer.length),
                parseStatus = "success"
            )
            traceLogger.finishNode(context, durationMs)
            ClassroomInlineQuestionResult(
                answerText = answer,
                rawResponse = rawText,
                visualFramePath = frameEvidence?.framePath.orEmpty(),
                visualFrameTimestampMs = frameEvidence?.frameTimestampMs ?: 0L,
                visualFrameStatus = frameEvidence?.status ?: "unavailable"
            )
        } catch (error: Throwable) {
            traceLogger.logError(context, error, System.currentTimeMillis() - startedAt)
            throw error
        }
    }

    private fun parseAnswer(rawText: String): String {
        val jsonStart = rawText.indexOf('{')
        val jsonEnd = rawText.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val answer = runCatching {
                JSONObject(rawText.substring(jsonStart, jsonEnd + 1)).optString("answer")
            }.getOrNull()
            if (!answer.isNullOrBlank()) return answer.trim()
        }
        return rawText.trim().trim('`').take(220)
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
