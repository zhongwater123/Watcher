package com.example.watcher.data.gateway

/** Status of a gateway task. */
enum class GatewayTaskStatus { Pending, Running, Completed, Failed, Cancelled }

/** Result of a task cancellation attempt. */
enum class GatewayTaskCancelResult { Cancelled, AlreadyFinished, NotFound }

const val GATEWAY_PAIRING_REQUEST_TTL_MILLIS = 10 * 60 * 1000L

/** A task submitted through the gateway API. */
data class GatewayTask(
    val id: String,
    val tool: String,
    val params: Map<String, Any?>,
    val status: GatewayTaskStatus = GatewayTaskStatus.Pending,
    val result: Any? = null,
    val error: String? = null,
    val events: MutableList<GatewayEvent> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

/** A real-time event emitted during task execution. */
data class GatewayEvent(
    val type: String,
    val data: Any?,
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

/** Standard metadata attached to successful gateway responses. */
data class GatewayMeta(
    val timestamp: Long = System.currentTimeMillis(),
    val nextSince: Long? = null,
    val count: Int? = null
)

/** Standard API response wrapper. */
data class GatewayResponse(
    val ok: Boolean,
    val data: Any? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val details: Any? = null,
    val retryable: Boolean? = null,
    val meta: GatewayMeta? = null
)

data class GatewayDeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val serviceVersion: String,
    val protocolVersion: String,
    val capabilities: List<String>,
    val discoveredAt: Long = System.currentTimeMillis()
)

data class GatewayPairingRecord(
    val bridgeId: String,
    val bridgeName: String,
    val bindingToken: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis()
)

data class GatewayPairingResult(
    val bridgeId: String,
    val bridgeName: String,
    val bindingToken: String,
    val deviceId: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class GatewayPairingRequestStatus { Pending, Approved, Rejected, Expired }

data class GatewayPairingRequest(
    val id: String,
    val bridgeId: String,
    val bridgeName: String,
    val sourceHost: String? = null,
    val status: GatewayPairingRequestStatus = GatewayPairingRequestStatus.Pending,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + GATEWAY_PAIRING_REQUEST_TTL_MILLIS,
    val bindingToken: String? = null,
    val deviceId: String? = null
)

data class GatewayRelayConversation(
    val id: String,
    val agentBridgeId: String,
    val agentBridgeName: String,
    val title: String,
    val summary: String = "",
    val status: String = "active",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long? = null
)

data class GatewayRelayMessage(
    val id: Long,
    val conversationId: String,
    val author: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val seenByAgentAt: Long? = null
)

data class GatewayAutomationTrigger(
    val type: String,
    val params: Map<String, Any?> = emptyMap()
)

data class GatewayAutomationDeliveryTarget(
    val type: String,
    val targetId: String,
    val metadata: Map<String, String> = emptyMap()
)

data class GatewayAutomationRule(
    val id: String,
    val name: String,
    val trigger: GatewayAutomationTrigger,
    val delivery: GatewayAutomationDeliveryTarget,
    val enabled: Boolean = true,
    val actionsPreview: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastConditionMatchedAt: Long? = null,
    val lastTriggeredAt: Long? = null,
    val lastAcknowledgedAt: Long? = null,
    val lastAcknowledgedStatus: String? = null,
    val lastAcknowledgedMessage: String? = null
)

data class GatewayAutomationEvent(
    val id: Long,
    val automationId: String,
    val type: String,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val acknowledgedAt: Long? = null,
    val acknowledgementStatus: String? = null,
    val acknowledgementMessage: String? = null
)

data class GatewayRuntimeStatus(
    val isRunning: Boolean = false,
    val configuredPort: Int = GatewayServer.DEFAULT_PORT,
    val listeningPort: Int? = null,
    val localIp: String = "0.0.0.0",
    val startedAt: Long? = null,
    val lastRequestAt: Long? = null,
    val lastRequestMethod: String? = null,
    val lastRequestPath: String? = null,
    val lastError: String? = null
)
