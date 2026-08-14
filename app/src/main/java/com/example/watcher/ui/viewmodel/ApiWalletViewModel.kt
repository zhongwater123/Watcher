package com.example.watcher.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.remote.ChatMessage
import com.example.watcher.data.remote.OpenAiCompatibleProvider
import com.example.watcher.data.repository.AsrConfigRepository
import com.example.watcher.data.repository.AsrConfigSource
import com.example.watcher.data.repository.AsrConnectivitySnapshot
import com.example.watcher.data.repository.AsrConnectivityStatus
import com.example.watcher.data.repository.ArkConfig
import com.example.watcher.data.repository.AstConfigRepository
import com.example.watcher.data.repository.DEFAULT_DOUBAO_STREAMING_ASR_RESOURCE_ID
import com.example.watcher.data.repository.DEFAULT_VOLCENGINE_AST_RESOURCE_ID
import com.example.watcher.data.repository.LlmWalletRepository
import com.example.watcher.data.repository.WalletShareManager
import com.example.watcher.data.repository.ProviderConnectivitySnapshot
import com.example.watcher.data.repository.ProviderConnectivityStatus
import com.example.watcher.data.repository.VolcengineAsrCredentials
import com.example.watcher.data.repository.VolcengineAstAuthMode
import com.example.watcher.data.repository.VolcengineAstCredentials
import com.example.watcher.data.repository.validateSecureEndpoint
import com.example.watcher.BuildConfig
import com.example.watcher.watcherApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class ApiWalletDraft(
    val id: String? = null,
    val name: String = "",
    val endpoint: String = "https://",
    val apiKey: String = "",
    val modelName: String = "",
    val enabled: Boolean = true
)

data class AsrConfigDraft(
    val appKey: String = "",
    val accessKey: String = "",
    val resourceId: String = DEFAULT_DOUBAO_STREAMING_ASR_RESOURCE_ID
) {
    internal fun toCredentials(): VolcengineAsrCredentials {
        return VolcengineAsrCredentials(
            appKey = appKey.trim(),
            accessKey = accessKey.trim(),
            resourceId = resourceId.trim().ifBlank { DEFAULT_DOUBAO_STREAMING_ASR_RESOURCE_ID }
        )
    }
}

data class AsrConfigUiState(
    val draft: AsrConfigDraft = AsrConfigDraft(),
    val source: AsrConfigSource = AsrConfigSource.Missing,
    val connectivity: AsrConnectivitySnapshot = AsrConnectivitySnapshot(),
    val isSaving: Boolean = false,
    val isTesting: Boolean = false
) {
    val isConfigured: Boolean
        get() = draft.toCredentials().isConfigured()
}

data class AstConfigDraft(
    val authMode: VolcengineAstAuthMode = VolcengineAstAuthMode.ApiKey,
    val apiKey: String = "",
    val appKey: String = "",
    val accessKey: String = "",
    val resourceId: String = DEFAULT_VOLCENGINE_AST_RESOURCE_ID
) {
    internal fun toCredentials(): VolcengineAstCredentials {
        return VolcengineAstCredentials(
            authMode = authMode,
            apiKey = apiKey.trim(),
            appKey = appKey.trim(),
            accessKey = accessKey.trim(),
            resourceId = resourceId.trim().ifBlank { DEFAULT_VOLCENGINE_AST_RESOURCE_ID }
        )
    }
}

data class AstConfigUiState(
    val draft: AstConfigDraft = AstConfigDraft(),
    val source: AsrConfigSource = AsrConfigSource.Missing,
    val connectivity: AsrConnectivitySnapshot = AsrConnectivitySnapshot(),
    val isSaving: Boolean = false,
    val isTesting: Boolean = false
) {
    val isConfigured: Boolean
        get() = draft.toCredentials().isConfigured()
}

data class ApiWalletUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val testingProviderId: String? = null,
    val providers: List<LlmProviderEntity> = emptyList(),
    val providerConnectivity: Map<String, ProviderConnectivitySnapshot> = emptyMap(),
    val defaultProviderId: String? = null,
    val isEditing: Boolean = false,
    val draft: ApiWalletDraft = ApiWalletDraft(),
    val asrConfig: AsrConfigUiState = AsrConfigUiState(),
    val astConfig: AstConfigUiState = AstConfigUiState(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val arkFallbackAvailable: Boolean = false,
    val showImportDialog: Boolean = false,
    val showExportWarning: Boolean = false,
    val pendingExportProviderId: String? = null
) {
    val defaultProvider: LlmProviderEntity?
        get() = providers.firstOrNull { it.id == defaultProviderId }
}

