package com.example.watcher.data.gateway

import android.content.Context
import android.os.Build
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.MonitorStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

private const val AUTOMATION_PREFS = "gateway_automation_prefs"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_BINDINGS = "bindings"
private const val KEY_AUTOMATIONS = "automations"
private const val KEY_AUTOMATION_EVENTS = "automation_events"
private const val KEY_NEXT_AUTOMATION_EVENT_ID = "next_automation_event_id"

internal class GatewayAutomationManager(
    context: Context
) {
    private val prefs = context.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    var onRulesChanged: (() -> Unit)? = null

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

    @Synchronized
    fun deviceIdentity(): GatewayDeviceIdentity {
        return GatewayDeviceIdentity(
            deviceId = deviceId,
            deviceName = buildDeviceName(),
            serviceVersion = "1.2",
            protocolVersion = "2026-05-automation-v1",
            capabilities = listOf("gateway", "automation", "agent", "stream", "commentary")
        )
    }

    @Synchronized
    fun pair(bridgeId: String, bridgeName: String): GatewayPairingResult {
        val normalizedId = bridgeId.trim().ifBlank { "watcher-bridge" }
        val normalizedName = bridgeName.trim().ifBlank { normalizedId }
        val existing = loadBindings().firstOrNull { it.bridgeId == normalizedId }
        val record = if (existing != null) {
            existing.copy(bridgeName = normalizedName, lastSeenAt = System.currentTimeMillis())
        } else {
            GatewayPairingRecord(
                bridgeId = normalizedId,
                bridgeName = normalizedName,
                bindingToken = UUID.randomUUID().toString().replace("-", "")
            )
        }
        saveBindings(
            loadBindings()
                .filterNot { it.bridgeId == normalizedId }
                .plus(record)
                .sortedBy { it.bridgeId }
        )
        return GatewayPairingResult(
            bridgeId = record.bridgeId,
            bridgeName = record.bridgeName,
            bindingToken = record.bindingToken,
            deviceId = deviceId,
            createdAt = record.createdAt
        )
    }

    @Synchronized
    fun isValidBindingToken(token: String?): Boolean {
        val normalized = token?.trim().orEmpty()
        if (normalized.isBlank()) return false
        return loadBindings().any { it.bindingToken == normalized }
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
