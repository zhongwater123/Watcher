package com.example.watcher.data.repository

import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException

internal data class ClassroomVisualEvidenceResult(
    val segmentIndex: Int,
    val summary: String,
    val rawJson: String,
    val parseStatus: String
)

internal class ClassroomVisualEvidenceAnalyzer(
    private val apiService: DoubaoApiService,
    private val videoModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun analyze(
        fileId: String,
        task: VideoProcessTaskDraft,
        segmentNumber: Int,
        segmentCount: Int,
        timeRange: String,
        traceId: String,
        runId: Long
    ): ClassroomVisualEvidenceResult {
        val context = VideoAiTraceContext(
            traceId = traceId,
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomVisualEvidenceAnalyzer",
            segmentIndex = segmentNumber,
            model = videoModel,
            requestKind = "classroom_visual_evidence"
        )
        val startedAt = System.currentTimeMillis()
        val basePrompt = ClassroomPromptBuilder.visualEvidenceBasePrompt()
        val renderedPrompt = ClassroomPromptBuilder.visualEvidencePrompt(
            task = task,
            segmentNumber = segmentNumber,
            segmentCount = segmentCount,
            timeRange = timeRange
        )
        val contentItems = listOf(
            VideoContentItem(type = "input_video", fileId = fileId),
            VideoContentItem(type = "input_text", text = renderedPrompt)
        )
        val request = DoubaoVideoRequest(
            model = videoModel,
            input = listOf(VideoMessage(role = "user", content = contentItems))
        )

        return try {
            traceLogger.beginNode(
                context,
                aiTracePayload(
                    "fileId" to fileId,
                    "segmentNumber" to segmentNumber,
                    "segmentCount" to segmentCount,
                    "timeRange" to timeRange
                )
            )
            traceLogger.logPrompt(context, basePrompt = basePrompt, renderedPrompt = renderedPrompt)
            traceLogger.logRequest(
                context,
                aiTracePayload(
                    "model" to request.model,
                    "contentItemTypes" to contentItems.joinToString(",") { it.type },
                    "fileId" to fileId,
                    "promptLength" to renderedPrompt.length
                )
            )
            val rawText = retryRemoteCall {
                apiService.analyzeVideo(
                    authorization = "Bearer $apiKey",
                    request = request
                )
                    .requireOutputText("classroom visual evidence analysis")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val parsed = parseVisualEvidence(segmentNumber, rawText)
            traceLogger.logResponse(context, rawText, durationMs)
            traceLogger.logParsed(
                context = context,
                parsedSummary = parsed.summary,
                parsedJson = parsed.rawJson,
                parseStatus = parsed.parseStatus
            )
            traceLogger.finishNode(context, durationMs)
            parsed
        } catch (error: Throwable) {
            traceLogger.logError(context, error, System.currentTimeMillis() - startedAt)
            throw error
        }
    }

    private fun parseVisualEvidence(segmentNumber: Int, rawText: String): ClassroomVisualEvidenceResult {
        val json = extractJsonObject(rawText)
        if (json == null) {
            return ClassroomVisualEvidenceResult(
                segmentIndex = segmentNumber,
                summary = rawText.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(160),
                rawJson = rawText,
                parseStatus = "fallback"
            )
        }
        return runCatching {
            val root = JSONObject(json)
            val firstEvidence = root.optJSONArray("visualEvidence")
                ?.optJSONObject(0)
                ?.let { item ->
                    listOf(item.optString("source"), item.optString("description"), item.optString("text"))
                        .filter(String::isNotBlank)
                        .joinToString(": ")
                }
                .orEmpty()
            ClassroomVisualEvidenceResult(
                segmentIndex = root.optInt("segmentIndex", segmentNumber),
                summary = firstEvidence.ifBlank { root.optString("coverageNotice") }.ifBlank { "visual evidence supplement" },
                rawJson = root.toString(),
                parseStatus = "success"
            )
        }.getOrElse {
            ClassroomVisualEvidenceResult(
                segmentIndex = segmentNumber,
                summary = rawText.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(160),
                rawJson = rawText,
                parseStatus = "failed"
            )
        }
    }

    private fun extractJsonObject(text: String): String? {
        val codeBlock = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val candidate = codeBlock ?: text
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        return if (start >= 0 && end > start) candidate.substring(start, end + 1) else null
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