class ApiWalletViewModel(application: Application) : AndroidViewModel(application) {
    private val walletRepository: LlmWalletRepository =
        application.watcherApplication().agentFrameworkContainer.llmWalletRepository
    private val asrConfigRepository = AsrConfigRepository(application)
    private val astConfigRepository = AstConfigRepository(application)

    private val _uiState = MutableStateFlow(
        ApiWalletUiState(arkFallbackAvailable = ArkConfig.apiKey.isNotBlank())
    )
    val uiState: StateFlow<ApiWalletUiState> = _uiState.asStateFlow()

    init {
        observeProviders()
        syncAsrConfig()
        syncAstConfig()
    }

    fun startCreate() {
        _uiState.value = _uiState.value.copy(
            isEditing = true,
            draft = ApiWalletDraft(),
            statusMessage = null,
            errorMessage = null
        )
    }

    fun startEdit(providerId: String) {
        val provider = _uiState.value.providers.firstOrNull { it.id == providerId } ?: return
        _uiState.value = _uiState.value.copy(
            isEditing = true,
            draft = provider.toDraft(),
            statusMessage = null,
            errorMessage = null
        )
    }

    fun cancelEditing() {
        _uiState.value = _uiState.value.copy(
            isEditing = false,
            draft = ApiWalletDraft(),
            statusMessage = null,
            errorMessage = null
        )
    }

    fun updateDraft(transform: (ApiWalletDraft) -> ApiWalletDraft) {
        _uiState.value = _uiState.value.copy(
            draft = transform(_uiState.value.draft),
            statusMessage = null,
            errorMessage = null
        )
    }

    fun updateAsrDraft(transform: (AsrConfigDraft) -> AsrConfigDraft) {
        _uiState.value = _uiState.value.copy(
            asrConfig = _uiState.value.asrConfig.copy(
                draft = transform(_uiState.value.asrConfig.draft)
            ),
            statusMessage = null,
            errorMessage = null
        )
    }

    fun updateAstDraft(transform: (AstConfigDraft) -> AstConfigDraft) {
        _uiState.value = _uiState.value.copy(
            astConfig = _uiState.value.astConfig.copy(
                draft = transform(_uiState.value.astConfig.draft)
            ),
            statusMessage = null,
            errorMessage = null
        )
    }

    fun saveDraft() {
        val current = _uiState.value
        val draft = current.draft
        if (draft.name.isBlank() || draft.endpoint.isBlank() || draft.modelName.isBlank()) {
            _uiState.value = current.copy(errorMessage = "Name, endpoint and model are required.")
            return
        }
        validateSecureEndpoint(draft.endpoint, "Provider endpoint")?.let { error ->
            _uiState.value = current.copy(errorMessage = error)
            return
        }

        val existing = current.providers.firstOrNull { it.id == draft.id }
        val provider = LlmProviderEntity(
            id = draft.id ?: buildProviderId(draft.name),
            name = draft.name.trim(),
            endpoint = draft.endpoint.trim(),
            apiKey = draft.apiKey.trim(),
            modelName = draft.modelName.trim(),
            enabled = draft.enabled,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        _uiState.value = current.copy(isSaving = true, statusMessage = null, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                val connectionChanged = existing?.let {
                    it.endpoint != provider.endpoint ||
                        it.apiKey != provider.apiKey ||
                        it.modelName != provider.modelName
                } ?: false
                walletRepository.upsertProvider(
                    provider = provider,
                    makeDefault = current.defaultProviderId.isNullOrBlank() ||
                        current.defaultProviderId == provider.id
                )
                if (connectionChanged) {
                    walletRepository.clearProviderConnectivitySnapshot(provider.id)
                }
                if (!provider.enabled && current.defaultProviderId == provider.id) {
                    val replacementId = current.providers
                        .firstOrNull { it.id != provider.id && it.enabled }
                        ?.id
                    walletRepository.setDefaultProviderId(replacementId)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isEditing = false,
                    draft = ApiWalletDraft(),
                    statusMessage = "Provider saved."
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = error.message ?: "Failed to save provider."
                )
            }
        }
    }

