package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeUpdate
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

internal class ClassroomKnowledgeTreeUpdater(
    private val apiService: DoubaoApiService,
    private val model: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun update(
        traceId: String,
        runId: Long,
        task: VideoProcessTaskDraft,
        currentTree: ClassroomKnowledgeTree?,
        transcriptWindow: ClassroomKnowledgeTranscriptWindow,
        realtimeInsights: List<String>,
        finalFlush: Boolean
    ): ClassroomKnowledgeTreeUpdate? {
        if (apiKey.isBlank() || transcriptWindow.isBlank()) return null
        val context = VideoAiTraceContext(
            traceId = traceId,
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomKnowledgeTreeUpdater",
            model = model,
            requestKind = "classroom_knowledge_tree"
        )
        val currentTreeJson = ClassroomKnowledgeTreeParser.toPromptJson(currentTree)
        val activePathSummary = ClassroomKnowledgeTreeParser.renderActivePathSummary(currentTree)
        val basePrompt = ClassroomPromptBuilder.knowledgeTreeBasePrompt()
        val prompt = ClassroomPromptBuilder.knowledgeTreePrompt(
            task = task,
            currentTreeJson = currentTreeJson,
            transcriptWindow = transcriptWindow,
            realtimeInsights = realtimeInsights,
            activePathSummary = activePathSummary
        )
        val startedAt = System.currentTimeMillis()
        ClassroomRealtimeDiagnostics.knowledgeTree(
            "update_start run=$runId trace=$traceId inputLines=${transcriptWindow.lines.size} windowMs=${transcriptWindow.startMs ?: -1}-${transcriptWindow.endMs ?: -1} transcriptChars=${transcriptWindow.charCount} currentNodes=${ClassroomKnowledgeTreeParser.countNodes(currentTree)} currentActive=${ClassroomKnowledgeTreeParser.countActiveNodes(currentTree)} insights=${realtimeInsights.size} model=$model"
        )
        ClassroomRealtimeDiagnostics.knowledgeTreeChunked(
            kind = "current_tree_snapshot",
            text = ClassroomKnowledgeTreeParser.renderDebugOutline(currentTree)
        )
        traceLogger.beginNode(
            context,
            aiTracePayload(
                "transcriptLength" to transcriptWindow.charCount,
                "inputLines" to transcriptWindow.lines.size,
                "windowStartMs" to (transcriptWindow.startMs ?: 0L),
                "windowEndMs" to (transcriptWindow.endMs ?: 0L),
                "hasCurrentTree" to (currentTree != null),
                "hasActivePath" to activePathSummary.isNotBlank(),
                "insightCount" to realtimeInsights.size
            )
        )
        traceLogger.logPrompt(context, basePrompt = basePrompt, renderedPrompt = prompt)
        traceLogger.logRequest(context, aiTracePayload("model" to model, "promptLength" to prompt.length))
        return try {
            val rawText = retryRemoteCall {
                apiService.analyzeIntent(
                    authorization = "Bearer $apiKey",
                    request = DoubaoRequest(
                        model = model,
                        input = listOf(
                            Message(
                                role = "user",
                                content = listOf(ContentItem(type = "input_text", text = prompt))
                            )
                        )
                    )
                ).requireOutputText("classroom knowledge tree")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            traceLogger.logResponse(context, rawText, durationMs)
            val parsed = ClassroomKnowledgeTreeParser.parseUpdate(rawText)
            val merged = parsed?.let {
                ClassroomKnowledgeTreeMergePolicy.merge(
                    currentTree = currentTree,
                    candidateUpdate = it,
                    window = transcriptWindow,
                    finalFlush = finalFlush
                )
            }?.takeIf { it.update.tree.nodes.isNotEmpty() }
            ClassroomRealtimeDiagnostics.knowledgeTree(
                "update_response run=$runId durationMs=$durationMs rawChars=${rawText.length} parseStatus=${if (parsed != null) "success" else "failed"} rootNodes=${parsed?.tree?.nodes.orEmpty().size} nodes=${ClassroomKnowledgeTreeParser.countNodes(parsed?.tree)} maxDepth=${ClassroomKnowledgeTreeParser.maxDepth(parsed?.tree)} maxDetails=${ClassroomKnowledgeTreeParser.maxDetailItems(parsed?.tree)} active=${ClassroomKnowledgeTreeParser.countActiveNodes(parsed?.tree)} emptyDetail=${ClassroomKnowledgeTreeParser.countEmptyDetailNodes(parsed?.tree)} changed=${parsed?.changedNodeIds.orEmpty().size} titles=${parsed?.tree?.nodes.orEmpty().joinToString("|") { it.title }.take(160)}"
            )
            merged?.stats?.let { stats ->
                ClassroomRealtimeDiagnostics.knowledgeTree(
                    "merge_applied run=$runId kept=${stats.kept} added=${stats.added} updated=${stats.updated} ignoredReparents=${stats.ignoredReparents} mergedDuplicates=${stats.mergedDuplicates} validTimeNodes=${stats.validTimeNodes} zeroTimeNodes=${stats.zeroTimeNodes} fallbackTimeNodes=${stats.fallbackTimeNodes}"
                )
            }
            ClassroomRealtimeDiagnostics.knowledgeTreeChunked(
                kind = "raw_response_preview",
                text = rawText.take(3_600)
            )
            ClassroomRealtimeDiagnostics.knowledgeTreeChunked(
                kind = "parsed_tree_snapshot",
                text = ClassroomKnowledgeTreeParser.renderDebugOutline(merged?.update?.tree ?: parsed?.tree)
            )
            traceLogger.logParsed(
                context = context,
                parsedSummary = merged?.update?.tree?.nodes.orEmpty().joinToString("；") { it.title },
                parsedJson = aiTracePayload(
                    "parseStatus" to if (merged != null) "success" else "failed",
                    "nodeCount" to ClassroomKnowledgeTreeParser.countNodes(merged?.update?.tree),
                    "changedNodeCount" to merged?.update?.changedNodeIds.orEmpty().size,
                    "inputLineCount" to transcriptWindow.lines.size,
                    "windowStartMs" to (transcriptWindow.startMs ?: 0L),
                    "windowEndMs" to (transcriptWindow.endMs ?: 0L)
                ),
                parseStatus = if (merged != null) "success" else "failed"
            )
            traceLogger.finishNode(context, durationMs)
            merged?.update
        } catch (error: Throwable) {
            ClassroomRealtimeDiagnostics.knowledgeTreeWarning(
                "update_failed run=$runId durationMs=${System.currentTimeMillis() - startedAt} message=${error.message.orEmpty().take(180)}"
            )
            traceLogger.logError(context, error, System.currentTimeMillis() - startedAt)
            null
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
        throw lastError ?: IllegalStateException("Knowledge tree update failed.")
    }

    private fun Throwable.isRetryableRemoteFailure(): Boolean {
        val text = message.orEmpty()
        return this is IOException ||
            text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
    }

    private companion object {
        private const val REMOTE_RETRY_ATTEMPTS = 2
        private const val REMOTE_RETRY_DELAY_MS = 1_000L
    }
}
