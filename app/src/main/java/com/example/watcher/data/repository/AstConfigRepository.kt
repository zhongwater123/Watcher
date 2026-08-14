package com.example.watcher.data.repository

import android.content.Context

internal const val VOLCENGINE_AST_WS_URL = "wss://openspeech.bytedance.com/api/v4/ast/v2/translate"
internal const val DEFAULT_VOLCENGINE_AST_RESOURCE_ID = "volc.service_type.10053"

internal data class ResolvedAstConfig(
    val credentials: VolcengineAstCredentials = VolcengineAstCredentials(),
    val source: AsrConfigSource = AsrConfigSource.Missing,
    val connectivity: AsrConnectivitySnapshot = AsrConnectivitySnapshot()
)

internal class AstConfigRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(AST_CONFIG_PREFS, Context.MODE_PRIVATE)
    private val secretStore = AppRuntimeSecretStore(appContext)

    fun resolveConfig(): ResolvedAstConfig {
        val stored = secretStore.getStoredVolcengineAstCredentials()
        return ResolvedAstConfig(
            credentials = stored,
            source = if (stored.isConfigured()) AsrConfigSource.Wallet else AsrConfigSource.Missing,
            connectivity = getConnectivitySnapshot()
        )
    }

    fun resolveRuntimeCredentials(): VolcengineAstCredentials {
        return secretStore.getStoredVolcengineAstCredentials()
    }

    fun saveCredentials(credentials: VolcengineAstCredentials) {
        secretStore.putVolcengineAstCredentials(credentials)
    }

    fun clearCredentials() {
        secretStore.clearVolcengineAstCredentials()
    }

    fun getConnectivitySnapshot(): AsrConnectivitySnapshot {
        val statusValue = prefs.getString(KEY_AST_TEST_STATUS, null)
        val status = statusValue?.let {
            runCatching { AsrConnectivityStatus.valueOf(it) }.getOrNull()
        } ?: AsrConnectivityStatus.Untested
        val testedAt = prefs.getLong(KEY_AST_TEST_TIME, 0L).takeIf { it > 0L }
        val message = prefs.getString(KEY_AST_TEST_MESSAGE, null)?.takeIf { it.isNotBlank() }
        return AsrConnectivitySnapshot(
            status = status,
            lastTestedAt = testedAt,
            message = message
        )
    }

    fun setConnectivitySnapshot(
        status: AsrConnectivityStatus,
        message: String?,
        testedAt: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putString(KEY_AST_TEST_STATUS, status.name)
            .putLong(KEY_AST_TEST_TIME, testedAt)
            .putString(KEY_AST_TEST_MESSAGE, message.orEmpty())
            .apply()
    }

    fun clearConnectivitySnapshot() {
        prefs.edit()
            .remove(KEY_AST_TEST_STATUS)
            .remove(KEY_AST_TEST_TIME)
            .remove(KEY_AST_TEST_MESSAGE)
            .apply()
    }

    suspend fun testCredentials(credentials: VolcengineAstCredentials): String {
        require(credentials.isConfigured()) {
            "请先填写完整的 AST 鉴权配置和 Resource ID。"
        }
        return StreamingAstClient.testCredentials(credentials)
    }

    private companion object {
        private const val AST_CONFIG_PREFS = "ast_config_prefs"
        private const val KEY_AST_TEST_STATUS = "ast_test_status"
        private const val KEY_AST_TEST_TIME = "ast_test_time"
        private const val KEY_AST_TEST_MESSAGE = "ast_test_message"
    }
}