    fun testProvider(providerId: String) {
        val provider = _uiState.value.providers.firstOrNull { it.id == providerId }
        if (provider == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Provider not found.")
            return
        }

        _uiState.value = _uiState.value.copy(
            testingProviderId = providerId,
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching {
                OpenAiCompatibleProvider(
                    id = provider.id,
                    displayName = provider.name,
                    endpoint = provider.endpoint,
                    apiKey = provider.apiKey,
                    modelName = provider.modelName
                ).chat(
                    systemPrompt = "You are a connectivity check. Reply with a short acknowledgement.",
                    messages = listOf(ChatMessage(role = "user", content = "你好，你是谁"))
                ).trim()
            }.onSuccess { response ->
                val preview = response.replace('\n', ' ').trim().take(160)
                walletRepository.setProviderConnectivitySnapshot(
                    providerId = provider.id,
                    status = ProviderConnectivityStatus.Verified,
                    message = preview.ifBlank { "Connection succeeded." }
                )
                _uiState.value = _uiState.value.copy(
                    testingProviderId = null,
                    providerConnectivity = _uiState.value.providerConnectivity + (
                        provider.id to walletRepository.getProviderConnectivitySnapshot(provider.id)
                    ),
                    statusMessage = if (preview.isBlank()) {
                        "Provider test passed."
                    } else {
                        "Provider test passed: $preview"
                    }
                )
            }.onFailure { error ->
                walletRepository.setProviderConnectivitySnapshot(
                    providerId = provider.id,
                    status = ProviderConnectivityStatus.Failed,
                    message = error.message ?: "Provider test failed."
                )
                _uiState.value = _uiState.value.copy(
                    testingProviderId = null,
                    providerConnectivity = _uiState.value.providerConnectivity + (
                        provider.id to walletRepository.getProviderConnectivitySnapshot(provider.id)
                    ),
                    errorMessage = error.message ?: "Provider test failed."
                )
            }
        }
    }

