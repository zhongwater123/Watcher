package com.example.watcher.data.repository

import com.example.watcher.data.model.LlmProviderEntity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/**
 * Handles JSON-based import/export of API Wallet configurations (LLM providers + ASR).
 *
 * Export format:
 * ```json
 * {
 *   "watcher_wallet": 1,
 *   "providers": [
 *     { "name": "...", "endpoint": "...", "apiKey": "...", "modelName": "..." }
 *   ],
 *   "asr": {
 *     "appKey": "...",
 *     "accessKey": "...",
 *     "resourceId": "..."
 *   }
 * }
 * ```
 */
object WalletShareManager {

    private const val FORMAT_KEY = "watcher_wallet"
    private const val FORMAT_VERSION = 1
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class ImportedProvider(
        val name: String,
        val endpoint: String,
        val apiKey: String,
        val modelName: String
    )

    data class ImportedAsrConfig(
        val appKey: String,
        val accessKey: String,
        val resourceId: String
    )

    data class ImportResult(
        val providers: List<ImportedProvider>,
        val asr: ImportedAsrConfig?
    )

    internal fun exportAll(
        providers: List<LlmProviderEntity>,
        asrCredentials: VolcengineAsrCredentials? = null
    ): String {
        val payload = mutableMapOf<String, Any>(
            FORMAT_KEY to FORMAT_VERSION,
            "providers" to providers.map { it.toExportMap() }
        )
        if (asrCredentials != null && asrCredentials.isConfigured()) {
            payload["asr"] = mapOf(
                "appKey" to asrCredentials.appKey,
                "accessKey" to asrCredentials.accessKey,
                "resourceId" to asrCredentials.resourceId
            )
        }
        return gson.toJson(payload)
    }

    fun exportProviders(providers: List<LlmProviderEntity>): String {
        return exportAll(providers, asrCredentials = null)
    }

    fun exportSingleProvider(provider: LlmProviderEntity): String {
        return exportProviders(listOf(provider))
    }

    fun canImport(text: String): Boolean {
        return try {
            val json = JsonParser.parseString(text.trim())
            json.isJsonObject && json.asJsonObject.has(FORMAT_KEY)
        } catch (_: Exception) {
            false
        }
    }

    fun importAll(text: String): Result<ImportResult> = runCatching {
        val json = JsonParser.parseString(text.trim()).asJsonObject
        require(json.has(FORMAT_KEY)) { "不是有效的 Watcher 钱包配置文件。" }

        val version = json.get(FORMAT_KEY).asInt
        require(version >= 1) { "配置文件版本不支持。" }

        val providers = mutableListOf<ImportedProvider>()
        val providersArray = json.getAsJsonArray("providers")
        if (providersArray != null && providersArray.size() > 0) {
            providersArray.forEach { element ->
                val obj = element.asJsonObject
                val name = obj.get("name")?.asString?.trim().orEmpty()
                val endpoint = obj.get("endpoint")?.asString?.trim().orEmpty()
                val apiKey = obj.get("apiKey")?.asString?.trim().orEmpty()
                val modelName = obj.get("modelName")?.asString?.trim().orEmpty()

                require(name.isNotBlank()) { "供应商名称不能为空。" }
                require(endpoint.isNotBlank()) { "供应商 endpoint 不能为空。" }
                require(apiKey.isNotBlank()) { "供应商 API Key 不能为空。" }
                require(modelName.isNotBlank()) { "供应商模型名不能为空。" }

                providers.add(ImportedProvider(name, endpoint, apiKey, modelName))
            }
        }

        val asr = json.getAsJsonObject("asr")?.let { asrObj ->
            val appKey = asrObj.get("appKey")?.asString?.trim().orEmpty()
            val accessKey = asrObj.get("accessKey")?.asString?.trim().orEmpty()
            val resourceId = asrObj.get("resourceId")?.asString?.trim().orEmpty()
            if (appKey.isNotBlank() && accessKey.isNotBlank()) {
                ImportedAsrConfig(appKey, accessKey, resourceId)
            } else null
        }

        require(providers.isNotEmpty() || asr != null) { "配置文件中没有可导入的数据。" }

        ImportResult(providers = providers, asr = asr)
    }

    @Deprecated("Use importAll", ReplaceWith("importAll(text)"))
    fun importProviders(text: String): Result<List<ImportedProvider>> {
        return importAll(text).map { it.providers }
    }

    private fun LlmProviderEntity.toExportMap(): Map<String, String> {
        return mapOf(
            "name" to name,
            "endpoint" to endpoint,
            "apiKey" to apiKey,
            "modelName" to modelName
        )
    }
}
