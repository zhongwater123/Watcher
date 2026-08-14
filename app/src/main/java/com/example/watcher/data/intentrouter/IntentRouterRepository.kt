package com.example.watcher.data.intentrouter

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.watcher.R
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.Message
import com.example.watcher.data.remote.extractOutputText
import com.example.watcher.data.repository.ArkConfig
import com.example.watcher.data.repository.LlmWalletRepository
import retrofit2.HttpException

class IntentRouterRepository(
    private val appContext: Context,
    private val apiService: DoubaoApiService,
    private val llmWalletRepository: LlmWalletRepository
) {
    suspend fun route(
        userInput: String,
        traceId: String = IntentRouterTrace.next()
    ): Result<IntentRouterDecision> {
        val startedAt = SystemClock.elapsedRealtime()
        val requestText = userInput.trim()
        if (requestText.isBlank()) {
            Log.d(IntentRouterLog.TAG, "traceId=$traceId repository route ignored reason=blank_input")
            return Result.failure(IllegalArgumentException("请输入你想做什么。"))
        }

        Log.d(
            IntentRouterLog.TAG,
            "traceId=$traceId repository route start inputLength=${requestText.length} preview=\"${IntentRouterLog.preview(requestText)}\""
        )
        findLocalRoute(requestText, phase = "precheck", startedAt = startedAt, traceId = traceId)?.let { decision ->
            return Result.success(decision)
        }
        return try {
            val llmConfig = llmWalletRepository.resolveArkResponsesConfig(ArkConfig.intentModel)
            Log.d(
                IntentRouterLog.TAG,
                "traceId=$traceId repository llm config resolved model=${llmConfig.modelName}"
            )
            val systemPrompt = buildSystemPrompt()
            Log.d(
                IntentRouterLog.TAG,
                "traceId=$traceId repository request sending promptLength=${systemPrompt.length} routeCount=${IntentRouteCatalog.routes.size} structuredOutput=false"
            )
            val response = apiService.analyzeIntent(
                authorization = llmConfig.bearerToken(),
                request = DoubaoRequest(
                    model = llmConfig.modelName,
                    input = listOf(
                        Message(
                            role = "system",
                            content = listOf(
                                ContentItem(
                                    type = "input_text",
                                    text = systemPrompt
                                )
                            )
                        ),
                        Message(
                            role = "user",
                            content = listOf(
                                ContentItem(type = "input_text", text = requestText)
                            )
                        )
                    )
                )
            )
            Log.d(
                IntentRouterLog.TAG,
                "traceId=$traceId repository response received elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )

            val output = response.extractOutputText()
                ?: run {
                    Log.w(
                        IntentRouterLog.TAG,
                        "traceId=$traceId repository response empty elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return Result.failure(IllegalStateException("LLM 返回内容为空。"))
                }
            Log.d(
                IntentRouterLog.TAG,
                "traceId=$traceId repository output extracted length=${output.length} preview=\"${IntentRouterLog.preview(output)}\""
            )

            when (val parsed = IntentRouterResponseParser.parse(output)) {
                is IntentRouterParseResult.Success -> {
                    Log.d(
                        IntentRouterLog.TAG,
                        "traceId=$traceId repository parse success routeId=${parsed.decision.route.id.wireId} confidence=${parsed.decision.confidence} source=${parsed.decision.source} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    Result.success(parsed.decision)
                }
                is IntentRouterParseResult.Failure -> {
                    Log.w(
                        IntentRouterLog.TAG,
                        "traceId=$traceId repository parse failed reason=${parsed.reason} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    findLocalRoute(requestText, phase = "parse_fallback", startedAt = startedAt, traceId = traceId)?.let { decision ->
                        return Result.success(decision)
                    }
                    Result.failure(IllegalStateException(parsed.reason))
                }
            }
        } catch (error: HttpException) {
            val errorBody = runCatching {
                error.response()?.errorBody()?.string()
            }.getOrNull().orEmpty()
            Log.e(
                IntentRouterLog.TAG,
                "traceId=$traceId repository route http failed elapsedMs=${SystemClock.elapsedRealtime() - startedAt} code=${error.code()} body=\"${IntentRouterLog.preview(errorBody, maxLength = 240)}\"",
                error
            )
            findLocalRoute(requestText, phase = "http_fallback", startedAt = startedAt, traceId = traceId)?.let { decision ->
                return Result.success(decision)
            }
            Result.failure(error)
        } catch (error: Exception) {
            Log.e(
                IntentRouterLog.TAG,
                "traceId=$traceId repository route failed elapsedMs=${SystemClock.elapsedRealtime() - startedAt} error=${error::class.java.simpleName}: ${error.message}",
                error
            )
            findLocalRoute(requestText, phase = "error_fallback", startedAt = startedAt, traceId = traceId)?.let { decision ->
                return Result.success(decision)
            }
            Result.failure(error)
        }
    }

    private fun findLocalRoute(
        requestText: String,
        phase: String,
        startedAt: Long,
        traceId: String
    ): IntentRouterDecision? {
        val decision = IntentRouteKeywordMatcher.match(requestText) ?: run {
            Log.d(
                IntentRouterLog.TAG,
                "traceId=$traceId repository local route miss phase=$phase elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return null
        }
        Log.d(
            IntentRouterLog.TAG,
            "traceId=$traceId repository local route matched phase=$phase routeId=${decision.route.id.wireId} confidence=${decision.confidence} source=${decision.source} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return decision
    }

    private fun buildSystemPrompt(): String {
        val basePrompt = appContext.resources.openRawResource(R.raw.intent_router_prompt)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val routeList = IntentRouteCatalog.routes.joinToString(separator = "\n") { route ->
            val examples = route.examples.joinToString(separator = "；")
            val keywords = route.keywords.joinToString(separator = "，")
            "- ${route.id.wireId}: ${route.title}。${route.description} 关键词：$keywords。示例：$examples"
        }
        val prompt = "$basePrompt\n\n可用 routeId：\n$routeList"
        Log.d(
            IntentRouterLog.TAG,
            "repository prompt built baseLength=${basePrompt.length} finalLength=${prompt.length}"
        )
        return prompt
    }
}
