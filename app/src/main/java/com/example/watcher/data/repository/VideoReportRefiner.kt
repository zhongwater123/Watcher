package com.example.watcher.data.repository

import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.Message
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Nodes 7+8: Report refinement.
 * - refineWithVideo (Node 7): Watches a video chunk and enriches the report with visual details.
 * - refineWithFacts (Node 8): Incrementally incorporates new segment facts into the report.
 */
internal class VideoReportRefiner(
    private val apiService: DoubaoApiService,
    private val videoModel: String,
    private val planningModel: String,
    private val apiKey: String
) {

    /**
     * Node 7: Refine the report by watching a video chunk and adding visual details,
     * correcting contradictions, and adding timestamp references.
     */
    suspend fun refineWithVideo(
        currentReport: String,
        videoFileId: String,
        task: VideoProcessTaskDraft,
        chunkIndex: Int,
        chunkCount: Int
    ): String {
        val prompt = buildString {
            appendLine("你是专业分析助手。以下是基于音频大纲和分片事实生成的初版报告。")
            appendLine("现在请观看对应视频片段，对报告执行：补充视觉细节、修正矛盾、添加时间戳引用、保持结构连贯。")
            appendLine("输出完整 Markdown 报告。")
            appendLine()
            appendLine("任务背景: ${task.userRequirement}")
            appendLine("视频 $chunkIndex/$chunkCount")
            appendLine()
            appendLine("当前报告:")
            appendLine(currentReport)
        }
        val contentItems = listOf(
            VideoContentItem(type = "input_video", fileId = videoFileId),
            VideoContentItem(type = "input_text", text = prompt)
        )
        val request = DoubaoVideoRequest(
            model = videoModel,
            input = listOf(VideoMessage(role = "user", content = contentItems))
        )
        return retryRemoteCall {
            apiService.analyzeVideo(
                authorization = bearerToken(),
                request = request
            ).requireOutputText("report video refinement")
        }
    }

    /**
     * Node 8: Incrementally refine the report with new segment facts.
     * Called each time a backlogged segment analysis completes.
     */
    suspend fun refineWithFacts(
        currentReport: String,
        newFactPacket: String,
        segmentIndex: Int,
        task: VideoProcessTaskDraft
    ): String {
        val prompt = buildString {
            appendLine("你正在增量构建一份分析报告。以下是当前报告和新获取的视觉+音频事实。")
            appendLine("请根据新事实校准、补充、丰富当前报告：")
            appendLine("- 修正任何与新事实矛盾的内容")
            appendLine("- 补充新发现的具体信息（产品参数、画面证据、精确引述等）")
            appendLine("- 保持报告的整体结构和叙事连贯性")
            appendLine("- 不要删除已有的正确内容")
            appendLine("- 输出完整的 Markdown 格式报告")
            appendLine()
            appendLine("任务背景: ${task.userRequirement}")
            appendLine()
            appendLine("当前报告:")
            appendLine(currentReport)
            appendLine()
            appendLine("新事实 (Segment $segmentIndex):")
            appendLine(newFactPacket)
        }

        val request = DoubaoRequest(
            model = planningModel,
            input = listOf(
                Message(
                    role = "user",
                    content = listOf(ContentItem(type = "input_text", text = prompt))
                )
            )
        )

        return retryRemoteCall {
            apiService.analyzeIntent(
                authorization = bearerToken(),
                request = request
            ).requireOutputText("report refinement")
        }
    }

    // region Utilities

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

    private fun bearerToken(): String = "Bearer $apiKey"

    // endregion

    private companion object {
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}
