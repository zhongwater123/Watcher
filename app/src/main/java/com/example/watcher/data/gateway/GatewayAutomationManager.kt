package com.example.watcher.data.gateway

import android.content.Context
import android.os.Build
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.MonitorStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private const val AUTOMATION_PREFS = "gateway_automation_prefs"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_BINDINGS = "bindings"
private const val KEY_PAIRING_REQUESTS = "pairing_requests"
private const val KEY_RELAY_CONVERSATIONS = "relay_conversations"
private const val KEY_RELAY_MESSAGES = "relay_messages"
private const val KEY_NEXT_RELAY_MESSAGE_ID = "next_relay_message_id"
private const val KEY_AUTOMATIONS = "automations"
private const val KEY_AUTOMATION_EVENTS = "automation_events"
private const val KEY_NEXT_AUTOMATION_EVENT_ID = "next_automation_event_id"

internal class GatewayAutomationManager(
    context: Context
) {
    private val prefs = context.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    var onRulesChanged: (() -> Unit)? = null
    var onPairingStateChanged: (() -> Unit)? = null

    private val _pairingRequests = MutableStateFlow<List<GatewayPairingRequest>>(emptyList())
    val pairingRequests: StateFlow<List<GatewayPairingRequest>> = _pairingRequests.asStateFlow()

    private val _bindings = MutableStateFlow<List<GatewayPairingRecord>>(emptyList())
    val bindings: StateFlow<List<GatewayPairingRecord>> = _bindings.asStateFlow()

    private val _relayConversations = MutableStateFlow<List<GatewayRelayConversation>>(emptyList())
    val relayConversations: StateFlow<List<GatewayRelayConversation>> = _relayConversations.asStateFlow()

    private val _relayMessages = MutableStateFlow<List<GatewayRelayMessage>>(emptyList())
    val relayMessages: StateFlow<List<GatewayRelayMessage>> = _relayMessages.asStateFlow()

    private val deviceId: String by lazy {
        val existing = prefs.getString(KEY_DEVICE_ID, null).orEmpty()
        if (existing.isNotBlank()) {
            existing
        } else {
            val created = "watcher-${UUID.randomUUID().toString().replace("-", "").take(16)}"
            prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            created
        }
    }

    init {
        refreshPairingState()
        refreshRelayState()
    }

    @Synchronized
    fun deviceIdentity(): GatewayDeviceIdentity {
        return GatewayDeviceIdentity(
            deviceId = deviceId,
            deviceName = buildDeviceName(),
            serviceVersion = "1.3",
            protocolVersion = "2026-06-relay-v1",
            capabilities = listOf("gateway", "automation", "agent", "stream", "commentary", "ntfy_relay")
        )
    }

    @Synchronized
    fun pair(bridgeId: String, bridgeName: String): GatewayPairingResult {
        val now = System.currentTimeMillis()
        val registry = loadPairingRegistry()
        val record = registry.pair(bridgeId, bridgeName, now)
        saveBindings(registry.bindings())
        refreshPairingState(registry)
        return GatewayPairingResult(
            bridgeId = record.bridgeId,
            bridgeName = record.bridgeName,
            bindingToken = record.bindingToken,
            deviceId = deviceId,
            createdAt = record.createdAt
        )
    }

    @Synchronized
    fun createPairingRequest(
        bridgeId: String,
        bridgeName: String,
        sourceHost: String?
    ): GatewayPairingRequest {
        val registry = loadPairingRegistry()
        val request = registry.createRequest(bridgeId, bridgeName, sourceHost)
        savePairingRequests(registry.requests())
        refreshPairingState(registry)
        onPairingStateChanged?.invoke()
        return request
    }

    @Synchronized
    fun getPairingRequest(requestId: String): GatewayPairingRequest? {
        val registry = loadPairingRegistry()
        val request = registry.getRequest(requestId)
        savePairingRequests(registry.requests())
        refreshPairingState(registry)
        return request
    }

    @Synchronized
    fun pendingPairingRequests(): List<GatewayPairingRequest> {
        val registry = loadPairingRegistry()
        val requests = registry.pendingRequests()
        savePairingRequests(registry.requests())
        refreshPairingState(registry)
        return requests
    }

    @Synchronized
    fun listPairingBindings(): List<GatewayPairingRecord> {
        val registry = loadPairingRegistry()
        refreshPairingState(registry)
        return registry.bindings()
    }

    @Synchronized
    fun approvePairingRequest(requestId: String): GatewayPairingRequest? {
        val registry = loadPairingRegistry()
        val request = registry.approveRequest(requestId, deviceId = deviceId)
        savePairingRequests(registry.requests())
        saveBindings(registry.bindings())
        refreshPairingState(registry)
        onPairingStateChanged?.invoke()
        return request
    }

    @Synchronized
    fun rejectPairingRequest(requestId: String): GatewayPairingRequest? {
        val registry = loadPairingRegistry()
        val request = registry.rejectRequest(requestId)
        savePairingRequests(registry.requests())
        refreshPairingState(registry)
        onPairingStateChanged?.invoke()
        return request
    }

    @Synchronized
    fun isValidBindingToken(token: String?): Boolean {
        return loadPairingRegistry().isValidBindingToken(token)
    }

    @Synchronized
    fun bindingForToken(token: String?): GatewayPairingRecord? {
        val normalized = token?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return loadBindings().firstOrNull { it.bindingToken == normalized }
    }

    @Synchronized
    fun createLocalRelayConversation(agentBridgeId: String, title: String): GatewayRelayConversation? {
        val binding = loadBindings().firstOrNull { it.bridgeId == agentBridgeId } ?: return null
        val now = System.currentTimeMillis()
        val conversation = GatewayRelayConversation(
            id = "relay_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            agentBridgeId = binding.bridgeId,
            agentBridgeName = binding.bridgeName,
            title = title.trim().ifBlank { "手机接续会话" },
            summary = "手机端新建的接续会话",
            createdAt = now,
            updatedAt = now
        )
        val conversations = (loadRelayConversations() + conversation)
            .sortedByDescending { it.updatedAt }
            .take(MAX_RELAY_CONVERSATIONS)
        saveRelayConversations(conversations)
        refreshRelayState()
        return conversation
    }

    @Synchronized
    fun registerRelayConversation(
        binding: GatewayPairingRecord,
        payload: Map<String, Any?>
    ): GatewayRelayConversation {
        val now = System.currentTimeMillis()
        val requestedId = payload["conversationId"]?.toString()?.trim().orEmpty()
        val existing = loadRelayConversations().firstOrNull {
            requestedId.isNotBlank() && it.id == requestedId
        }
        require(existing == null || existing.agentBridgeId == binding.bridgeId) {
            "Relay conversation belongs to another Agent."
        }
        val title = payload["title"]?.toString()?.trim().orEmpty()
        val summary = payload["summary"]?.toString()?.trim().orEmpty()
        val status = payload["status"]?.toString()?.trim()?.ifBlank { null } ?: existing?.status ?: "active"
        val conversation = if (existing != null) {
            existing.copy(
                agentBridgeName = binding.bridgeName,
                title = title.ifBlank { existing.title },
                summary = summary.ifBlank { existing.summary },
                status = status,
                updatedAt = now
            )
        } else {
            GatewayRelayConversation(
                id = requestedId.ifBlank { "relay_${UUID.randomUUID().toString().replace("-", "").take(12)}" },
                agentBridgeId = binding.bridgeId,
                agentBridgeName = binding.bridgeName,
                title = title.ifBlank { "PC Agent 会话" },
                summary = summary,
                status = status,
                createdAt = now,
                updatedAt = now
            )
        }
        val conversations = (loadRelayConversations().filterNot { it.id == conversation.id } + conversation)
            .sortedByDescending { it.updatedAt }
            .take(MAX_RELAY_CONVERSATIONS)
        saveRelayConversations(conversations)
        refreshRelayState()
        return conversation
    }

    @Synchronized
    fun listRelayConversations(agentBridgeId: String? = null): List<GatewayRelayConversation> {
        val conversations = loadRelayConversations()
            .filter { agentBridgeId == null || it.agentBridgeId == agentBridgeId }
            .sortedByDescending { it.updatedAt }
        refreshRelayState()
        return conversations
    }

    @Synchronized
    fun listRelayMessages(
        agentBridgeId: String,
        conversationId: String,
        afterMessageId: Long? = null
    ): List<GatewayRelayMessage>? {
        getOwnedRelayConversation(agentBridgeId, conversationId) ?: return null
        val messages = loadRelayMessages()
            .filter { it.conversationId == conversationId }
            .filter { afterMessageId == null || it.id > afterMessageId }
            .sortedBy { it.id }
        refreshRelayState()
        return messages
    }

    @Synchronized
    fun appendRelayMessage(
        agentBridgeId: String,
        conversationId: String,
        author: String,
        content: String
    ): GatewayRelayMessage? {
        val conversation = getOwnedRelayConversation(agentBridgeId, conversationId) ?: return null
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null
        val now = System.currentTimeMillis()
        val message = GatewayRelayMessage(
            id = nextRelayMessageId(),
            conversationId = conversationId,
            author = author,
            content = trimmed,
            createdAt = now
        )
        saveRelayMessages((loadRelayMessages() + message).trimRelayMessages())
        saveRelayConversations(
            loadRelayConversations().map {
                if (it.id == conversation.id) {
                    it.copy(updatedAt = now, lastMessageAt = now)
                } else {
                    it
                }
            }.sortedByDescending { it.updatedAt }.take(MAX_RELAY_CONVERSATIONS)
        )
        refreshRelayState()
        return message
    }

    @Synchronized
    fun markRelayMessagesSeen(
        agentBridgeId: String,
        conversationId: String,
        throughMessageId: Long? = null
    ): Map<String, Any?>? {
        getOwnedRelayConversation(agentBridgeId, conversationId) ?: return null
        val now = System.currentTimeMillis()
        var updatedCount = 0
        val messages = loadRelayMessages().map { message ->
            if (
                message.conversationId == conversationId &&
                message.author == RELAY_AUTHOR_PHONE_USER &&
                message.seenByAgentAt == null &&
                (throughMessageId == null || message.id <= throughMessageId)
            ) {
                updatedCount += 1
                message.copy(seenByAgentAt = now)
            } else {
                message
            }
        }
        saveRelayMessages(messages)
        refreshRelayState()
        return mapOf(
            "conversationId" to conversationId,
            "updatedCount" to updatedCount,
            "seenAt" to now
        )
    }

    @Synchronized
    fun createAutomation(payload: Map<String, Any?>): GatewayAutomationRule {
        val name = payload["name"]?.toString()?.trim().orEmpty()
        require(name.isNotBlank()) { "Automation name is required." }
        val trigger = parseTrigger(payload["trigger"])
        val delivery = parseDelivery(payload["delivery"])
        val actionsPreview = parseActionsPreview(payload["actionsPreview"])
        val rule = GatewayAutomationRule(
            id = "auto_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            name = name,
            trigger = trigger,
            delivery = delivery,
            enabled = payload["enabled"] as? Boolean ?: true,
            actionsPreview = actionsPreview
        )
        saveAutomations(loadAutomations().plus(rule).sortedByDescending { it.createdAt })
        onRulesChanged?.invoke()
        return rule
    }

    @Synchronized
    fun listAutomations(): List<GatewayAutomationRule> = loadAutomations()

    @Synchronized
    fun getAutomation(automationId: String): GatewayAutomationRule? {
        return loadAutomations().firstOrNull { it.id == automationId }
    }

    @Synchronized
    fun updateAutomation(automationId: String, payload: Map<String, Any?>): GatewayAutomationRule? {
        val current = loadAutomations().firstOrNull { it.id == automationId } ?: return null
        val updated = current.copy(
            name = payload["name"]?.toString()?.trim()?.ifBlank { current.name } ?: current.name,
            trigger = payload["trigger"]?.let(::parseTrigger) ?: current.trigger,
            delivery = payload["delivery"]?.let(::parseDelivery) ?: current.delivery,
            enabled = payload["enabled"] as? Boolean ?: current.enabled,
            actionsPreview = payload["actionsPreview"]?.let(::parseActionsPreview) ?: current.actionsPreview,
            updatedAt = System.currentTimeMillis()
        )
        saveAutomations(loadAutomations().map { if (it.id == automationId) updated else it })
        onRulesChanged?.invoke()
        return updated
    }

    @Synchronized
    fun listAutomationEvents(
        automationId: String,
        since: Long? = null,
        afterEventId: Long? = null
    ): List<GatewayAutomationEvent>? {
        getAutomation(automationId) ?: return null
        return loadEvents()
            .filter { it.automationId == automationId }
            .filter { event ->
                val matchesSince = since?.let { event.createdAt > it } ?: true
                val matchesAfterId = afterEventId?.let { event.id > it } ?: true
                matchesSince && matchesAfterId
            }
    }

    @Synchronized
    fun acknowledgeAutomationEvent(
        automationId: String,
        eventId: Long,
        status: String,
        message: String?
    ): GatewayAutomationEvent? {
        getAutomation(automationId) ?: return null
        var updatedEvent: GatewayAutomationEvent? = null
        val events = loadEvents().map { event ->
            if (event.automationId == automationId && event.id == eventId) {
                val next = event.copy(
                    acknowledgedAt = System.currentTimeMillis(),
                    acknowledgementStatus = status.trim().ifBlank { "received" },
                    acknowledgementMessage = message?.trim()?.ifBlank { null }
                )
                updatedEvent = next
                next
            } else {
                event
            }
        }
        if (updatedEvent != null) {
            saveEvents(events)
            saveAutomations(
                loadAutomations().map { rule ->
                    if (rule.id == automationId) {
                        rule.copy(
                            lastAcknowledgedAt = updatedEvent!!.acknowledgedAt,
                            lastAcknowledgedStatus = updatedEvent!!.acknowledgementStatus,
                            lastAcknowledgedMessage = updatedEvent!!.acknowledgementMessage,
                            updatedAt = System.currentTimeMillis()
                        )
                    } else {
                        rule
                    }
                }
            )
        }
        return updatedEvent
    }

    @Synchronized
    fun hasEnabledDeskAbsenceAutomation(): Boolean {
        return loadAutomations().any { it.enabled && it.trigger.type == TRIGGER_DESK_ABSENCE }
    }

    @Synchronized
    fun onMonitorStatusChanged(status: MonitorStatus, now: Long = System.currentTimeMillis()): List<GatewayAutomationEvent> {
        val currentRules = loadAutomations()
        if (currentRules.isEmpty()) return emptyList()

        val emittedEvents = mutableListOf<GatewayAutomationEvent>()
        val updatedRules = currentRules.map { rule ->
            val evaluation = evaluateDeskAbsenceRule(rule, status, now, nextEventId(peek = true))
            emittedEvents += evaluation.emittedEvents
            evaluation.updatedRule
        }

        if (emittedEvents.isNotEmpty() || updatedRules != currentRules) {
            saveAutomations(updatedRules)
        }
        if (emittedEvents.isNotEmpty()) {
            val events = loadEvents().toMutableList()
            emittedEvents.forEach { emitted ->
                events += emitted.copy(id = nextEventId())
            }
            saveEvents(events.takeLast(MAX_EVENTS))
        }
        return emittedEvents
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val joined = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
        return joined.ifBlank { "Watcher Android Device" }
    }

    private fun parseTrigger(raw: Any?): GatewayAutomationTrigger {
        val map = raw as? Map<*, *> ?: error("Automation trigger is required.")
        val type = map["type"]?.toString()?.trim().orEmpty()
        require(type == TRIGGER_DESK_ABSENCE) {
            "Unsupported automation trigger: $type"
        }
        @Suppress("UNCHECKED_CAST")
        val params = map["params"] as? Map<String, Any?> ?: emptyMap()
        return GatewayAutomationTrigger(type = type, params = params)
    }

    private fun parseDelivery(raw: Any?): GatewayAutomationDeliveryTarget {
        val map = raw as? Map<*, *> ?: error("Automation delivery is required.")
        val type = map["type"]?.toString()?.trim().orEmpty()
        val targetId = map["targetId"]?.toString()?.trim().orEmpty()
        require(type.isNotBlank()) { "Automation delivery type is required." }
        require(targetId.isNotBlank()) { "Automation delivery targetId is required." }
        @Suppress("UNCHECKED_CAST")
        val metadata = (map["metadata"] as? Map<String, Any?>)
            ?.mapNotNull { (key, value) ->
                val normalizedKey = key.trim()
                val normalizedValue = value?.toString()?.trim().orEmpty()
                if (normalizedKey.isBlank() || normalizedValue.isBlank()) null else normalizedKey to normalizedValue
            }
            ?.toMap()
            ?: emptyMap()
        return GatewayAutomationDeliveryTarget(type = type, targetId = targetId, metadata = metadata)
    }

    private fun parseActionsPreview(raw: Any?): List<String> {
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            is String -> listOf(raw.trim()).filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun loadBindings(): List<GatewayPairingRecord> {
        val raw = prefs.getString(KEY_BINDINGS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<GatewayPairingRecord>>() {}.type
        return runCatching { gson.fromJson<List<GatewayPairingRecord>>(raw, type) }.getOrElse { emptyList() }
    }

    private fun saveBindings(bindings: List<GatewayPairingRecord>) {
        prefs.edit().putString(KEY_BINDINGS, gson.toJson(bindings)).apply()
    }

    private fun loadPairingRequests(): List<GatewayPairingRequest> {
        val raw = prefs.getString(KEY_PAIRING_REQUESTS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<GatewayPairingRequest>>() {}.type
        return runCatching { gson.fromJson<List<GatewayPairingRequest>>(raw, type) }.getOrElse { emptyList() }
    }

    private fun savePairingRequests(requests: List<GatewayPairingRequest>) {
        prefs.edit().putString(KEY_PAIRING_REQUESTS, gson.toJson(requests.takeLast(MAX_PAIRING_REQUESTS))).apply()
    }

    private fun loadPairingRegistry(): GatewayPairingRegistry =
        GatewayPairingRegistry(
            initialRequests = loadPairingRequests(),
            initialBindings = loadBindings()
        )

    private fun refreshPairingState(registry: GatewayPairingRegistry = loadPairingRegistry()) {
        _pairingRequests.value = registry.requests()
        _bindings.value = registry.bindings()
    }

    private fun getOwnedRelayConversation(
        agentBridgeId: String,
        conversationId: String
    ): GatewayRelayConversation? {
        return loadRelayConversations().firstOrNull {
            it.id == conversationId && it.agentBridgeId == agentBridgeId
        }
    }

    private fun loadRelayConversations(): List<GatewayRelayConversation> {
        val raw = prefs.getString(KEY_RELAY_CONVERSATIONS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<GatewayRelayConversation>>() {}.type
        return runCatching { gson.fromJson<List<GatewayRelayConversation>>(raw, type) }.getOrElse { emptyList() }
    }

    private fun saveRelayConversations(conversations: List<GatewayRelayConversation>) {
        val kept = conversations
            .sortedByDescending { it.updatedAt }
            .take(MAX_RELAY_CONVERSATIONS)
        prefs.edit().putString(KEY_RELAY_CONVERSATIONS, gson.toJson(kept)).apply()
        val keptIds = kept.map { it.id }.toSet()
        val messages = loadRelayMessages().filter { it.conversationId in keptIds }
        saveRelayMessages(messages)
    }

    private fun loadRelayMessages(): List<GatewayRelayMessage> {
        val raw = prefs.getString(KEY_RELAY_MESSAGES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<GatewayRelayMessage>>() {}.type
        return runCatching { gson.fromJson<List<GatewayRelayMessage>>(raw, type) }.getOrElse { emptyList() }
    }

    private fun saveRelayMessages(messages: List<GatewayRelayMessage>) {
        prefs.edit().putString(KEY_RELAY_MESSAGES, gson.toJson(messages.trimRelayMessages())).apply()
    }

    private fun List<GatewayRelayMessage>.trimRelayMessages(): List<GatewayRelayMessage> {
        return groupBy { it.conversationId }
            .values
            .flatMap { group -> group.sortedBy { it.id }.takeLast(MAX_RELAY_MESSAGES_PER_CONVERSATION) }
            .sortedBy { it.id }
    }

    private fun nextRelayMessageId(): Long {
        val next = prefs.getLong(KEY_NEXT_RELAY_MESSAGE_ID, 1L)
        prefs.edit().putLong(KEY_NEXT_RELAY_MESSAGE_ID, next + 1L).apply()
        return next
    }

    private fun refreshRelayState() {
        _relayConversations.value = loadRelayConversations()
            .sortedByDescending { it.updatedAt }
        _relayMessages.value = loadRelayMessages()
            .sortedBy { it.id }
    }

    private fun loadAutomations(): List<GatewayAutomationRule> {
        val raw = prefs.getString(KEY_AUTOMATIONS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<GatewayAutomationRule>>() {}.type
        return runCatching { gson.fromJson<List<GatewayAutomationRule>>(raw, type) }.getOrElse { emptyList() }
    }

    private fun saveAutomations(automations: List<GatewayAutomationRule>) {
        prefs.edit().putString(KEY_AUTOMATIONS, gson.toJson(automations)).apply()
    }

    private fun loadEvents(): List<GatewayAutomationEvent> {
        val raw = prefs.getString(KEY_AUTOMATION_EVENTS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<GatewayAutomationEvent>>() {}.type
        return runCatching { gson.fromJson<List<GatewayAutomationEvent>>(raw, type) }.getOrElse { emptyList() }
    }

    private fun saveEvents(events: List<GatewayAutomationEvent>) {
        prefs.edit().putString(KEY_AUTOMATION_EVENTS, gson.toJson(events)).apply()
    }

    private fun nextEventId(peek: Boolean = false): Long {
        val next = prefs.getLong(KEY_NEXT_AUTOMATION_EVENT_ID, 1L)
        if (!peek) {
            prefs.edit().putLong(KEY_NEXT_AUTOMATION_EVENT_ID, next + 1L).apply()
        }
        return next
    }

    companion object {
        private const val MAX_EVENTS = 200
        private const val MAX_PAIRING_REQUESTS = 50
        private const val MAX_RELAY_CONVERSATIONS = 100
        private const val MAX_RELAY_MESSAGES_PER_CONVERSATION = 500
        const val RELAY_AUTHOR_PHONE_USER = "phone_user"
        const val RELAY_AUTHOR_PC_AGENT = "pc_agent"
        const val TRIGGER_DESK_ABSENCE = "desk_absence_detected"
    }
}

internal data class DeskAbsenceEvaluation(
    val updatedRule: GatewayAutomationRule,
    val emittedEvents: List<GatewayAutomationEvent> = emptyList()
)

internal fun evaluateDeskAbsenceRule(
    rule: GatewayAutomationRule,
    status: MonitorStatus,
    now: Long,
    nextEventId: Long
): DeskAbsenceEvaluation {
    if (!rule.enabled || rule.trigger.type != GatewayAutomationManager.TRIGGER_DESK_ABSENCE) {
        return DeskAbsenceEvaluation(updatedRule = rule.copy(lastConditionMatchedAt = null))
    }

    val holdSeconds = (rule.trigger.params["absenceHoldSeconds"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 180L
    val cooldownSeconds = (rule.trigger.params["cooldownSeconds"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 900L
    val isAbsent = status.isRunning && status.lastResult == CheckResult.ALERT

    if (!isAbsent) {
        return DeskAbsenceEvaluation(updatedRule = rule.copy(lastConditionMatchedAt = null))
    }

    val matchedAt = rule.lastConditionMatchedAt ?: now
    val holdSatisfied = now - matchedAt >= holdSeconds * 1_000L
    val coolingDown = rule.lastTriggeredAt?.let { now - it < cooldownSeconds * 1_000L } ?: false
    if (!holdSatisfied || coolingDown) {
        return DeskAbsenceEvaluation(updatedRule = rule.copy(lastConditionMatchedAt = matchedAt))
    }

    val event = GatewayAutomationEvent(
        id = nextEventId,
        automationId = rule.id,
        type = GatewayAutomationManager.TRIGGER_DESK_ABSENCE,
        payload = mapOf(
            "summary" to status.lastSummary,
            "reason" to status.lastReason,
            "remark" to status.lastRemark,
            "confidence" to status.lastConfidence,
            "totalChecks" to status.totalCheckCount
        ),
        createdAt = now
    )
    return DeskAbsenceEvaluation(
        updatedRule = rule.copy(
            lastConditionMatchedAt = matchedAt,
            lastTriggeredAt = now,
            updatedAt = now
        ),
        emittedEvents = listOf(event)
    )
}
