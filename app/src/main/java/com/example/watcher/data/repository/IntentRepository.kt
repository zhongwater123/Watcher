package com.example.watcher.data.repository

import android.graphics.Bitmap
import com.example.watcher.data.local.MonitorTaskDao
import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoImageRequest
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.ImageContentItem
import com.example.watcher.data.remote.ImageMessage
import com.example.watcher.data.remote.Message
import com.example.watcher.data.remote.extractOutputText
import kotlinx.coroutines.flow.Flow

class IntentRepository(
    private val apiService: DoubaoApiService,
    private val taskDao: MonitorTaskDao,
    private val llmWalletRepository: LlmWalletRepository
) {
    suspend fun analyzeIntent(
        userInput: String,
        frame: Bitmap? = null,
        baselineSource: BaselineSource = BaselineSource.CapturedFrame,
        baselineImagePath: String? = null,
        persist: Boolean = true
    ): Result<IntentResult> {
        return try {
            val llmConfig = llmWalletRepository.resolveArkResponsesConfig(ArkConfig.intentModel)
            val encodedFrame = frame?.let { bitmap ->
                val base64 = BitmapEncoding.toBase64(bitmap)
                base64 to "data:image/jpeg;base64,$base64"
            }
            val response = if (frame != null) {
                val request = DoubaoImageRequest(
                    model = llmConfig.modelName,
                    input = listOf(
                        ImageMessage(
                            role = "system",
                            content = listOf(
                                ImageContentItem(
                                    type = "input_text",
                                    text = buildIntentPrompt(
                                        baselineSource = baselineSource,
                                        hasImage = true
                                    )
                                )
                            )
                        ),
                        ImageMessage(
                            role = "user",
                            content = listOf(
                                ImageContentItem(type = "input_image", imageUrl = encodedFrame!!.second),
                                ImageContentItem(type = "input_text", text = userInput)
                            )
                        )
                    )
                )

                apiService.analyzeImage(
                    authorization = llmConfig.bearerToken(),
                    request = request
                )
            } else {
                val request = DoubaoRequest(
                    model = llmConfig.modelName,
                    input = listOf(
                        Message(
                            role = "system",
                            content = listOf(
                                ContentItem(
                                    type = "input_text",
                                    text = buildIntentPrompt(
                                        baselineSource = baselineSource,
                                        hasImage = false
                                    )
                                )
                            )
                        ),
                        Message(
                            role = "user",
                            content = listOf(ContentItem(type = "input_text", text = userInput))
                        )
                    )
                )

                apiService.analyzeIntent(
                    authorization = llmConfig.bearerToken(),
                    request = request
                )
            }

            val content = response.extractOutputText()
                ?: return Result.failure(Exception("\u63A5\u53E3\u8FD4\u56DE\u5185\u5BB9\u4E3A\u7A7A\u3002"))

            val baseFrameBase64 = encodedFrame?.first
            val parsed = ModelOutputParser.parseIntentResult(
                rawText = content,
                userInput = userInput,
                baseFrameBase64 = baseFrameBase64,
                baselineSource = baselineSource,
                hasImage = frame != null
            )

            val normalized = parsed.copy(baselineImagePath = baselineImagePath).normalized()
            Result.success(
                if (persist) {
                    saveTask(normalized)
                } else {
                    normalized
                }
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun observeTasks(): Flow<List<MonitorTask>> = taskDao.observeTasks()

    suspend fun deleteTask(id: Long) {
        taskDao.deleteById(id)
    }

    suspend fun saveTask(result: IntentResult): IntentResult {
        val normalized = result.normalized()
        val existing = normalized.taskId?.let { taskId ->
            taskDao.getTaskById(taskId)
        }
        val persisted = normalized.toMonitorTask().copy(
            id = normalized.taskId ?: 0,
            createdAt = existing?.createdAt ?: normalized.createdAt,
            lastUsedAt = existing?.lastUsedAt,
            runCount = existing?.runCount ?: 0,
            lastStatus = existing?.lastStatus,
            lastSummary = existing?.lastSummary
        )
        val taskId = taskDao.upsert(persisted)
        return IntentResult.fromTask(persisted.copy(id = if (persisted.id == 0L) taskId else persisted.id))
    }

    suspend fun updateTaskBaseline(
        taskId: Long,
        baseFrameBase64: String,
        baselineImagePath: String? = null
    ): IntentResult? {
        val existing = taskDao.getTaskById(taskId) ?: return null
        val updated = existing.copy(
            baseFrameBase64 = baseFrameBase64,
            baselineImagePath = baselineImagePath,
            updatedAt = System.currentTimeMillis()
        )
        taskDao.upsert(updated)
        return IntentResult.fromTask(updated)
    }

    suspend fun touchTask(taskId: Long, lastStatus: String?, lastSummary: String?) {
        val existing = taskDao.getTaskById(taskId) ?: return
        taskDao.upsert(
            existing.copy(
                updatedAt = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis(),
                runCount = existing.runCount + 1,
                lastStatus = lastStatus ?: existing.lastStatus,
                lastSummary = lastSummary ?: existing.lastSummary
            )
        )
    }

    suspend fun updateTaskOutcome(taskId: Long, lastStatus: String?, lastSummary: String?) {
        val existing = taskDao.getTaskById(taskId) ?: return
        taskDao.upsert(
            existing.copy(
                updatedAt = System.currentTimeMillis(),
                lastStatus = lastStatus,
                lastSummary = lastSummary
            )
        )
    }

    private fun buildIntentPrompt(
        baselineSource: BaselineSource,
        hasImage: Boolean
    ): String {
        val modeRequirement = when {
            !hasImage -> "- \u672A\u63D0\u4F9B\u56FE\u7247\u65F6\uff0cmonitorMode \u53EA\u80FD\u662F SceneBaseline\u3002"
            baselineSource == BaselineSource.UploadedImage ->
                "- \u56FE\u7247\u662F\u7528\u6237\u4E3B\u52A8\u4E0A\u4F20\u7684\u57FA\u51C6\u56FE\u3002\u4F18\u5148\u63CF\u8FF0\u56FE\u4E2D\u4E3B\u4F53/\u76EE\u6807\uff0c\u518D\u7ED3\u5408\u6587\u5B57\u9700\u6C42\u5224\u65AD monitorMode \u662F SceneBaseline \u8FD8\u662F ReferenceTarget\u3002"
            else -> "- \u56FE\u7247\u662F\u4ECE\u5F53\u524D\u89C6\u9891\u6D41\u81EA\u52A8\u622A\u53D6\u7684\u57FA\u51C6\u56FE\uff0cmonitorMode \u53EA\u80FD\u662F SceneBaseline\u3002"
        }

        val descriptionRequirement = if (baselineSource == BaselineSource.UploadedImage && hasImage) {
            "- \u5982\u679C\u5224\u65AD\u4E3A ReferenceTarget\uff0CoriginalSceneDescription \u5FC5\u987B\u805A\u7126\u57FA\u51C6\u56FE\u4E2D\u76EE\u6807\u4E3B\u4F53\u7684\u7A33\u5B9A\u5916\u89C2\u7279\u5F81\uff0c\u4E0D\u8981\u628A\u80CC\u666F\u5F53\u6210\u4E3B\u8981\u53C2\u8003\u3002"
        } else {
            "- originalSceneDescription \u5FC5\u987B\u63CF\u8FF0\u57FA\u51C6\u56FE\u6216\u6B63\u5E38\u573A\u666F\u4E2D\u7684\u7A33\u5B9A\u4E8B\u5B9E\uff0c\u4E0D\u8981\u81C6\u9020\u7EC6\u8282\u3002"
        }

        return """
            \u4F60\u8D1F\u8D23\u628A\u81EA\u7136\u8BED\u8A00\u76D1\u63A7\u9700\u6C42\u8F6C\u6362\u6210\u53EF\u590D\u7528\u7684\u4EFB\u52A1\u914D\u7F6E\u3002
            \u53EA\u8FD4\u56DE JSON\uFF0C\u4E0D\u8981\u8FD4\u56DE Markdown\u3001\u89E3\u91CA\u6216\u989D\u5916\u6587\u672C\u3002
            JSON \u5FC5\u987B\u4E14\u53EA\u80FD\u5305\u542B\u4EE5\u4E0B\u952E\uFF1A
            {
              "title": string,
              "userRequirement": string,
              "originalSceneDescription": string,
              "checkIntervalSeconds": integer,
              "promptTemplate": string,
              "monitorMode": "SceneBaseline" | "ReferenceTarget",
              "targetTrigger": "OnAppear" | "OnDisappear",
              "baselineSource": "CapturedFrame" | "UploadedImage"
            }

            \u8981\u6C42\uFF1A
            - \u4EFB\u52A1\u5FC5\u987B\u9002\u5408\u91CD\u590D\u6027\u7684\u89C6\u89C9\u5DE1\u68C0\u3002
            - checkIntervalSeconds \u5FC5\u987B\u5728 2 \u5230 300 \u4E4B\u95F4\u3002
            $modeRequirement
            - title \u8981\u7B80\u6D01\u660E\u786E\uFF0C\u9002\u5408\u76F4\u63A5\u4F5C\u4E3A\u4EFB\u52A1\u540D\u79F0\u3002
            - userRequirement \u8981\u51C6\u786E\u63D0\u70BC\u7528\u6237\u60F3\u8981\u76D1\u63A7\u7684\u76EE\u6807\u3001\u5F02\u5E38\u6761\u4EF6\u6216\u7ED3\u679C\u3002
            $descriptionRequirement
            - originalSceneDescription \u4E0D\u8981\u5199\u201C\u79BB\u5F00\u5C31\u62A5\u8B66\u201D\u3001\u201C\u5E94\u8BE5\u5728\u8FD9\u91CC\u201D\u3001\u201C\u53EF\u80FD\u8981\u53D8\u5316\u201D\u8FD9\u7C7B\u4EFB\u52A1\u89C4\u5219\u3001\u63A8\u6D4B\u3001\u98CE\u9669\u5224\u65AD\u6216\u672A\u6765\u8D8B\u52BF\u3002
            - \u5982\u679C\u6CA1\u6709\u63D0\u4F9B\u56FE\u7247\uFF0CoriginalSceneDescription \u53EF\u4EE5\u6839\u636E\u7528\u6237\u63CF\u8FF0\u505A\u9002\u5EA6\u6982\u62EC\uFF0C\u4F46\u4E0D\u80FD\u81C6\u9020\u5177\u4F53\u7EC6\u8282\u3002
            - \u770B\u7BA1\u3001\u770B\u4F4F\u3001\u9632\u4E22\u3001\u9632\u62FF\u8D70\u3001\u6301\u7EED\u76EF\u9632\u7C7B\u4EFB\u52A1\u5E94\u503E\u5411\u66F4\u77ED\u7684 checkIntervalSeconds\uFF0C\u901A\u5E38\u4EE5 10 \u79D2\u5DE6\u53F3\u4E3A\u4F18\u5148\u53C2\u8003\uFF1B\u53EA\u6709\u4F4E\u9891\u9759\u6001\u786E\u8BA4\u7C7B\u4EFB\u52A1\u624D\u9002\u5408\u66F4\u957F\u95F4\u9694\u3002
            - baselineSource \u5FC5\u987B\u548C\u56FE\u7247\u6765\u6E90\u4E00\u81F4\u3002
            - \u5982\u679C monitorMode \u662F ReferenceTarget\uff0CtargetTrigger \u53EF\u4EE5\u662F OnAppear \u6216 OnDisappear\uff1B\u5426\u5219 targetTrigger \u9ED8\u8BA4\u4E3A OnAppear\u3002
            - promptTemplate \u5FC5\u987B\u7ED9\u51FA\u540E\u7EED\u89C6\u89C9\u6A21\u578B\u7684\u660E\u786E\u5224\u65AD\u6307\u4EE4\uff0C\u5E76\u4E14\u8981\u548C monitorMode / targetTrigger \u4E00\u81F4\u3002
            - promptTemplate \u5FC5\u987B\u8981\u6C42\u89C6\u89C9\u6A21\u578B\u53EA\u8FD4\u56DE JSON\uff0C\u5B57\u6BB5\u4E3A status\u3001summary\u3001reason\u3001confidence\u3001remark\uff1Bsummary \u7B80\u77ED\u660E\u786E\uff0Creason \u805A\u7126\u57FA\u51C6\u56FE\u4E0E\u5F53\u524D\u753B\u9762\u7684\u5173\u952E\u5DEE\u5F02\u6216\u5339\u914D\u7ED3\u679C\uff0Cstatus \u53EA\u80FD\u662F ALERT\u3001WARNING\u3001NORMAL\u3001UNKNOWN \u4E4B\u4E00\u3002
            - promptTemplate \u5FC5\u987B\u8981\u6C42 remark \u662F\u7ED9\u7528\u6237\u7684\u4E00\u53E5\u7B80\u77ED\u4E2D\u4E8C\u75C5\u8D5B\u535A\u8BD7\u4EBA\u5F0F\u65C1\u767D\uff0C\u53EF\u4EE5\u5938\u5F20\u3001\u53CD\u5DEE\u3001\u6709\u4EBA\u683C\uff0C\u4F46\u4E0D\u80FD\u548C status / summary / reason \u51B2\u7A81\uff0C\u4E0D\u80FD\u7F16\u9020\u753B\u9762\u91CC\u6CA1\u6709\u4F9D\u636E\u7684\u4E8B\u5B9E\u3002
            - \u4E0D\u8981\u8F93\u51FA\u6CE8\u91CA\u3001\u793A\u4F8B\u6216\u591A\u4F59\u5B57\u6BB5\u3002
        """.trimIndent()
    }
}
