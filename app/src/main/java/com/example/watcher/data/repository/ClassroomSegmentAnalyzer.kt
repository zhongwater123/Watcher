package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException

private const val CLASSROOM_SEGMENT_TAG = "Watcher.Classroom.Segment"

internal class ClassroomSegmentAnalyzer(
    private val apiService: DoubaoApiService,
    private val videoModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun analyze(
        fileId: String,
        audioFileId: String?,
        isMergedInput: Boolean,
        task: VideoProcessTaskDraft,
        segmentNumber: Int,
        segmentCount: Int,
        startOffsetSeconds: Int,
        durationSeconds: Int,
        traceId: String,
        runId: Long,
        inputMode: String
    ): VideoAnalysisResult {
        val context = VideoAiTraceContext(
            traceId = traceId,
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomSegmentAnalyzer",
            segmentIndex = segmentNumber,
            model = videoModel,
            requestKind = inputMode
        )
        val startedAt = System.currentTimeMillis()
        val basePrompt = ClassroomPromptBuilder.segmentFactBasePrompt()
        val renderedPrompt = ClassroomPromptBuilder.segmentFactPrompt(
            task = task,
            segmentNumber = segmentNumber,
            segmentCount = segmentCount,
            startOffsetSeconds = startOffsetSeconds,
            durationSeconds = durationSeconds,
            inputMode = inputMode
        )
        val contentItems = buildList {
            if (!isMergedInput) {
                audioFileId?.takeIf(String::isNotBlank)?.let { add(VideoContentItem(type = "input_audio", fileId = it)) }
            }
            add(VideoContentItem(type = "input_video", fileId = fileId))
            add(VideoContentItem(type = "input_text", text = renderedPrompt))
        }
        val request = DoubaoVideoRequest(
            model = videoModel,
            input = listOf(VideoMessage(role = "user", content = contentItems))
        )

        return try {
            traceLogger.beginNode(
                context,
                aiTracePayload(
                    "fileId" to fileId,
                    "audioFileId" to audioFileId,
                    "isMergedInput" to isMergedInput,
                    "inputMode" to inputMode,
                    "segmentNumber" to segmentNumber,
                    "segmentCount" to segmentCount,
                    "startOffsetSeconds" to startOffsetSeconds,
                    "durationSeconds" to durationSeconds
                )
            )
            traceLogger.logPrompt(context, basePrompt = basePrompt, renderedPrompt = renderedPrompt)
            traceLogger.logRequest(
                context,
                aiTracePayload(
                    "model" to request.model,
                    "contentItemTypes" to contentItems.joinToString(",") { it.type },
                    "fileId" to fileId,
                    "audioFileId" to audioFileId,
                    "promptLength" to renderedPrompt.length
                )
            )
            Log.d(CLASSROOM_SEGMENT_TAG, "Classroom segment $segmentNumber request model=$videoModel inputMode=$inputMode")
            val rawText = retryRemoteCall {
                apiService.analyzeVideo(
                    authorization = "Bearer $apiKey",
                    request = request
                )
                    .requireOutputText("classroom segment analysis")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val parsed = parseSegmentFacts(rawText)
            traceLogger.logResponse(context, rawText, durationMs)
            traceLogger.logParsed(
                context = context,
                parsedSummary = parsed.summary,
                parsedJson = parsed.jsonText,
                parseStatus = parsed.parseStatus
            )
            traceLogger.finishNode(context, durationMs)
            VideoAnalysisResult(
                summary = parsed.summary,
                conclusion = parsed.coverageNotice,
                timelineEvents = emptyList(),
                rawResponse = rawText,
                structuredNoteJson = parsed.jsonText,
                markdownNote = "",
                evidenceJson = parsed.jsonText.ifBlank { rawText }
            )
        } catch (error: Throwable) {
            traceLogger.logError(context, error, System.currentTimeMillis() - startedAt)
            throw error
        }
    }

    private fun parseSegmentFacts(rawText: String): ParsedSegmentFacts {
        val json = extractJsonObject(rawText)
        if (json == null) {
            return ParsedSegmentFacts(
                summary = rawText.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(160),
                jsonText = "",
                coverageNotice = "segment fact JSON parse fallback",
                parseStatus = "fallback"
            )
        }
        return runCatching {
            val root = JSONObject(json)
            ParsedSegmentFacts(
                summary = root.optString("segmentTopic")
                    .ifBlank { root.optJSONArray("speechKeyPoints")?.optJSONObject(0)?.optString("text").orEmpty() }
                    .ifBlank { "课堂分片 ${root.optInt("segmentIndex")} 事实包" },
                jsonText = root.toString(),
                coverageNotice = root.optString("coverageNotice"),
                parseStatus = "success"
            )
        }.getOrElse {
            ParsedSegmentFacts(
                summary = rawText.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(160),
                jsonText = "",
                coverageNotice = "segment fact JSON parse failed",
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

    private data class ParsedSegmentFacts(
        val summary: String,
        val jsonText: String,
        val coverageNotice: String,
        val parseStatus: String
    )

    private companion object {
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}
