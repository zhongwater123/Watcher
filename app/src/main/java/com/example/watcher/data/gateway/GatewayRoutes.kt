package com.example.watcher.data.gateway

import android.graphics.Bitmap
import com.google.gson.Gson
import java.io.ByteArrayOutputStream

/**
 * Route handler implementations for the gateway API.
 * Stateless — receives dependencies through method parameters.
 */
internal object GatewayRoutes {

    const val ERROR_INVALID_API_KEY = "invalid_api_key"
    const val ERROR_INVALID_BODY = "invalid_body"
    const val ERROR_MISSING_FIELD = "missing_field"
    const val ERROR_NOT_FOUND = "not_found"
    const val ERROR_INVALID_STATE = "invalid_state"
    const val ERROR_NOT_IMPLEMENTED = "not_implemented"
    const val ERROR_SERVICE_UNAVAILABLE = "service_unavailable"
    const val ERROR_CONFLICT = "conflict"
    const val ERROR_UNKNOWN_TOOL = "unknown_tool"
    const val ERROR_INTERNAL = "internal_error"
    const val ERROR_INVALID_TOKEN = "invalid_token"

    private val gson = Gson()

    fun capabilities(baseUrl: String): Map<String, Any> = mapOf(
        "service" to mapOf(
            "name" to "Watcher",
            "version" to "1.1",
            "description" to "Watcher exposes LAN APIs for visual monitoring, video analysis, agent runtime control, stream ownership handoff, and commentary state polling."
        ),
        "protocolVersion" to "2026-05-automation-v1",
        "auth" to mapOf(
            "type" to "api_key",
            "header" to "X-API-Key",
            "queryFallback" to "api_key",
            "bindingTokenHeader" to "Authorization: Bearer <token> or X-Binding-Token",
            "note" to "All /api/ endpoints except /api/health, /api/device/identity, and /api/device/pair-requests require either an API key or an approved binding token. POST /api/device/pair requires the API key and cannot mint new tokens from an existing binding token. Relay chat now uses ntfy pub/sub instead of gateway HTTP."
        ),
        "baseUrl" to baseUrl,
        "responseEnvelope" to mapOf(
            "success" to mapOf("ok" to true, "data" to "payload", "meta" to "{timestamp,count,nextSince}"),
            "error" to mapOf("ok" to false, "error" to "human readable message", "errorCode" to "stable_code", "details" to "optional object", "retryable" to false)
        ),
        "polling" to mapOf(
            "taskEvents" to "GET /api/tasks/{id}/events?afterEventId=<n> or ?since=<timestamp>",
            "commentaryEntries" to "GET /api/commentary/entries?since=<timestamp>",
            "automationEvents" to "GET /api/automations/{id}/events?afterEventId=<n> or ?since=<timestamp>"
        ),
        "endpoints" to mapOf(
            "health" to endpoint("GET", "$baseUrl/api/health", false, "Health check with service availability summary."),
            "device_identity" to endpoint("GET", "$baseUrl/api/device/identity", false, "Returns a stable device identity for zero-config discovery."),
            "device_pair" to endpoint("POST", "$baseUrl/api/device/pair", true, "Pairs a desktop watcher bridge and returns a binding token.", body = """{"bridgeId":"watcher-desktop","bridgeName":"Desktop"}"""),
            "device_pair_request_create" to endpoint("POST", "$baseUrl/api/device/pair-requests", false, "Create a first-use pairing request that must be approved on the phone.", body = """{"bridgeId":"watcher-mcp","bridgeName":"Watcher MCP"}"""),
            "device_pair_request_get" to endpoint("GET", "$baseUrl/api/device/pair-requests/{requestId}", false, "Poll a pairing request until it is approved, rejected, or expired."),
            "capabilities" to endpoint("GET", "$baseUrl/api/capabilities", true, "Returns the gateway protocol contract."),
            "stream_snapshot" to endpoint("GET", "$baseUrl/api/stream/snapshot", true, "Returns the current frame as image/jpeg.", returns = "image/jpeg"),
            "tasks_create" to endpoint("POST", "$baseUrl/api/tasks", true, "Create and execute a task.", body = """{"tool":"<name>", ...params}"""),
            "tasks_list" to endpoint("GET", "$baseUrl/api/tasks", true, "List tasks, newest first."),
            "tasks_get" to endpoint("GET", "$baseUrl/api/tasks/{id}", true, "Get one task including current status and accumulated events."),
            "tasks_events" to endpoint("GET", "$baseUrl/api/tasks/{id}/events?afterEventId={n}&since={timestamp}", true, "Get task events incrementally or in full."),
            "tasks_snapshot" to endpoint("GET", "$baseUrl/api/tasks/{id}/snapshot", true, "Get the current frame for a running task.", returns = "image/jpeg"),
            "tasks_cancel" to endpoint("DELETE", "$baseUrl/api/tasks/{id}", true, "Cancel a running task."),
            "agents_list" to endpoint("GET", "$baseUrl/api/agents", true, "List registered agents."),
            "agents_get" to endpoint("GET", "$baseUrl/api/agents/{id}", true, "Get one agent profile."),
            "agent_runs_list" to endpoint("GET", "$baseUrl/api/agents/{id}/runs", true, "List runtime records for an agent."),
            "agent_runs_create" to endpoint("POST", "$baseUrl/api/agents/{id}/runs", true, "Start an autonomous runtime for an agent."),
            "agent_runtime_get" to endpoint("GET", "$baseUrl/api/agents/runs/{runtimeId}", true, "Get one autonomous runtime snapshot."),
            "agent_runtime_events" to endpoint("GET", "$baseUrl/api/agents/runs/{runtimeId}/events", true, "Get autonomous runtime events."),
            "agent_runtime_signal" to endpoint("POST", "$baseUrl/api/agents/runs/{runtimeId}/signals", true, "Send a signal to a runtime."),
            "agent_runtime_stop" to endpoint("DELETE", "$baseUrl/api/agents/runs/{runtimeId}", true, "Stop an autonomous runtime."),
            "stream_status" to endpoint("GET", "$baseUrl/api/stream/status", true, "Check stream ownership and reclaim status."),
            "stream_handoff" to endpoint("POST", "$baseUrl/api/stream/handoff", true, "Release the phone-owned stream and return the remote stream URL."),
            "stream_reclaim" to endpoint("POST", "$baseUrl/api/stream/reclaim", true, "Request that the remote client release the stream."),
            "stream_release" to endpoint("POST", "$baseUrl/api/stream/release", true, "Confirm the remote client has released the stream."),
            "commentary_state" to endpoint("GET", "$baseUrl/api/commentary/state", true, "Return live commentary state and speech state."),
            "commentary_entries" to endpoint("GET", "$baseUrl/api/commentary/entries?since={timestamp}", true, "Return commentary entries, optionally incrementally."),
            "commentary_ask" to endpoint("POST", "$baseUrl/api/commentary/ask", true, "Submit observation requests to commentary consumers.", body = """{"requests":["..."]}"""),
            "automations_create" to endpoint("POST", "$baseUrl/api/automations", true, "Create an automation rule for gateway-triggered desktop workflows."),
            "automations_list" to endpoint("GET", "$baseUrl/api/automations", true, "List automation rules."),
            "automations_get" to endpoint("GET", "$baseUrl/api/automations/{id}", true, "Get one automation rule."),
            "automations_update" to endpoint("PATCH", "$baseUrl/api/automations/{id}", true, "Update one automation rule."),
            "automations_events" to endpoint("GET", "$baseUrl/api/automations/{id}/events?afterEventId={n}&since={timestamp}", true, "Poll automation trigger events incrementally."),
            "automations_ack" to endpoint("POST", "$baseUrl/api/automations/{id}/ack", true, "Acknowledge a delivered automation event.", body = """{"eventId":1,"status":"completed","message":"optional"}""")
        ),
        "taskLifecycle" to mapOf(
            "statuses" to GatewayTaskStatus.values().map { it.name },
            "flow" to "Pending -> Running -> Completed|Failed|Cancelled",
            "eventFields" to listOf("id", "type", "data", "timestamp")
        ),
        "tools" to listOf(
            tool(
                name = "snapshot",
                description = "获取当前摄像头画面的实时截图。实际图片通过 GET /api/stream/snapshot 获取。",
                properties = emptyMap(),
                required = emptyList<String>()
            ),
            tool(
                name = "monitor",
                description = "创建持续监控任务，并通过 events 返回 check_result。",
                properties = mapOf(
                    "objective" to mapOf("type" to "string"),
                    "checkIntervalSeconds" to mapOf("type" to "integer", "default" to 30),
                    "triggerCondition" to mapOf("type" to "string")
                ),
                required = listOf("objective")
            ),
            tool(
                name = "video_analysis",
                description = "录制视频片段并逐段分析，返回最终 summary、conclusion 和 timeline。",
                properties = mapOf(
                    "task" to mapOf("type" to "string"),
                    "durationSeconds" to mapOf("type" to "integer", "default" to 60),
                    "segmentSeconds" to mapOf("type" to "integer", "default" to 10)
                ),
                required = listOf("task")
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "council",
                    "description" to "智囊团多专家分析入口。",
                    "parameters" to mapOf("type" to "object")
                ),
                "status" to "not_implemented"
            )
        ),
        "automations" to listOf(
            mapOf(
                "triggerType" to GatewayAutomationManager.TRIGGER_DESK_ABSENCE,
                "description" to "当监控结果连续判定为用户离开工位时触发。",
                "params" to mapOf(
                    "absenceHoldSeconds" to mapOf("type" to "integer", "default" to 180),
                    "cooldownSeconds" to mapOf("type" to "integer", "default" to 900)
                ),
                "deliveryTypes" to listOf("desktop_bridge")
            )
        )
    )

    fun health(
        hasFrame: Boolean,
        agentConfigured: Boolean,
        commentaryConfigured: Boolean,
        streamManagementConfigured: Boolean,
        gateway: Map<String, Any?>? = null
    ): Map<String, Any> = mapOf(
        "status" to "ok",
        "streamConnected" to hasFrame,
        "services" to mapOf(
            "agent" to agentConfigured,
            "commentary" to commentaryConfigured,
            "streamManagement" to streamManagementConfigured
        ),
        "timestamp" to System.currentTimeMillis()
    ).let { base ->
        if (gateway == null) base else base + mapOf("gateway" to gateway)
    }

    data class SnapshotResult(val bytes: ByteArray, val mimeType: String)

    fun snapshot(frameProvider: () -> Bitmap?): SnapshotResult? {
        val bitmap = frameProvider() ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return SnapshotResult(bytes = out.toByteArray(), mimeType = "image/jpeg")
    }

    fun toJson(obj: Any): String = gson.toJson(obj)

    fun ok(data: Any? = null, meta: GatewayMeta? = GatewayMeta()): String {
        return toJson(GatewayResponse(ok = true, data = data, meta = meta))
    }

    fun error(
        message: String,
        errorCode: String,
        details: Any? = null,
        retryable: Boolean = false
    ): String {
        return toJson(
            GatewayResponse(
                ok = false,
                error = message,
                errorCode = errorCode,
                details = details,
                retryable = retryable
            )
        )
    }

    fun parseBody(body: String): Map<String, Any?>? {
        if (body.isBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(body, Map::class.java) as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
    }

    private fun endpoint(
        method: String,
        url: String,
        auth: Boolean,
        description: String,
        body: String? = null,
        returns: String? = null
    ): Map<String, Any> = buildMap {
        put("method", method)
        put("url", url)
        put("auth", auth)
        put("description", description)
        if (body != null) put("body", body)
        if (returns != null) put("returns", returns)
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, Any>,
        required: List<String>
    ): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required
            )
        )
    )
}