    fun setDefaultProvider(providerId: String) {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
        viewModelScope.launch {
            val provider = walletRepository.getProviderById(providerId)
            if (provider == null) {
                _uiState.value = _uiState.value.copy(errorMessage = "Provider not found.")
                return@launch
            }
            if (!provider.enabled) {
                walletRepository.upsertProvider(
                    provider.copy(enabled = true, updatedAt = System.currentTimeMillis()),
                    makeDefault = false
                )
            }
            walletRepository.setDefaultProviderId(providerId)
            _uiState.value = _uiState.value.copy(
                defaultProviderId = providerId,
                statusMessage = "Default wallet switched."
            )
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
        viewModelScope.launch {
            val provider = walletRepository.getProviderById(providerId)
            if (provider == null) {
                _uiState.value = _uiState.value.copy(errorMessage = "Provider not found.")
                return@launch
            }

            runCatching {
                walletRepository.upsertProvider(
                    provider.copy(enabled = enabled, updatedAt = System.currentTimeMillis()),
                    makeDefault = false
                )
                if (!enabled && _uiState.value.defaultProviderId == providerId) {
                    val replacementId = _uiState.value.providers
                        .firstOrNull { it.id != providerId && it.enabled }
                        ?.id
                    walletRepository.setDefaultProviderId(replacementId)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    statusMessage = if (enabled) "Provider enabled." else "Provider disabled."
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message ?: "Failed to update provider."
                )
            }
        }
    }

    fun deleteProvider(providerId: String) {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                walletRepository.deleteProvider(providerId)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(statusMessage = "Provider deleted.")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message ?: "Failed to delete provider."
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
    }

    // --- Import / Export ---

    fun showImportDialog() {
        _uiState.value = _uiState.value.copy(showImportDialog = true, statusMessage = null, errorMessage = null)
    }

    fun dismissImportDialog() {
        _uiState.value = _uiState.value.copy(showImportDialog = false)
    }

    fun requestExportProvider(providerId: String?) {
        _uiState.value = _uiState.value.copy(
            showExportWarning = true,
            pendingExportProviderId = providerId,
            statusMessage = null,
            errorMessage = null
        )
    }

    fun dismissExportWarning() {
        _uiState.value = _uiState.value.copy(showExportWarning = false, pendingExportProviderId = null)
    }

    fun confirmExport(): String? {
        val current = _uiState.value
        val providerId = current.pendingExportProviderId
        val asrCredentials = current.asrConfig.draft.toCredentials()
        val json = if (providerId != null) {
            val provider = current.providers.firstOrNull { it.id == providerId }
            provider?.let { WalletShareManager.exportSingleProvider(it) }
        } else {
            if (current.providers.isEmpty() && !asrCredentials.isConfigured()) null
            else WalletShareManager.exportAll(current.providers, asrCredentials)
        }
        _uiState.value = current.copy(
            showExportWarning = false,
            pendingExportProviderId = null,
            statusMessage = if (json != null) "配置已复制到剪贴板，请注意保护您的密钥安全。" else null,
            errorMessage = if (json == null) "没有可导出的配置。" else null
        )
        return json
    }

    fun importFromText(text: String) {
        val result = WalletShareManager.importAll(text)
        result.onSuccess { imported ->
            viewModelScope.launch {
                var providerCount = 0
                for ((index, item) in imported.providers.withIndex()) {
                    val provider = LlmProviderEntity(
                        id = buildProviderId(item.name),
                        name = item.name,
                        endpoint = item.endpoint,
                        apiKey = item.apiKey,
                        modelName = item.modelName,
                        enabled = true,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    runCatching {
                        walletRepository.upsertProvider(
                            provider = provider,
                            makeDefault = index == 0
                        )
                    }.onSuccess { providerCount++ }
                }

                var asrImported = false
                imported.asr?.let { asr ->
                    val credentials = VolcengineAsrCredentials(
                        appKey = asr.appKey,
                        accessKey = asr.accessKey,
                        resourceId = asr.resourceId.ifBlank { DEFAULT_DOUBAO_STREAMING_ASR_RESOURCE_ID }
                    )
                    runCatching {
                        asrConfigRepository.saveCredentials(credentials)
                    }.onSuccess { asrImported = true }
                }

                val message = buildString {
                    if (providerCount > 0) append("成功导入 $providerCount 个供应商")
                    if (asrImported) {
                        if (providerCount > 0) append("，")
                        append("语音识别配置已导入")
                    }
                    append("。")
                }

                syncAsrConfig()
                _uiState.value = _uiState.value.copy(
                    showImportDialog = false,
                    statusMessage = message,
                    errorMessage = null
                )
            }
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                errorMessage = error.message ?: "导入失败，请检查配置格式。"
            )
        }
    }

    fun saveAsrDraft() {
        val current = _uiState.value
        val credentials = current.asrConfig.draft.toCredentials()
        if (!credentials.isConfigured()) {
            _uiState.value = current.copy(errorMessage = "请填写完整的 App Key、Access Key 和 Resource ID。")
            return
        }

        _uiState.value = current.copy(
            asrConfig = current.asrConfig.copy(isSaving = true),
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching {
                val resolved = resolveBuildConfigFallback()
                val previous = asrConfigRepository.resolveConfig(resolved).credentials
                asrConfigRepository.saveCredentials(credentials)
                if (previous != credentials) {
                    asrConfigRepository.clearConnectivitySnapshot()
                }
            }.onSuccess {
                syncAsrConfig(statusMessage = "语音识别配置已保存。")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    asrConfig = _uiState.value.asrConfig.copy(isSaving = false),
                    errorMessage = error.message ?: "保存语音识别配置失败。"
                )
            }
        }
    }

