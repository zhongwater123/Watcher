package com.example.watcher.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import com.example.watcher.WatcherForegroundService
import com.example.watcher.agentframework.service.AgentFrameworkService
import com.example.watcher.data.gateway.GatewayEvent
import com.example.watcher.data.gateway.GatewayAutomationManager
import com.example.watcher.data.gateway.GatewayPairingRecord
import com.example.watcher.data.gateway.GatewayPairingRequest
import com.example.watcher.data.gateway.GatewayRelayConversation
import com.example.watcher.data.gateway.GatewayRelayMessage
import com.example.watcher.data.gateway.GatewayRuntimeStatus
import com.example.watcher.data.gateway.GatewayServer
import com.example.watcher.data.gateway.GatewayServiceAnnouncer
import com.example.watcher.data.gateway.GatewayStateHolder
import com.example.watcher.data.gateway.GatewayTaskManager
import com.example.watcher.data.gateway.GatewayTaskStatus
import com.example.watcher.data.gateway.NtfyConnectionState
import com.example.watcher.data.gateway.NtfyDebugEntry
import com.example.watcher.data.gateway.NtfyDebugStats
import com.example.watcher.data.gateway.SendStatus
import com.example.watcher.data.gateway.NtfyRelayClient
import com.example.watcher.data.gateway.NtfyRelayConfig
import com.example.watcher.data.gateway.NtfyRelayPayload
import com.example.watcher.data.gateway.validateNtfyRelayServerUrl
import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.TargetTrigger
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.repository.HistoryRepository
import com.example.watcher.data.repository.IntentRepository
import com.example.watcher.data.repository.LiveSpeechRecognizer
import com.example.watcher.data.repository.LiveCommentaryRepository
import com.example.watcher.data.repository.MonitorManager
import com.example.watcher.data.repository.VideoProcessRepository
import com.example.watcher.data.repository.AppRuntimeSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles all Gateway API server lifecycle, tool executor registration, and mDNS discovery.
 * Extracted from IntentViewModel.
 */
