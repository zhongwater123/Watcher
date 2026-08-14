package com.example.watcher.data.repository

import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

internal class ClassroomNoteSynthesizer(
    private val apiService: DoubaoApiService,
    private val planningModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun synthesize(
        task: VideoProcessTaskDraft,
        results: List<SegmentExecutionResult>,
        outlineMarkdown: String,
        realtimeTranscript: String = "",
        coverageNotices: List<String>,
        visualEvidence: List<ClassroomVisualEvidenceResult> = emptyList(),
        traceId: String,
        runId: Long
    ): VideoAnalysisResult {
        val context = VideoAiTraceContext(
            traceId = traceId,
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomNoteSynthesizer",
            model = planningModel,
            requestKind = "classroom_note_synthesis"
        )
        val startedAt = System.currentTimeMillis()
        val basePrompt = ClassroomPromptBuilder.noteSynthesisBasePrompt()
        val renderedPrompt = ClassroomPromptBuilder.noteSynthesisPrompt(
            task = task,
            audioOutlineMarkdown = outlineMarkdown,
            segmentFacts = results,
            realtimeTranscript = realtimeTranscript,
            coverageNotices = coverageNotices,
            visualEvidence = visualEvidence
        )
        val request = DoubaoVideoRequest(
            model = planningModel,
            input = listOf(
                VideoMessage(
                    role = "user",
                    content = listOf(VideoContentItem(type = "input_text", text = renderedPrompt))
                )
            )
        )

        return try {
            traceLogger.beginNode(
                context,
                aiTracePayload(
                    "segmentCount" to results.size,
                    "successfulSegmentCount" to results.count { it.segment.status == VideoRunStatus.Completed },
                    "outlineAvailable" to outlineMarkdown.isNotBlank(),
                    "realtimeTranscriptLength" to realtimeTranscript.length,
                    "coverageNoticeCount" to coverageNotices.size,
                    "visualEvidenceSupplementCount" to visualEvidence.size
                )
            )
            traceLogger.logPrompt(context, basePrompt = basePrompt, renderedPrompt = renderedPrompt)
            traceLogger.logRequest(
                context,
                aiTracePayload(
                    "model" to request.model,
                    "promptLength" to renderedPrompt.length,
                    "contentItemTypes" to "input_text"
                )
            )
            val rawText = retryRemoteCall {
                apiService.analyzeVideo(
                    authorization = "Bearer $apiKey",
                    request = request
                )
                    .requireOutputText("classroom note synthesis")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val parsed = ClassroomNoteResultParser.parse(rawText)
            traceLogger.logResponse(context, rawText, durationMs)
            traceLogger.logParsed(
                context = context,
                parsedSummary = parsed.summary,
                parsedJson = parsed.structuredNoteJson.ifBlank {
                    aiTracePayload(
                        "parseStatus" to parsed.parseStatus.name.lowercase(),
                        "fallbackMarkdownLength" to parsed.markdownNote.length
                    )
                },
                parseStatus = parsed.parseStatus.name.lowercase()
            )
            traceLogger.finishNode(context, durationMs)
            parsed.toVideoAnalysisResult()
        } catch (error: Throwable) {
            traceLogger.logError(context, error, System.currentTimeMillis() - startedAt)
            throw error
        }
    }

    fun fallbackFromAvailableEvidence(
        task: VideoProcessTaskDraft,
        results: List<SegmentExecutionResult>,
        outlineMarkdown: String,
        realtimeTranscript: String = "",
        coverageNotices: List<String>,
        visualEvidence: List<ClassroomVisualEvidenceResult> = emptyList()
    ): VideoAnalysisResult {
        val note = buildString {
            appendLine("# ${task.title.ifBlank { "课堂笔记" }}")
            if (outlineMarkdown.isNotBlank()) {
                appendLine()
                appendLine("## 音频课堂大纲")
                appendLine(outlineMarkdown)
            }
            if (realtimeTranscript.isNotBlank()) {
                appendLine()
                appendLine("## 实时转写证据")
                appendLine(realtimeTranscript)
            }
            appendLine()
            appendLine("## 分片课堂事实")
            results.sortedBy { it.segment.segmentIndex }.forEach { result ->
                appendLine()
                appendLine("### 第 ${result.segment.segmentIndex} 段")
                appendLine(result.analysisResult.summary.ifBlank { result.analysisResult.rawResponse })
                result.coverageLimitation?.takeIf(String::isNotBlank)?.let {
                    appendLine()
                    appendLine("覆盖限制：$it")
                }
            }
            if (visualEvidence.isNotEmpty()) {
                appendLine()
                appendLine("## 视觉证据补充")
                visualEvidence.sortedBy { it.segmentIndex }.forEach { result ->
                    appendLine()
                    appendLine("### 第 ${result.segmentIndex} 段")
                    appendLine(result.summary)
                    appendLine(result.rawJson)
                }
            }
            val notices = coverageNotices.filter(String::isNotBlank)
            if (notices.isNotEmpty()) {
                appendLine()
                appendLine("## 覆盖说明")
                notices.forEach { appendLine("- $it") }
            }
        }.trim()
        return VideoAnalysisResult(
            summary = note.lines().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.trim().orEmpty(),
            conclusion = coverageNotices.joinToString("；"),
            timelineEvents = emptyList(),
            rawResponse = note,
            structuredNoteJson = "",
            markdownNote = note,
            evidenceJson = buildList {
                addAll(results.map { it.analysisResult.evidenceJson }.filter(String::isNotBlank))
                addAll(visualEvidence.map { it.rawJson }.filter(String::isNotBlank))
            }.joinToString(separator = "\n\n")
        )
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
