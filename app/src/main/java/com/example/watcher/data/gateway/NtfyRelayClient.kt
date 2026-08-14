package com.example.watcher.data.gateway

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSource
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * ntfy-based relay client that replaces the old Gateway HTTP relay.
 * Publishes messages via HTTP POST and subscribes via JSON stream (long-lived GET).
 */
internal class NtfyRelayClient(
    private val scope: CoroutineScope,
    private val onConversationsChanged: ((List<GatewayRelayConversation>) -> Unit)? = null,
    private val onMessagesChanged: ((List<GatewayRelayMessage>) -> Unit)? = null
) {
    private val streamClient = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)  // Watchdog: ntfy sends keepalive every ~30s
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val publishClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow(NtfyConnectionState.Disconnected)
    val connectionState: StateFlow<NtfyConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<GatewayRelayMessage>>(emptyList())
    val messages: StateFlow<List<GatewayRelayMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<GatewayRelayConversation>>(emptyList())
    val conversations: StateFlow<List<GatewayRelayConversation>> = _conversations.asStateFlow()

    private val _debugLog = MutableStateFlow<List<NtfyDebugEntry>>(emptyList())
    val debugLog: StateFlow<List<NtfyDebugEntry>> = _debugLog.asStateFlow()

    private val _debugStats = MutableStateFlow(NtfyDebugStats())
    val debugStats: StateFlow<NtfyDebugStats> = _debugStats.asStateFlow()

    private var subscriptionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var config: NtfyRelayConfig? = null
    private val nextMessageId = java.util.concurrent.atomic.AtomicLong(1L)
    private val publishCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val receiveCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var subscriptionStartedAt = 0L
    private val messageLock = Any()

    fun restoreConversations(saved: List<GatewayRelayConversation>) {
        if (saved.isNotEmpty()) {
            _conversations.value = saved
            Log.d(TAG, "restoreConversations: restored ${saved.size} conversations")
        }
    }

    fun restoreMessages(saved: List<GatewayRelayMessage>) {
        if (saved.isNotEmpty()) {
            _messages.value = saved
            nextMessageId.set((saved.maxOfOrNull { it.id } ?: 0L) + 1)
            Log.d(TAG, "restoreMessages: restored ${saved.size} messages")
        }
    }

    fun configure(newConfig: NtfyRelayConfig) {
        val configChanged = config != newConfig
        config = newConfig
        Log.d(TAG, "configure: changed=$configChanged enabled=${newConfig.enabled} url=${newConfig.serverUrl} topic=${newConfig.topic}")
        if (configChanged) {
            stopSubscription()
            _messages.value = emptyList()
            nextMessageId.set(1L)
            // Note: conversations are NOT cleared — they are persisted across config changes
        }
        if (newConfig.enabled && newConfig.serverUrl.isNotBlank() && subscriptionJob?.isActive != true) {
            startSubscription()
        }
    }

    fun publishPresence(available: Boolean) {
        val cfg = config ?: return
        if (!cfg.enabled || cfg.serverUrl.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val payload = NtfyRelayPayload(
                    type = "presence",
                    author = "phone_user",
                    content = if (available) "Phone is available for handoff" else "Phone is offline",
                    status = if (available) "available" else "offline"
                )
                publish(payload)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish presence: ${e.message}")
            }
        }
    }

    fun createConversation(title: String): GatewayRelayConversation {
        val id = "conv_${System.currentTimeMillis()}"
        val conversation = GatewayRelayConversation(
            id = id,
            agentBridgeId = "ntfy",
            agentBridgeName = "ntfy relay",
            title = title
        )
        val current = _conversations.value.toMutableList()
        current.add(0, conversation)
        _conversations.value = current
        onConversationsChanged?.invoke(_conversations.value)
        return conversation
    }

    fun deleteConversation(conversationId: String) {
        _conversations.value = _conversations.value.filter { it.id != conversationId }
        _messages.value = _messages.value.filter { it.conversationId != conversationId }
        onConversationsChanged?.invoke(_conversations.value)
    }

    fun startSubscription() {
        val cfg = config ?: return
        if (!cfg.enabled || cfg.serverUrl.isBlank()) return
        if (subscriptionJob?.isActive == true) return
        Log.d(TAG, "startSubscription: launching for ${cfg.serverUrl}/${cfg.topic}")

        subscriptionStartedAt = System.currentTimeMillis()
        subscriptionJob = scope.launch(Dispatchers.IO) {
            var retryDelay = 2000L
            while (isActive) {
                try {
                    _connectionState.value = NtfyConnectionState.Connecting
                    updateDebugStats()
                    subscribe(cfg)
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "Subscription error: ${e.message}")
                    _connectionState.value = NtfyConnectionState.Disconnected
                }
                if (!isActive) break
                delay(retryDelay)
                retryDelay = (retryDelay * 1.5).toLong().coerceAtMost(30_000L)
            }
        }
        startHeartbeat()
    }

    fun stopSubscription() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        subscriptionJob?.cancel()
        subscriptionJob = null
        _connectionState.value = NtfyConnectionState.Disconnected
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    val payload = NtfyRelayPayload(
                        type = "presence",
                        author = "phone_user",
                        content = "Phone is available for handoff",
                        status = "available"
                    )
                    publish(payload)
                    _debugStats.value = _debugStats.value.copy(lastHeartbeatAt = System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat publish failed: ${e.message}")
                }
            }
        }
    }

    suspend fun publish(payload: NtfyRelayPayload) {
        val cfg = config ?: throw IllegalStateException("NtfyRelayClient not configured")
        if (payload.conversationId.isBlank()) {
            // Presence and other non-conversation messages: publish without optimistic UI
            publishDirect(cfg, payload)
            return
        }

        // Optimistic: show message immediately with Sending status
        val messageId = addMessage(payload, SendStatus.Sending)
        publishCount.incrementAndGet()
        appendDebugEntry("tx", payload)
        updateDebugStats()

        var lastError: Exception? = null
        for (attempt in 1..2) {
            try {
                Log.d(TAG, "publish: attempt $attempt for conv=${payload.conversationId.take(12)}")
                publishDirect(cfg, payload)
                Log.d(TAG, "publish: SUCCESS for msgId=$messageId")
                updateMessageStatus(messageId, SendStatus.Confirmed)
                lastError = null
                break
            } catch (e: Exception) {
                Log.w(TAG, "publish: attempt $attempt FAILED: ${e.message}")
                lastError = e
                if (attempt < 2) delay(3000)
            }
        }
        if (lastError != null) {
            updateMessageStatus(messageId, SendStatus.Failed)
            throw lastError!!
        }
    }

    private suspend fun publishDirect(cfg: NtfyRelayConfig, payload: NtfyRelayPayload) {
        val url = cfg.serverUrl.trimEnd('/')
        val messageJson = JSONObject().apply {
            put("schema", payload.schema)
            put("type", payload.type)
            put("messageId", payload.messageId)
            put("conversationId", payload.conversationId)
            if (payload.turnId > 0) put("turnId", payload.turnId)
            payload.replyTo?.let { put("replyTo", it) }
            put("author", payload.author)
            put("content", payload.content)
            put("createdAt", payload.createdAt)
            put("ts", payload.ts)
            payload.title?.let { put("title", it) }
            payload.summary?.let { put("summary", it) }
            payload.status?.let { put("status", it) }
        }.toString()

        val ntfyBody = JSONObject().apply {
            put("topic", cfg.topic)
            put("message", messageJson)
            put("title", "relay:${payload.conversationId}")
            if (payload.type == "handoff") put("priority", 4)
        }.toString()

        Log.d(TAG, "HTTP POST → $url topic=${cfg.topic} msgId=${payload.messageId} turn=${payload.turnId} content=${payload.content.take(30)}")
        val startMs = System.currentTimeMillis()

        val request = Request.Builder()
            .url(url)
            .post(ntfyBody.toRequestBody("application/json".toMediaType()))
            .apply { cfg.authToken?.let { header("Authorization", "Bearer $it") } }
            .build()

        withContext(Dispatchers.IO) {
            try {
                publishClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - startMs
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP POST ← ${response.code} ${response.message} (${elapsed}ms)")
                        throw RuntimeException("ntfy publish failed: ${response.code} ${response.message}")
                    }
                    Log.d(TAG, "HTTP POST ← 200 OK (${elapsed}ms)")
                }
            } catch (e: java.net.SocketTimeoutException) {
                val elapsed = System.currentTimeMillis() - startMs
                Log.w(TAG, "HTTP POST ← TIMEOUT (${elapsed}ms) connectTimeout=${publishClient.connectTimeoutMillis}ms readTimeout=${publishClient.readTimeoutMillis}ms")
                throw e
            } catch (e: java.io.IOException) {
                val elapsed = System.currentTimeMillis() - startMs
                Log.w(TAG, "HTTP POST ← IO ERROR (${elapsed}ms): ${e.message}")
                throw e
            }
        }
    }

    suspend fun pollHistory(since: String = "1h"): List<NtfyRelayPayload> {
        val cfg = config ?: return emptyList()
        val url = "${cfg.serverUrl.trimEnd('/')}/${cfg.topic}/json?poll=1&since=$since"
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { cfg.authToken?.let { header("Authorization", "Bearer $it") } }
            .build()

        return withContext(Dispatchers.IO) {
            publishClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val text = response.body?.string() ?: return@withContext emptyList()
                text.lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { parseNtfyLine(it) }
            }
        }
    }

    fun release() {
        stopSubscription()
        streamClient.dispatcher.executorService.shutdown()
        publishClient.dispatcher.executorService.shutdown()
    }

    // ── Private ─────────────────────────────────────────────────────

    private fun subscribe(cfg: NtfyRelayConfig) {
        val url = "${cfg.serverUrl.trimEnd('/')}/${cfg.topic}/json?since=30m"
        Log.d(TAG, "subscribe: connecting to $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { cfg.authToken?.let { header("Authorization", "Bearer $it") } }
            .build()

        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("ntfy subscribe failed: ${response.code}")
            }
            _connectionState.value = NtfyConnectionState.Connected
            Log.d(TAG, "subscribe: connected, reading stream")
            val source: BufferedSource = response.body?.source() ?: return
            while (!source.exhausted()) {
                val line = try {
                    source.readUtf8Line()
                } catch (e: SocketTimeoutException) {
                    // Watchdog: 90s no data — ntfy keepalive is ~30s, so connection is likely dead
                    Log.w(TAG, "subscribe: read timeout (watchdog), reconnecting")
                    throw e
                } ?: break
                if (line.isBlank()) continue
                Log.d(TAG, "subscribe: rx line len=${line.length} ${line.take(80)}")
                val payload = parseNtfyLine(line)
                if (payload != null) {
                    Log.d(TAG, "subscribe: parsed type=${payload.type} author=${payload.author} conv=${payload.conversationId.take(12)}")
                    addMessage(payload)
                    receiveCount.incrementAndGet()
                    appendDebugEntry("rx", payload)
                    updateDebugStats()
                }
            }
        }
        Log.d(TAG, "subscribe: stream ended")
        _connectionState.value = NtfyConnectionState.Disconnected
    }

    private fun parseNtfyLine(line: String): NtfyRelayPayload? {
        return try {
            val json = JSONObject(line)
            val event = json.optString("event", "")
            if (event != "message") return null
            val messageBody = json.optString("message", "")
            if (messageBody.isBlank()) return null
            val payload = JSONObject(messageBody)
            NtfyRelayPayload(
                schema = payload.optString("schema", ""),
                type = payload.optString("type", "message"),
                messageId = payload.optString("messageId", "ntfy_${json.optString("id", "")}"),
                conversationId = payload.optString("conversationId", ""),
                turnId = payload.optInt("turnId", 0),
                replyTo = payload.optString("replyTo", "").ifBlank { null },
                author = payload.optString("author", "unknown"),
                content = payload.optString("content", ""),
                createdAt = payload.optString("createdAt", ""),
                ts = payload.optLong("ts", json.optLong("time", 0) * 1000),
                title = payload.optString("title", "").ifBlank { null },
                summary = payload.optString("summary", "").ifBlank { null },
                status = payload.optString("status", "").ifBlank { null }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ntfy message: ${e.message}")
            null
        }
    }

    private fun addMessage(payload: NtfyRelayPayload, status: SendStatus = SendStatus.Confirmed): Long {
        if (payload.conversationId.isBlank()) return -1
        Log.d(TAG, "addMessage: type=${payload.type} conv=${payload.conversationId.take(12)} author=${payload.author} status=$status")

        ensureConversation(payload)

        val id = nextMessageId.getAndIncrement()
        val message = GatewayRelayMessage(
            id = id,
            conversationId = payload.conversationId,
            author = payload.author,
            content = payload.content,
            createdAt = payload.ts,
            sendStatus = status
        )
        var changed = false
        synchronized(messageLock) {
            val current = _messages.value.toMutableList()
            val pendingIndex = current.indexOfFirst {
                it.author == message.author && it.content == message.content
                    && it.sendStatus != SendStatus.Confirmed && status == SendStatus.Confirmed
            }
            if (pendingIndex >= 0) {
                current[pendingIndex] = current[pendingIndex].copy(sendStatus = SendStatus.Confirmed)
                _messages.value = current
                changed = true
            } else {
                val isDuplicate = current.any {
                    it.author == message.author && it.content == message.content && it.createdAt == message.createdAt
                }
                if (!isDuplicate) {
                    current.add(message)
                    _messages.value = if (current.size > 200) current.takeLast(200) else current
                    changed = true
                }
            }
        }
        if (changed) onMessagesChanged?.invoke(_messages.value)

        if (payload.type == "handoff") {
            onHandoffReceived?.invoke(payload)
        }
        return id
    }

    private fun updateMessageStatus(messageId: Long, status: SendStatus) {
        synchronized(messageLock) {
            _messages.value = _messages.value.map {
                if (it.id == messageId) it.copy(sendStatus = status) else it
            }
        }
    }

    /** Callback invoked when a handoff message arrives (for local notification). */
    var onHandoffReceived: ((NtfyRelayPayload) -> Unit)? = null

    private fun ensureConversation(payload: NtfyRelayPayload) {
        val conversationId = payload.conversationId
        val existing = _conversations.value.firstOrNull { it.id == conversationId }
        if (existing == null) {
            val title = payload.title ?: conversationId
            val summary = payload.summary ?: ""
            val conversation = GatewayRelayConversation(
                id = conversationId,
                agentBridgeId = "ntfy",
                agentBridgeName = if (payload.author == "pc_agent") "PC Agent" else "Phone",
                title = title,
                summary = summary
            )
            val current = _conversations.value.toMutableList()
            current.add(0, conversation)
            _conversations.value = current
        } else {
            val updated = if (payload.type == "handoff" && payload.title != null) {
                existing.copy(
                    title = payload.title,
                    summary = payload.summary ?: existing.summary,
                    status = "active",
                    updatedAt = System.currentTimeMillis()
                )
            } else if (payload.type == "hand_back") {
                existing.copy(status = "handed_back", updatedAt = System.currentTimeMillis())
            } else {
                existing.copy(updatedAt = System.currentTimeMillis())
            }
            _conversations.value = _conversations.value.map {
                if (it.id == conversationId) updated else it
            }
        }
        onConversationsChanged?.invoke(_conversations.value)
    }

    private fun appendDebugEntry(direction: String, payload: NtfyRelayPayload) {
        val entry = NtfyDebugEntry(
            ts = System.currentTimeMillis(),
            direction = direction,
            type = payload.type,
            author = payload.author,
            conversationId = payload.conversationId,
            contentPreview = payload.content.take(50)
        )
        val current = _debugLog.value.toMutableList()
        current.add(0, entry)
        _debugLog.value = if (current.size > 20) current.take(20) else current
    }

    private fun updateDebugStats() {
        _debugStats.value = NtfyDebugStats(
            connectionState = _connectionState.value,
            lastHeartbeatAt = _debugStats.value.lastHeartbeatAt,
            publishCount = publishCount.get(),
            receiveCount = receiveCount.get(),
            activeConversationCount = _conversations.value.count { it.status != "handed_back" },
            subscriptionStartedAt = subscriptionStartedAt
        )
    }

    companion object {
        private const val TAG = "NtfyRelay"
        private const val HEARTBEAT_INTERVAL_MS = 3 * 60 * 1000L // 3 minutes
    }
}