    fun clearAsrConfig() {
        val current = _uiState.value
        _uiState.value = current.copy(
            asrConfig = current.asrConfig.copy(isSaving = true),
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching {
                asrConfigRepository.clearCredentials()
                asrConfigRepository.clearConnectivitySnapshot()
            }.onSuccess {
                syncAsrConfig(statusMessage = "语音识别配置已清空。")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    asrConfig = _uiState.value.asrConfig.copy(isSaving = false),
                    errorMessage = error.message ?: "清空语音识别配置失败。"
                )
            }
        }
    }

    fun testAsrConfig() {
        val current = _uiState.value
        val credentials = current.asrConfig.draft.toCredentials()
        if (!credentials.isConfigured()) {
            _uiState.value = current.copy(errorMessage = "请先填写完整的 App Key、Access Key 和 Resource ID。")
            return
        }

        _uiState.value = current.copy(
            asrConfig = current.asrConfig.copy(isTesting = true),
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching {
                asrConfigRepository.testCredentials(credentials)
            }.onSuccess { message ->
                val snapshot = AsrConnectivitySnapshot(
                    status = AsrConnectivityStatus.Verified,
                    lastTestedAt = System.currentTimeMillis(),
                    message = message
                )
                asrConfigRepository.setConnectivitySnapshot(
                    status = snapshot.status,
                    message = snapshot.message,
                    testedAt = snapshot.lastTestedAt ?: System.currentTimeMillis()
                )
                _uiState.value = _uiState.value.copy(
                    asrConfig = _uiState.value.asrConfig.copy(
                        isTesting = false,
                        connectivity = snapshot
                    ),
                    statusMessage = "语音识别初始化验证成功。"
                )
            }.onFailure { error ->
                val snapshot = AsrConnectivitySnapshot(
                    status = AsrConnectivityStatus.Failed,
                    lastTestedAt = System.currentTimeMillis(),
                    message = error.message ?: "连接失败。"
                )
                asrConfigRepository.setConnectivitySnapshot(
                    status = snapshot.status,
                    message = snapshot.message,
                    testedAt = snapshot.lastTestedAt ?: System.currentTimeMillis()
                )
                _uiState.value = _uiState.value.copy(
                    asrConfig = _uiState.value.asrConfig.copy(
                        isTesting = false,
                        connectivity = snapshot
                    ),
                    errorMessage = error.message ?: "语音识别连接测试失败。"
                )
            }
        }
    }

    fun saveAstDraft() {
        val current = _uiState.value
        val credentials = current.astConfig.draft.toCredentials()
        if (!credentials.isConfigured()) {
            _uiState.value = current.copy(errorMessage = "请填写完整的 AST 鉴权配置和 Resource ID。")
            return
        }

        _uiState.value = current.copy(
            astConfig = current.astConfig.copy(isSaving = true),
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching {
                val previous = astConfigRepository.resolveConfig().credentials
                astConfigRepository.saveCredentials(credentials)
                if (previous != credentials) {
                    astConfigRepository.clearConnectivitySnapshot()
                }
            }.onSuccess {
                syncAstConfig(statusMessage = "AST 复杂语种配置已保存。")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    astConfig = _uiState.value.astConfig.copy(isSaving = false),
                    errorMessage = error.message ?: "保存 AST 配置失败。"
                )
            }
        }
    }

    fun clearAstConfig() {
        val current = _uiState.value
        _uiState.value = current.copy(
            astConfig = current.astConfig.copy(isSaving = true),
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching {
                astConfigRepository.clearCredentials()
                astConfigRepository.clearConnectivitySnapshot()
            }.onSuccess {
                syncAstConfig(statusMessage = "AST 配置已清空。")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    astConfig = _uiState.value.astConfig.copy(isSaving = false),
                    errorMessage = error.message ?: "清空 AST 配置失败。"
                )
            }
        }
    }

    fun testAstConfig() {
        val current = _uiState.value
        val credentials = current.astConfig.draft.toCredentials()
        if (!credentials.isConfigured()) {
            _uiState.value = current.copy(errorMessage = "请先填写完整的 AST 鉴权配置和 Resource ID。")
            return
        }

        _uiState.value = current.copy(
            astConfig = current.astConfig.copy(isTesting = true),
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching {
                astConfigRepository.testCredentials(credentials)
            }.onSuccess { message ->
                val snapshot = AsrConnectivitySnapshot(
                    status = AsrConnectivityStatus.Verified,
                    lastTestedAt = System.currentTimeMillis(),
                    message = message
                )
                astConfigRepository.setConnectivitySnapshot(
                    status = snapshot.status,
                    message = snapshot.message,
                    testedAt = snapshot.lastTestedAt ?: System.currentTimeMillis()
                )
                _uiState.value = _uiState.value.copy(
                    astConfig = _uiState.value.astConfig.copy(
                        isTesting = false,
                        connectivity = snapshot
                    ),
                    statusMessage = "AST 会话初始化验证成功。"
                )
            }.onFailure { error ->
                val snapshot = AsrConnectivitySnapshot(
                    status = AsrConnectivityStatus.Failed,
                    lastTestedAt = System.currentTimeMillis(),
                    message = error.message ?: "连接失败。"
                )
                astConfigRepository.setConnectivitySnapshot(
                    status = snapshot.status,
                    message = snapshot.message,
                    testedAt = snapshot.lastTestedAt ?: System.currentTimeMillis()
                )
                _uiState.value = _uiState.value.copy(
                    astConfig = _uiState.value.astConfig.copy(
                        isTesting = false,
                        connectivity = snapshot
                    ),
                    errorMessage = error.message ?: "AST 连接测试失败。"
                )
            }
        }
    }

    private fun observeProviders() {
        viewModelScope.launch {
            walletRepository.observeProviders().collect { providers ->
                _uiState.value = _uiState.value.copy(
                    testingProviderId = null,
                    isLoading = false,
                    providers = providers,
                    providerConnectivity = providers.associate { provider ->
                        provider.id to walletRepository.getProviderConnectivitySnapshot(provider.id)
                    },
                    defaultProviderId = resolveEffectiveDefaultId(
                        providers = providers,
                        storedDefaultId = walletRepository.getDefaultProviderId()
                    ),
                    arkFallbackAvailable = ArkConfig.apiKey.isNotBlank()
                )
            }
        }
    }

    private fun syncAsrConfig(
        statusMessage: String? = null,
        errorMessage: String? = null
    ) {
        val resolved = asrConfigRepository.resolveConfig(resolveBuildConfigFallback())
        _uiState.value = _uiState.value.copy(
            asrConfig = _uiState.value.asrConfig.copy(
                draft = resolved.credentials.toDraft(),
                source = resolved.source,
                connectivity = resolved.connectivity,
                isSaving = false,
                isTesting = false
            ),
            statusMessage = statusMessage,
            errorMessage = errorMessage
        )
    }

    private fun syncAstConfig(
        statusMessage: String? = null,
        errorMessage: String? = null
    ) {
        val resolved = astConfigRepository.resolveConfig()
        _uiState.value = _uiState.value.copy(
            astConfig = _uiState.value.astConfig.copy(
                draft = resolved.credentials.toDraft(),
                source = resolved.source,
                connectivity = resolved.connectivity,
                isSaving = false,
                isTesting = false
            ),
            statusMessage = statusMessage,
            errorMessage = errorMessage
        )
    }

    private fun resolveBuildConfigFallback(): VolcengineAsrCredentials {
        return VolcengineAsrCredentials(
            appKey = BuildConfig.VOLCENGINE_ASR_APP_KEY,
            accessKey = BuildConfig.VOLCENGINE_ASR_ACCESS_KEY,
            resourceId = BuildConfig.VOLCENGINE_ASR_RESOURCE_ID
        )
    }

    private fun resolveEffectiveDefaultId(
        providers: List<LlmProviderEntity>,
        storedDefaultId: String?
    ): String? {
        val stored = providers.firstOrNull { it.id == storedDefaultId && it.enabled }
        return stored?.id ?: providers.firstOrNull { it.enabled }?.id
    }

    private fun buildProviderId(name: String): String {
        return buildString {
            append(
                name.trim()
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
                    .ifBlank { "provider" }
            )
            append("_")
            append(System.currentTimeMillis())
        }
    }
}

private fun LlmProviderEntity.toDraft(): ApiWalletDraft {
    return ApiWalletDraft(
        id = id,
        name = name,
        endpoint = endpoint,
        apiKey = apiKey,
        modelName = modelName,
        enabled = enabled
    )
}

private fun VolcengineAsrCredentials.toDraft(): AsrConfigDraft {
    return AsrConfigDraft(
        appKey = appKey,
        accessKey = accessKey,
        resourceId = resourceId.ifBlank { DEFAULT_DOUBAO_STREAMING_ASR_RESOURCE_ID }
    )
}

private fun VolcengineAstCredentials.toDraft(): AstConfigDraft {
    return AstConfigDraft(
        authMode = authMode,
        apiKey = apiKey,
        appKey = appKey,
        accessKey = accessKey,
        resourceId = resourceId.ifBlank { DEFAULT_VOLCENGINE_AST_RESOURCE_ID }
    )
}