internal class GatewayDelegate(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val monitorManager: MonitorManager,
    private val intentRepository: IntentRepository,
    private val historyRepository: HistoryRepository,
    private val videoRepository: VideoProcessRepository,
    private val agentService: AgentFrameworkService,
    private val liveCommentaryRepository: LiveCommentaryRepository,
    private val liveSpeechManager: LiveSpeechRecognizer,
    private val streamUrlProvider: () -> String?,
    private val onStreamRelease: () -> Unit,
    private val onStreamReclaim: () -> Unit
) : GatewayStateHolder.Delegate {
    private val taskManager = GatewayTaskManager()
    private val announcer = GatewayServiceAnnouncer(appContext)
    private val automationManager = GatewayAutomationManager(appContext)
    private var server: GatewayServer? = null
    private val prefs = appContext.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE)
    private val secretStore = AppRuntimeSecretStore(appContext)
    private var automationMonitorOwned = false

    // Stream ownership: phone ↔ remote handoff protocol
    enum class StreamOwner { Phone, Remote }
    @Volatile private var streamOwner = StreamOwner.Phone
    @Volatile private var reclaimRequested = false

    private val _running = MutableStateFlow(false)
    private val _status = MutableStateFlow(
        GatewayRuntimeStatus(
            isRunning = false,
            configuredPort = port,
            localIp = getLocalIpAddress()
        )
    )
    override val running: StateFlow<Boolean> = _running.asStateFlow()
    override val status: StateFlow<GatewayRuntimeStatus> = _status.asStateFlow()
    private val ntfyClient = NtfyRelayClient(scope, onConversationsChanged = ::persistConversations, onMessagesChanged = ::persistMessages)

    override val pairingRequests: StateFlow<List<GatewayPairingRequest>> = automationManager.pairingRequests
    override val pairingBindings: StateFlow<List<GatewayPairingRecord>> = automationManager.bindings
    override val relayConversations: StateFlow<List<GatewayRelayConversation>> = ntfyClient.conversations
    override val relayMessages: StateFlow<List<GatewayRelayMessage>> = ntfyClient.messages
    override val ntfyConnectionState: StateFlow<NtfyConnectionState> = ntfyClient.connectionState
    override val ntfyDebugStats: StateFlow<NtfyDebugStats> = ntfyClient.debugStats
    override val ntfyDebugLog: StateFlow<List<NtfyDebugEntry>> = ntfyClient.debugLog
    private val _relayError = MutableStateFlow<String?>(null)
    override val relayError: StateFlow<String?> = _relayError.asStateFlow()
    private val _phoneAvailable = MutableStateFlow(false)
    override val phoneAvailable: StateFlow<Boolean> = _phoneAvailable.asStateFlow()
    override val apiKey: String get() = readOrCreateApiKey()
    override val port: Int get() = prefs.getInt("port", GatewayServer.DEFAULT_PORT)

    init {
        automationManager.onRulesChanged = ::reconcileAutomationMonitoring
        scope.launch {
            monitorManager.monitorStatus.collect { status ->
                automationManager.onMonitorStatusChanged(status)
            }
        }
        reconcileAutomationMonitoring()
        loadAndApplyNtfyConfig()
    }

    override fun toggle(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    override fun approvePairingRequest(requestId: String) {
        automationManager.approvePairingRequest(requestId)
    }

    override fun rejectPairingRequest(requestId: String) {
        automationManager.rejectPairingRequest(requestId)
    }

    override fun createLocalRelayConversation(agentBridgeId: String, title: String) {
        ntfyClient.createConversation(title)
    }

    override fun sendPhoneRelayMessage(conversationId: String, content: String) {
        scope.launch {
            try {
                ntfyClient.publish(
                    NtfyRelayPayload(
                        author = "phone_user",
                        content = content,
                        conversationId = conversationId,
                        turnId = nextTurnId(conversationId)
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w("GatewayDelegate", "ntfy publish failed: ${e.message}")
            }
        }
    }

    private fun nextTurnId(conversationId: String): Int {
        val key = "turn_$conversationId"
        val current = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, current).apply()
        return current
    }

    override fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return try {
            @Suppress("DEPRECATION")
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
            else "0.0.0.0"
        } catch (_: Exception) { "0.0.0.0" }
    }

    override fun getNtfyConfig(): NtfyRelayConfig {
        var topic = prefs.getString("ntfy_topic", null)
        // Migrate: old topic without watcher- prefix is incompatible with new ACL
        if (topic == null || !topic.startsWith("watcher-")) {
            topic = generateAndStoreTopic()
        }
        return NtfyRelayConfig(
            serverUrl = prefs.getString("ntfy_server_url", "")?.trim().orEmpty(),
            topic = topic,
            authToken = prefs.getString("ntfy_auth_token", null)?.trim()?.takeIf { it.isNotBlank() },
            enabled = prefs.getBoolean("ntfy_enabled", false)
        )
    }

    private fun generateAndStoreTopic(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val topic = "watcher-$hex"
        prefs.edit().putString("ntfy_topic", topic).apply()
        android.util.Log.d("GatewayNtfy", "Generated new device topic: $topic")
        return topic
    }

    override fun setPhoneAvailable(available: Boolean) {
        _phoneAvailable.value = available
        prefs.edit().putBoolean("ntfy_phone_available", available).apply()
        if (available) {
            // Auto-enable: preserve user's server/topic, just ensure enabled=true
            val config = getNtfyConfig()
            val effectiveConfig = if (!config.enabled) {
                config.copy(enabled = true).also { updateNtfyConfig(it) }
            } else {
                config
            }
            applyNtfyConfigIfValid(effectiveConfig, publishPresence = true)
        } else {
            ntfyClient.publishPresence(false)
            ntfyClient.stopSubscription()
            WatcherForegroundService.stop(appContext, WatcherForegroundService.REASON_NTFY_RELAY)
        }
    }

    override fun handBackConversation(conversationId: String, summary: String) {
        scope.launch {
            try {
                ntfyClient.publish(
                    NtfyRelayPayload(
                        type = "hand_back",
                        author = "phone_user",
                        content = summary,
                        conversationId = conversationId,
                        turnId = nextTurnId(conversationId)
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w("GatewayDelegate", "ntfy hand_back failed: ${e.message}")
                _relayError.value = "交回失败，请重试"
            }
        }
    }

    override fun deleteConversation(conversationId: String) {
        ntfyClient.deleteConversation(conversationId)
    }

    override fun clearRelayError() {
        _relayError.value = null
    }

    override fun updateNtfyConfig(config: NtfyRelayConfig) {
        prefs.edit()
            .putString("ntfy_server_url", config.serverUrl)
            .putString("ntfy_topic", config.topic)
            .putString("ntfy_auth_token", config.authToken)
            .putBoolean("ntfy_enabled", config.enabled)
            .apply()
        applyNtfyConfigIfValid(config, publishPresence = false)
    }

    private fun loadAndApplyNtfyConfig() {
        ntfyClient.onHandoffReceived = ::onHandoffNotification
        restoreConversations()
        restoreMessages()
        val available = prefs.getBoolean("ntfy_phone_available", false)
        _phoneAvailable.value = available
        android.util.Log.d("GatewayNtfy", "loadAndApplyNtfyConfig: available=$available")
        if (available) {
            val config = getNtfyConfig()
            val effectiveConfig = if (!config.enabled) config.copy(enabled = true) else config
            android.util.Log.d("GatewayNtfy", "loadAndApplyNtfyConfig: applying ${effectiveConfig.serverUrl}/${effectiveConfig.topic} enabled=${effectiveConfig.enabled}")
            applyNtfyConfigIfValid(effectiveConfig, publishPresence = true)
        }
    }

    private fun applyNtfyConfigIfValid(config: NtfyRelayConfig, publishPresence: Boolean) {
        val validationError = validateNtfyRelayServerUrl(config.serverUrl)
        if (config.enabled && (config.serverUrl.isBlank() || validationError != null)) {
            _relayError.value = validationError ?: "请先配置 HTTPS ntfy 服务器地址"
            ntfyClient.stopSubscription()
            WatcherForegroundService.stop(appContext, WatcherForegroundService.REASON_NTFY_RELAY)
            return
        }
        ntfyClient.configure(config)
        if (publishPresence) {
            ntfyClient.publishPresence(true)
            WatcherForegroundService.start(
                appContext,
                "ntfy 消息通道运行中",
                WatcherForegroundService.REASON_NTFY_RELAY
            )
        }
    }

    private fun persistConversations(conversations: List<GatewayRelayConversation>) {
        val json = org.json.JSONArray().apply {
            conversations.take(50).forEach { conv ->
                put(org.json.JSONObject().apply {
                    put("id", conv.id)
                    put("title", conv.title)
                    put("summary", conv.summary)
                    put("status", conv.status)
                    put("createdAt", conv.createdAt)
                    put("updatedAt", conv.updatedAt)
                })
            }
        }
        prefs.edit().putString("ntfy_conversations", json.toString()).apply()
    }

    private fun restoreConversations() {
        val json = prefs.getString("ntfy_conversations", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            val list = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                GatewayRelayConversation(
                    id = obj.getString("id"),
                    agentBridgeId = "ntfy",
                    agentBridgeName = "PC Agent",
                    title = obj.optString("title", ""),
                    summary = obj.optString("summary", ""),
                    status = obj.optString("status", "active"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                )
            }
            ntfyClient.restoreConversations(list)
        } catch (e: Exception) {
            android.util.Log.w("GatewayNtfy", "Failed to restore conversations: ${e.message}")
        }
    }

    private fun persistMessages(messages: List<GatewayRelayMessage>) {
        val json = org.json.JSONArray().apply {
            messages.takeLast(200).forEach { msg ->
                put(org.json.JSONObject().apply {
                    put("id", msg.id)
                    put("conversationId", msg.conversationId)
                    put("author", msg.author)
                    put("content", msg.content)
                    put("createdAt", msg.createdAt)
                    put("sendStatus", msg.sendStatus.name)
                })
            }
        }
        prefs.edit().putString("ntfy_messages", json.toString()).apply()
    }

    private fun restoreMessages() {
        val json = prefs.getString("ntfy_messages", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            val list = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                GatewayRelayMessage(
                    id = obj.getLong("id"),
                    conversationId = obj.getString("conversationId"),
                    author = obj.optString("author", ""),
                    content = obj.optString("content", ""),
                    createdAt = obj.optLong("createdAt", 0L),
                    sendStatus = try { SendStatus.valueOf(obj.optString("sendStatus", "Confirmed")) } catch (_: Exception) { SendStatus.Confirmed }
                )
            }
            ntfyClient.restoreMessages(list)
        } catch (e: Exception) {
            android.util.Log.w("GatewayNtfy", "Failed to restore messages: ${e.message}")
        }
    }

    private fun onHandoffNotification(payload: NtfyRelayPayload) {
        android.util.Log.d("GatewayNtfy", "onHandoffNotification: conv=${payload.conversationId} title=${payload.title}")
        try {
            val channelId = "relay_handoff"
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId, "会话接续", android.app.NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val intent = android.content.Intent(appContext, com.example.watcher.MultiDeviceActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversationId", payload.conversationId)
            }
            val pending = android.app.PendingIntent.getActivity(
                appContext, payload.conversationId.hashCode(), intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val bigText = buildString {
                payload.summary?.let { appendLine(it) }
                if (payload.content.isNotBlank() && payload.content != payload.summary) {
                    appendLine()
                    append(payload.content)
                }
            }.ifBlank { "点击查看对话上下文" }

            val notification = androidx.core.app.NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(payload.title ?: "PC Agent 需要你接续")
                .setContentText(payload.content.ifBlank { payload.summary ?: "点击查看对话上下文" })
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(bigText))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            nm.notify(payload.conversationId.hashCode(), notification)
        } catch (e: Exception) {
            android.util.Log.w("GatewayDelegate", "Failed to show handoff notification: ${e.message}")
        }
    }

    fun release() {
        stop()
        ntfyClient.release()
        taskManager.release()
    }

    // ── Server lifecycle ─────────────────────────────────────────

    private fun start() {
        if (server != null) return
        updateStatus { current ->
            current.copy(
                configuredPort = port,
                localIp = getLocalIpAddress(),
                lastError = null
            )
        }
        scope.launch(Dispatchers.IO) {
            registerExecutors()
            val ports = listOf(port, 8081, 8090, 9090)
            for (p in ports) {
                val s = GatewayServer(
                    port = p,
                    apiKey = apiKey,
                    localIpProvider = ::getLocalIpAddress,
                    frameProvider = { monitorManager.currentFrame.value },
                    taskManager = taskManager,
                    automationManager = automationManager,
                    gatewayStatusProvider = ::gatewayStatusMap,
                    onRequestObserved = ::recordRequest,
                    agentService = agentService,
                    commentaryStateProvider = {
                        val commentary = liveCommentaryRepository.commentaryState.value
                        val speech = liveSpeechManager.state.value
                        mapOf(
                            "isActive" to commentary.isActive,
                            "entries" to commentary.entries,
                            "recordedSegmentCount" to commentary.recordedSegmentCount,
                            "analyzedSegmentCount" to commentary.analyzedSegmentCount,
                            "memoryA" to commentary.memoryA,
                            "latestMemoryB" to commentary.latestMemoryB,
                            "scenePhase" to commentary.scenePhase,
                            "sceneMemory" to commentary.sceneMemory,
                            "entityMemory" to commentary.entityMemory,
                            "actionSummary" to commentary.actionSummary,
                            "pendingAsks" to commentary.pendingAsks,
                            "expertRequests" to commentary.expertRequests,
                            "speech" to mapOf(
                                "isActive" to speech.isActive,
                                "isListening" to speech.isListening,
                                "transcripts" to speech.transcripts
                            )
                        )
                    },
                    commentaryEntriesProvider = { since ->
                        val entries = liveCommentaryRepository.commentaryState.value.entries
                        if (since > 0) entries.filter { it.wallClockStartTime > since } else entries
                    },
                    onCommentaryAsk = { requests ->
                        liveCommentaryRepository.sceneMemoryManager.appendExpertRequests(requests)
                    },
                    streamStatusProvider = {
                        mapOf(
                            "owner" to streamOwner.name.lowercase(),
                            "reclaimRequested" to reclaimRequested,
                            "streamUrl" to streamUrlProvider()
                        )
                    },
                    onStreamHandoff = {
                        val url = streamUrlProvider()
                        if (!url.isNullOrBlank() && streamOwner == StreamOwner.Phone) {
                            onStreamRelease()
                            streamOwner = StreamOwner.Remote
                            reclaimRequested = false
                        }
                        url
                    },
                    onStreamReclaim = {
                        if (streamOwner == StreamOwner.Remote) {
                            reclaimRequested = true
                        } else {
                            // Already phone-owned, just reconnect
                            onStreamReclaim()
                        }
                    },
                    onStreamRelease = {
                        // Remote client confirms disconnect → phone reconnects
                        streamOwner = StreamOwner.Phone
                        reclaimRequested = false
                        onStreamReclaim()
                    }
                )
                try {
                    s.start()
                    server = s
                    val identity = automationManager.deviceIdentity()
                    announcer.register(
                        p,
                        attributes = mapOf(
                            "deviceId" to identity.deviceId,
                            "serviceVersion" to identity.serviceVersion,
                            "cap" to identity.capabilities.joinToString(",")
                        )
                    )
                    _running.value = true
                    updateStatus { current ->
                        current.copy(
                            isRunning = true,
                            configuredPort = port,
                            listeningPort = p,
                            localIp = getLocalIpAddress(),
                            startedAt = System.currentTimeMillis(),
                            lastError = null
                        )
                    }
                    android.util.Log.d("Gateway", "Started on port $p")
                    return@launch
                } catch (e: Exception) {
                    updateStatus { current ->
                        current.copy(
                            isRunning = false,
                            listeningPort = null,
                            localIp = getLocalIpAddress(),
                            lastError = "Port $p unavailable: ${e.message}"
                        )
                    }
                    android.util.Log.w("Gateway", "Port $p unavailable: ${e.message}")
                    try { s.stop() } catch (_: Exception) {}
                }
            }
            updateStatus { current ->
                current.copy(
                    isRunning = false,
                    listeningPort = null,
                    localIp = getLocalIpAddress(),
                    lastError = "Failed to start on any configured port."
                )
            }
            android.util.Log.e("Gateway", "Failed to start on any port")
        }
    }

    private fun stop() {
        announcer.unregister()
        server?.stop()
        server = null
        _running.value = false
        updateStatus { current ->
            current.copy(
                isRunning = false,
                listeningPort = null,
                localIp = getLocalIpAddress()
            )
        }
    }

    private fun readOrCreateApiKey(): String {
        val storedSecret = secretStore.getGatewayApiKey().trim()
        if (storedSecret.isNotBlank()) {
            migrateLegacyGatewayApiKeyIfNeeded()
            return storedSecret
        }

        val legacySecret = prefs.getString("api_key", null)?.trim().orEmpty()
        if (legacySecret.isNotBlank()) {
            secretStore.putGatewayApiKey(legacySecret)
            prefs.edit().remove("api_key").apply()
            return legacySecret
        }

        return generateApiKey()
    }

    private fun migrateLegacyGatewayApiKeyIfNeeded() {
        if (prefs.contains("api_key")) {
            prefs.edit().remove("api_key").apply()
        }
    }

    private fun generateApiKey(): String {
        val key = UUID.randomUUID().toString().replace("-", "").take(24)
        secretStore.putGatewayApiKey(key)
        return key
    }

    // ── Tool executors ───────────────────────────────────────────

    private fun registerExecutors() {
        // ── snapshot ──
        taskManager.registerExecutor("snapshot") { _, onEvent ->
            val bitmap = monitorManager.currentFrame.value
                ?: throw IllegalStateException("No frame available")
            onEvent(GatewayEvent("snapshot_captured", null))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            mapOf("format" to "jpeg", "size" to out.size(), "message" to "Snapshot captured. Use GET /api/stream/snapshot for the image.")
        }

        // ── monitor ──
        taskManager.registerExecutor("monitor") { params, onEvent ->
            val objective = params["objective"] as? String
                ?: throw IllegalArgumentException("Missing required param: objective")
            val interval = (params["checkIntervalSeconds"] as? Number)?.toInt() ?: 30
            val trigger = params["triggerCondition"] as? String ?: ""

            onEvent(GatewayEvent("creating_task", mapOf("objective" to objective, "interval" to interval)))

            val task = IntentResult(
                title = objective.take(48),
                userInput = objective,
                userRequirement = objective,
                originalSceneDescription = "Gateway 远程创建的监控任务。$trigger".trim(),
                checkInterval = interval.coerceIn(2, 300),
                promptTemplate = buildMonitorPrompt(objective, trigger),
                monitorMode = MonitorMode.SceneBaseline,
                targetTrigger = TargetTrigger.OnAppear,
                baselineSource = BaselineSource.CapturedFrame
            ).normalized()

            val saved = intentRepository.saveTask(task)
            val taskId = saved.taskId ?: throw IllegalStateException("Failed to save monitor task")
            onEvent(GatewayEvent("task_saved", mapOf("taskId" to taskId)))

            monitorManager.stopMonitoring()
            val runId = historyRepository.startMonitorRun(saved)
            onEvent(GatewayEvent("monitoring_started", mapOf("runId" to runId, "checkInterval" to interval)))
            monitorManager.startMonitoring(saved, runId)

            // Register graceful cancel
            val gwTaskId = taskManager.listTasks().firstOrNull {
                it.status == GatewayTaskStatus.Running && it.tool == "monitor"
            }?.id
            if (gwTaskId != null) {
                taskManager.onTaskCancel(gwTaskId) { monitorManager.stopMonitoring() }
            }

            // Collect status updates until monitoring stops
            var lastCheckCount = 0
            while (true) {
                delay(2000)
                val status = monitorManager.monitorStatus.value
                if (!status.isRunning) break
                if (status.totalCheckCount > lastCheckCount) {
                    lastCheckCount = status.totalCheckCount
                    onEvent(GatewayEvent("check_result", mapOf(
                        "result" to status.lastResult.name,
                        "summary" to status.lastSummary,
                        "reason" to status.lastReason,
                        "remark" to status.lastRemark,
                        "confidence" to status.lastConfidence,
                        "totalChecks" to status.totalCheckCount,
                        "alerts" to status.alertCount,
                        "warnings" to status.warningCount,
                        "normals" to status.normalCount
                    )))
                }
            }

            val finalStatus = monitorManager.monitorStatus.value
            mapOf(
                "taskId" to taskId,
                "totalChecks" to finalStatus.totalCheckCount,
                "alerts" to finalStatus.alertCount,
                "warnings" to finalStatus.warningCount,
                "normals" to finalStatus.normalCount,
                "lastResult" to finalStatus.lastResult.name,
                "lastSummary" to finalStatus.lastSummary,
                "lastRemark" to finalStatus.lastRemark
            )
        }

        // ── video_analysis ──
        taskManager.registerExecutor("video_analysis") { params, onEvent ->
            val taskDesc = params["task"] as? String
                ?: throw IllegalArgumentException("Missing required param: task")
            val duration = (params["durationSeconds"] as? Number)?.toInt() ?: 60
            val segment = (params["segmentSeconds"] as? Number)?.toInt() ?: 10

            onEvent(GatewayEvent("creating_task", mapOf("task" to taskDesc, "duration" to duration, "segment" to segment)))

            val draft = VideoProcessTaskDraft(
                userRequirement = taskDesc,
                plannedDurationSeconds = duration.coerceIn(5, 600),
                plannedSegmentDurationSeconds = segment.coerceIn(2, 300),
                captureIntervalSeconds = segment.coerceIn(2, 300),
                plannedSamplingFps = 2,
                autoStartStreamingOutput = false,
                finalSummaryEnabled = true
            ).normalized()

            onEvent(GatewayEvent("recording_started", mapOf(
                "duration" to draft.plannedDurationSeconds,
                "segments" to draft.plannedSegmentCount,
                "segmentDuration" to draft.plannedSegmentDurationSeconds
            )))

            val stopFlag = AtomicBoolean(false)
            val gwTaskId = taskManager.listTasks().firstOrNull {
                it.status == GatewayTaskStatus.Running && it.tool == "video_analysis"
            }?.id
            if (gwTaskId != null) {
                taskManager.onTaskCancel(gwTaskId) { stopFlag.set(true) }
            }

            val result = videoRepository.executeTask(
                draft = draft,
                streamingOutputEnabled = false,
                latestFrameProvider = { monitorManager.currentFrame.value },
                outputRoot = appContext.filesDir,
                shouldStopRequested = { stopFlag.get() },
                onStatus = { update ->
                    val eventType = when (update.stage) {
                        VideoRunStatus.Recording -> "recording"
                        VideoRunStatus.Uploading -> "uploading"
                        VideoRunStatus.Preprocessing -> "preprocessing"
                        VideoRunStatus.Analyzing -> "analyzing"
                        VideoRunStatus.Summarizing -> "summarizing"
                        VideoRunStatus.Completed -> "completed"
                        VideoRunStatus.CompletedDegraded -> "completed_degraded"
                        VideoRunStatus.Failed -> "failed"
                        VideoRunStatus.Cancelled -> "cancelled"
                        else -> "progress"
                    }
                    onEvent(GatewayEvent(eventType, mapOf(
                        "message" to update.message,
                        "recordedSegments" to update.recordedSegmentCount,
                        "analyzedSegments" to update.analyzedSegmentCount,
                        "runId" to update.runId
                    )))
                }
            )

            mapOf(
                "runId" to result.run.id,
                "status" to result.run.status.name,
                "summary" to result.finalResult.summary,
                "conclusion" to result.finalResult.conclusion,
                "segmentCount" to result.run.segmentCount,
                "durationSeconds" to result.run.totalDurationSeconds,
                "timelineEvents" to result.finalResult.timelineEvents.map { event ->
                    mapOf(
                        "timestampSeconds" to event.timestampSeconds,
                        "title" to event.title,
                        "detail" to event.detail,
                        "confidence" to event.confidence
                    )
                }
            )
        }
    }

    private fun reconcileAutomationMonitoring() {
        val needsDeskAbsenceMonitoring = automationManager.hasEnabledDeskAbsenceAutomation()
        val currentlyRunning = monitorManager.monitorStatus.value.isRunning
        if (needsDeskAbsenceMonitoring && !currentlyRunning) {
            monitorManager.startMonitoring(buildDeskAbsenceAutomationTask())
            automationMonitorOwned = true
            return
        }
        if (!needsDeskAbsenceMonitoring && automationMonitorOwned) {
            monitorManager.stopMonitoring()
            automationMonitorOwned = false
        }
    }

    private fun buildDeskAbsenceAutomationTask(): IntentResult {
        return IntentResult(
            title = "Desk absence automation",
            userInput = "判断用户是否已经离开工位",
            userRequirement = "当用户连续离开工位时触发桌面自动化",
            originalSceneDescription = "Gateway automation rule for desk absence detection.",
            checkInterval = 20,
            promptTemplate = buildDeskAbsencePrompt(),
            monitorMode = MonitorMode.SceneBaseline,
            targetTrigger = TargetTrigger.OnAppear,
            baselineSource = BaselineSource.CapturedFrame
        ).normalized()
    }

    private fun buildDeskAbsencePrompt(): String = buildString {
        appendLine("你是一个工位在席检测分析员。")
        appendLine("请判断当前摄像头画面中的用户是否已经离开工位。")
        appendLine()
        appendLine("输出 JSON：")
        appendLine("{\"status\":\"ALERT|WARNING|NORMAL\",\"summary\":\"一句话结论\",\"reason\":\"判断依据\",\"confidence\":0.0}")
        appendLine()
        appendLine("规则：")
        appendLine("- ALERT：用户明显已经离开工位，座位为空或工位前无人。")
        appendLine("- WARNING：用户可能离开，但证据不充分。")
        appendLine("- NORMAL：用户仍在工位或明显处于画面内。")
    }

    private fun recordRequest(method: String, path: String) {
        updateStatus { current ->
            current.copy(
                lastRequestAt = System.currentTimeMillis(),
                lastRequestMethod = method,
                lastRequestPath = path,
                localIp = getLocalIpAddress()
            )
        }
    }

    private fun gatewayStatusMap(): Map<String, Any?> {
        val status = _status.value
        return mapOf(
            "running" to status.isRunning,
            "configuredPort" to status.configuredPort,
            "listeningPort" to status.listeningPort,
            "localIp" to status.localIp,
            "startedAt" to status.startedAt,
            "lastRequestAt" to status.lastRequestAt,
            "lastRequestMethod" to status.lastRequestMethod,
            "lastRequestPath" to status.lastRequestPath,
            "lastError" to status.lastError
        )
    }

    private fun updateStatus(transform: (GatewayRuntimeStatus) -> GatewayRuntimeStatus) {
        _status.value = transform(_status.value)
    }

    private fun buildMonitorPrompt(objective: String, trigger: String): String = buildString {
        appendLine("你是一个视觉监控分析员。你需要分析当前画面，判断是否满足用户的监控条件。")
        appendLine()
        appendLine("监控目标：$objective")
        if (trigger.isNotBlank()) appendLine("触发条件：$trigger")
        appendLine()
        appendLine("请分析当前画面并与基线对比。返回 JSON：")
        appendLine("{\"status\": \"ALERT|WARNING|NORMAL\", \"summary\": \"一句话概括\", \"reason\": \"判断依据\", \"confidence\": 0.0-1.0}")
        appendLine()
        appendLine("- ALERT：检测到明确满足监控条件的情况，需要立即通知")
        appendLine("- WARNING：发现可疑变化，但尚未完全确认")
        appendLine("- NORMAL：未检测到异常")
    }
}
