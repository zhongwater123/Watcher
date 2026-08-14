package com.example.watcher

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.watcher.agentframework.integration.AppAgentBrainConnectionTester
import com.example.watcher.agentframework.integration.AppDefaultAgentBrainFactory
import com.example.watcher.agentframework.integration.APP_DEFAULT_LLM_BRAIN_FACTORY_ID
import com.example.watcher.agentframework.service.AgentBrainCatalog
import com.example.watcher.agentframework.service.AgentBrainConnectionTester
import com.example.watcher.agentframework.service.AgentFrameworkService
import com.example.watcher.agentframework.service.StaticAgentBrainCatalog
import com.example.watcher.data.gateway.GatewayStateHolder
import com.example.watcher.data.local.AppDatabase
import com.example.watcher.data.local.litert.CompositeAgentBrainConnectionTester
import com.example.watcher.data.local.litert.LITERT_BRAIN_FACTORY_ID
import com.example.watcher.data.local.litert.LiteRtAgentBrainFactory
import com.example.watcher.data.local.litert.LiteRtAssetInstaller
import com.example.watcher.data.local.litert.LiteRtConfigStore
import com.example.watcher.data.local.litert.LiteRtModelDownloader
import com.example.watcher.data.local.litert.LiteRtModelLocator
import com.example.watcher.data.local.litert.LiteRtConnectionTester
import com.example.watcher.data.local.litert.LiteRtEngineManager
import com.example.watcher.data.local.litert.LiteRtLlmProvider
import com.example.watcher.data.repository.LlmWalletRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WatcherApplication : Application() {
    val llmWalletRepository: LlmWalletRepository by lazy {
        LlmWalletRepository(this, AppDatabase.getDatabase(this).llmProviderDao())
    }

    val agentFrameworkContainer: AgentFrameworkContainer by lazy {
        AgentFrameworkContainer(this, llmWalletRepository)
    }

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, t ->
            Log.e("WatcherApplication", "Application init failed", t)
        }
    )

    override fun onCreate() {
        super.onCreate()
    }

    /** Called after startup video finishes (or immediately if no video). */
    fun initializeLiteRt() {
        appScope.launch {
            val container = agentFrameworkContainer
            container.liteRtAssetInstaller.installBundledModelIfNeeded()
            val savedConfig = container.liteRtConfigStore.loadConfig()
            val config = container.liteRtModelLocator.resolveConfig(savedConfig)

            if (config != null && config != savedConfig) {
                container.liteRtConfigStore.saveConfig(config)
            }

            if (config != null) {
                runCatching {
                    container.liteRtEngineManager.initialize(config)
                }.onFailure { e ->
                    Log.w("WatcherApplication", "LiteRT auto-init failed: ${e.message}")
                }
            } else if (savedConfig != null) {
                Log.i("WatcherApplication", "Saved LiteRT model path is unavailable; waiting for user action")
            }
        }
    }
}

class AgentFrameworkContainer(
    application: Application,
    val llmWalletRepository: LlmWalletRepository
) {
    private val defaultBrainFactory: AppDefaultAgentBrainFactory by lazy {
        AppDefaultAgentBrainFactory(llmWalletRepository)
    }

    // LiteRT-LM on-device inference
    val liteRtModelLocator: LiteRtModelLocator by lazy { LiteRtModelLocator(application) }
    val liteRtAssetInstaller: LiteRtAssetInstaller by lazy { LiteRtAssetInstaller(application) }
    val liteRtModelDownloader: LiteRtModelDownloader by lazy { LiteRtModelDownloader(application) }
    val liteRtConfigStore: LiteRtConfigStore by lazy { LiteRtConfigStore(application) }
    val liteRtEngineManager: LiteRtEngineManager by lazy { LiteRtEngineManager(application) }
    val liteRtProvider: LiteRtLlmProvider by lazy { LiteRtLlmProvider(liteRtEngineManager, application) }
    private val liteRtBrainFactory: LiteRtAgentBrainFactory by lazy {
        LiteRtAgentBrainFactory(liteRtProvider)
    }

    val brainCatalog: AgentBrainCatalog by lazy {
        StaticAgentBrainCatalog(
            defaultFactoryId = APP_DEFAULT_LLM_BRAIN_FACTORY_ID,
            registeredFactories = listOf(defaultBrainFactory, liteRtBrainFactory)
        )
    }

    val brainConnectionTester: AgentBrainConnectionTester by lazy {
        CompositeAgentBrainConnectionTester(
            testers = mapOf(
                APP_DEFAULT_LLM_BRAIN_FACTORY_ID to AppAgentBrainConnectionTester(defaultBrainFactory),
                LITERT_BRAIN_FACTORY_ID to LiteRtConnectionTester(liteRtProvider, liteRtEngineManager)
            )
        )
    }

    val gatewayStateHolder: GatewayStateHolder by lazy { GatewayStateHolder() }

    val service: AgentFrameworkService by lazy {
        AgentFrameworkService.builder()
            .persistentStorage(application.filesDir)
            .addBrainCatalog(brainCatalog)
            .build()
    }
}

fun Context.watcherApplication(): WatcherApplication {
    return applicationContext as WatcherApplication
}
