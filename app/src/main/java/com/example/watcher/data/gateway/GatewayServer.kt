package com.example.watcher.data.gateway

import android.graphics.Bitmap
import android.util.Log
import com.example.watcher.agentframework.autonomy.SignalChannel
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.service.AgentFrameworkService
import com.example.watcher.agentframework.service.AgentKnowledgeSeed
import com.example.watcher.agentframework.service.AgentMemorySeed
import com.example.watcher.agentframework.service.AgentSignalSeed
import com.example.watcher.agentframework.service.AutonomousAgentStartRequest
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking

/**
 * Embedded HTTP server exposing Watcher capabilities to LAN clients.
 */
internal class GatewayServer(
    port: Int = DEFAULT_PORT,
    private val apiKey: String,
    private val localIpProvider: () -> String,
    private val frameProvider: () -> Bitmap?,
    private val taskManager: GatewayTaskManager,
    private val automationManager: GatewayAutomationManager? = null,
    private val gatewayStatusProvider: (() -> Map<String, Any?>)? = null,
    private val onRequestObserved: ((String, String) -> Unit)? = null,
    private val agentService: AgentFrameworkService? = null,
    private val commentaryStateProvider: (() -> Any)? = null,
    private val commentaryEntriesProvider: ((since: Long) -> List<Any>)? = null,
    private val onCommentaryAsk: ((List<String>) -> Unit)? = null,
    private val streamStatusProvider: (() -> Map<String, Any?>)? = null,
    private val onStreamHandoff: (() -> String?)? = null,
    private val onStreamReclaim: (() -> Unit)? = null,
    private val onStreamRelease: (() -> Unit)? = null
) : NanoHTTPD(port) {

    private val baseUrl: String get() = "http://${localIpProvider()}:$listeningPort"

    companion object {
        private const val TAG = "GatewayServer"
        const val DEFAULT_PORT = 8080
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri.trimEnd('/')
        Log.d(TAG, "$method $uri")
        onRequestObserved?.invoke(method.name, uri)

        if (method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        if (uri == "/api/health" && method == Method.GET) {
            return ok(
                GatewayRoutes.health(
                    hasFrame = frameProvider() != null,
                    agentConfigured = agentService != null,
                    commentaryConfigured = commentaryStateProvider != null &&
                        commentaryEntriesProvider != null &&
                        onCommentaryAsk != null,
                    streamManagementConfigured = streamStatusProvider != null &&
                        onStreamHandoff != null &&
                        onStreamReclaim != null &&
                        onStreamRelease != null,
                    gateway = gatewayStatusProvider?.invoke()
                )
            )
        }

        if (uri == "/api/device/identity" && method == Method.GET) {
            val manager = automationManager ?: return notImplemented("Automation manager not configured")
            return ok(manager.deviceIdentity())
        }

        if (requiresApiAuthentication(uri) && apiKey.isNotBlank()) {
            if (!isAuthorized(session, uri)) {
                return error(
                    status = Response.Status.UNAUTHORIZED,
                    message = unauthorizedMessage(uri),
                    errorCode = unauthorizedCode(uri)
                )
            }
        }

        return try {
            route(method, uri, session)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri", e)
            error(
                status = Response.Status.INTERNAL_ERROR,
                message = e.message ?: "Internal error",
                errorCode = GatewayRoutes.ERROR_INTERNAL,
                retryable = true
            )
        }
    }

    private fun route(method: Method, uri: String, session: IHTTPSession): Response = when {
        method == Method.GET && uri == "/api/capabilities" ->
            ok(GatewayRoutes.capabilities(baseUrl))

        method == Method.POST && uri == "/api/device/pair" -> pairDevice(session)

        method == Method.GET && uri == "/api/stream/snapshot" -> snapshotResponse()

        method == Method.POST && uri == "/api/tasks" -> createTask(session)

        method == Method.GET && uri == "/api/tasks" -> {
            val tasks = taskManager.listTasks()
            ok(tasks, meta = GatewayMeta(count = tasks.size))
        }

        method == Method.GET && uri.matches(Regex("/api/tasks/[^/]+")) -> getTask(uri)

        method == Method.GET && uri.matches(Regex("/api/tasks/[^/]+/snapshot")) -> getTaskSnapshot(uri)

        method == Method.GET && uri.matches(Regex("/api/tasks/[^/]+/events")) -> getTaskEvents(uri, session)

        method == Method.DELETE && uri.matches(Regex("/api/tasks/[^/]+")) -> cancelTask(uri)

        method == Method.GET && uri == "/api/agents" -> {
            val service = requireAgentService() ?: return notImplemented("Agent service not configured")
            ok(runBlocking { service.listAgents() })
        }

        method == Method.GET && uri.matches(Regex("/api/agents/[^/]+")) -> {
            val service = requireAgentService() ?: return notImplemented("Agent service not configured")
            val agentId = uri.removePrefix("/api/agents/")
            val profile = runBlocking { service.getAgentProfile(agentId) }
            if (profile != null) ok(profile) else notFound("Agent not found: $agentId", details = mapOf("agentId" to agentId))
        }

        method == Method.GET && uri.matches(Regex("/api/agents/[^/]+/runs")) -> {
            val service = requireAgentService() ?: return notImplemented("Agent service not configured")
            val agentId = uri.removePrefix("/api/agents/").removeSuffix("/runs")
            ok(runBlocking { service.listAutonomousRuntimes(agentId) })
        }

        method == Method.POST && uri.matches(Regex("/api/agents/[^/]+/runs")) -> startRuntime(uri, session)

        method == Method.GET && uri.matches(Regex("/api/agents/runs/[^/]+")) -> {
            val service = requireAgentService() ?: return notImplemented("Agent service not configured")
            val runtimeId = uri.removePrefix("/api/agents/runs/")
            val runtime = runBlocking { service.getAutonomousRuntime(runtimeId) }
            if (runtime != null) ok(runtime) else notFound("Autonomous runtime not found: $runtimeId", details = mapOf("runtimeId" to runtimeId))
        }

        method == Method.GET && uri.matches(Regex("/api/agents/runs/[^/]+/events")) -> {
            val service = requireAgentService() ?: return notImplemented("Agent service not configured")
            val runtimeId = uri.removePrefix("/api/agents/runs/").removeSuffix("/events")
            val runtime = runBlocking { service.getAutonomousRuntime(runtimeId) }
            if (runtime == null) {
                notFound("Autonomous runtime not found: $runtimeId", details = mapOf("runtimeId" to runtimeId))
            } else {
                ok(runBlocking { service.getAutonomousRuntimeEvents(runtimeId) })
            }
        }

        method == Method.POST && uri.matches(Regex("/api/agents/runs/[^/]+/signals")) -> sendRuntimeSignal(uri, session)

        method == Method.DELETE && uri.matches(Regex("/api/agents/runs/[^/]+")) -> {
            val service = requireAgentService() ?: return notImplemented("Agent service not configured")
            val runtimeId = uri.removePrefix("/api/agents/runs/")
            val stopped = runBlocking { service.stopAutonomousRuntime(runtimeId) }
            if (stopped) ok(mapOf("message" to "Autonomous runtime stopped"))
            else notFound("Autonomous runtime not found: $runtimeId", details = mapOf("runtimeId" to runtimeId))
        }

        method == Method.GET && uri == "/api/stream/status" -> {
            val provider = streamStatusProvider ?: return notImplemented("Stream management not available")
            ok(provider())
        }

        method == Method.POST && uri == "/api/stream/handoff" -> streamHandoff()

        method == Method.POST && uri == "/api/stream/reclaim" -> {
            val handler = onStreamReclaim ?: return notImplemented("Stream management not available")
            handler()
            ok(mapOf("message" to "Reclaim requested. Waiting for remote client to release."))
        }

        method == Method.POST && uri == "/api/stream/release" -> {
            val handler = onStreamRelease ?: return notImplemented("Stream management not available")
            handler()
            ok(mapOf("message" to "Stream released. Phone is reconnecting."))
        }

        method == Method.GET && uri == "/api/commentary/state" -> {
            val provider = commentaryStateProvider ?: return notImplemented("Commentary not available")
            ok(provider())
        }

        method == Method.GET && uri == "/api/commentary/entries" -> {
            val provider = commentaryEntriesProvider ?: return notImplemented("Commentary not available")
            val since = session.parms["since"]?.toLongOrNull() ?: 0L
            val entries = provider(since)
            val nextSince = entries.mapNotNull { entry ->
                (entry as? Map<*, *>)?.get("wallClockStartTime") as? Number
            }.maxOfOrNull { it.toLong() } ?: since.takeIf { it > 0L }
            ok(entries, meta = GatewayMeta(count = entries.size, nextSince = nextSince))
        }

        method == Method.POST && uri == "/api/commentary/ask" -> commentaryAsk(session)

        method == Method.POST && uri == "/api/automations" -> createAutomation(session)

        method == Method.GET && uri == "/api/automations" -> listAutomations()

        method == Method.GET && uri.matches(Regex("/api/automations/[^/]+")) -> getAutomation(uri)

        method == Method.PATCH && uri.matches(Regex("/api/automations/[^/]+")) -> updateAutomation(uri, session)

        method == Method.GET && uri.matches(Regex("/api/automations/[^/]+/events")) -> getAutomationEvents(uri, session)

        method == Method.POST && uri.matches(Regex("/api/automations/[^/]+/ack")) -> acknowledgeAutomation(uri, session)

        else -> notFound("Not found: $uri", details = mapOf("path" to uri))
    }

    private fun pairDevice(session: IHTTPSession): Response {
        val manager = automationManager ?: return notImplemented("Automation manager not configured")
        val params = requireBodyMap(session) ?: return invalidBody()
        val bridgeId = params["bridgeId"]?.toString()?.trim().orEmpty()
        if (bridgeId.isBlank()) {
            return missingField("bridgeId")
        }
        val bridgeName = params["bridgeName"]?.toString().orEmpty()
        return ok(manager.pair(bridgeId, bridgeName), status = Response.Status.CREATED)
    }

    private fun createTask(session: IHTTPSession): Response {
        val params = requireBodyMap(session) ?: return invalidBody()
        val tool = params["tool"] as? String
        if (tool.isNullOrBlank()) {
            return missingField("tool")
        }
        val task = taskManager.createTask(tool, params - "tool")
        return if (task.error?.startsWith("Unknown tool:") == true) {
            error(
                status = Response.Status.BAD_REQUEST,
                message = task.error,
                errorCode = GatewayRoutes.ERROR_UNKNOWN_TOOL,
                details = mapOf("tool" to tool)
            )
        } else {
            ok(task, status = Response.Status.CREATED)
        }
    }

    private fun getTask(uri: String): Response {
        val taskId = uri.removePrefix("/api/tasks/")
        val task = taskManager.getTask(taskId)
        return if (task != null) ok(task) else notFound("Task not found: $taskId", details = mapOf("taskId" to taskId))
    }

    private fun getTaskSnapshot(uri: String): Response {
        val taskId = uri.removePrefix("/api/tasks/").removeSuffix("/snapshot")
        val task = taskManager.getTask(taskId)
            ?: return notFound("Task not found: $taskId", details = mapOf("taskId" to taskId))
        if (task.status != GatewayTaskStatus.Running) {
            return invalidState(
                "Task is not running",
                details = mapOf("taskId" to taskId, "status" to task.status.name)
            )
        }
        return snapshotResponse("No frame available")
    }

    private fun getTaskEvents(uri: String, session: IHTTPSession): Response {
        val taskId = uri.removePrefix("/api/tasks/").removeSuffix("/events")
        val since = session.parms["since"]?.toLongOrNull()
        val afterEventId = session.parms["afterEventId"]?.toLongOrNull()
        val events = taskManager.listTaskEvents(taskId, since = since, afterEventId = afterEventId)
            ?: return notFound("Task not found: $taskId", details = mapOf("taskId" to taskId))
        val nextSince = events.maxOfOrNull { it.timestamp } ?: since
        return ok(events, meta = GatewayMeta(count = events.size, nextSince = nextSince))
    }

    private fun cancelTask(uri: String): Response {
        val taskId = uri.removePrefix("/api/tasks/")
        return when (taskManager.cancelTask(taskId)) {
            GatewayTaskCancelResult.Cancelled -> ok(mapOf("message" to "Task cancellation requested"))
            GatewayTaskCancelResult.AlreadyFinished -> invalidState(
                "Task is already finished",
                details = mapOf("taskId" to taskId)
            )
            GatewayTaskCancelResult.NotFound -> notFound("Task not found: $taskId", details = mapOf("taskId" to taskId))
        }
    }

    private fun startRuntime(uri: String, session: IHTTPSession): Response {
        val service = requireAgentService() ?: return notImplemented("Agent service not configured")
        val agentId = uri.removePrefix("/api/agents/").removeSuffix("/runs")
        val params = requireBodyMap(session) ?: return invalidBody()
        val record = runBlocking {
            service.startAutonomousAgent(
                AutonomousAgentStartRequest(
                    agentId = agentId,
                    initialSignals = parseSignals(params["signals"]),
                    preloadMemory = parseMemorySeeds(params["preloadMemory"]),
                    preloadKnowledge = parseKnowledgeSeeds(params["preloadKnowledge"])
                )
            )
        }
        return ok(record, status = Response.Status.CREATED)
    }

    private fun sendRuntimeSignal(uri: String, session: IHTTPSession): Response {
        val service = requireAgentService() ?: return notImplemented("Agent service not configured")
        val runtimeId = uri.removePrefix("/api/agents/runs/").removeSuffix("/signals")
        val params = requireBodyMap(session) ?: return invalidBody()
        val signal = parseSingleSignal(params)
            ?: return missingField("channel/content", details = mapOf("required" to listOf("channel", "content")))
        runBlocking { service.getAutonomousRuntime(runtimeId) }
            ?: return notFound("Autonomous runtime not found: $runtimeId", details = mapOf("runtimeId" to runtimeId))
        val runtime = runBlocking { service.submitAutonomousSignal(runtimeId, signal) }
        return ok(runtime)
    }

    private fun streamHandoff(): Response {
        val handler = onStreamHandoff ?: return notImplemented("Stream management not available")
        val currentOwner = (streamStatusProvider?.invoke()?.get("owner") as? String) ?: "phone"
        if (currentOwner == "remote") {
            return conflict("Stream already handed off to remote client")
        }
        val url = handler()
        return if (url.isNullOrBlank()) {
            unavailable("Phone has no active stream connection")
        } else {
            ok(
                mapOf(
                    "streamUrl" to url,
                    "message" to "Phone connection released. Connect to streamUrl directly. Poll GET /api/stream/status to check for reclaim requests."
                )
            )
        }
    }

    private fun commentaryAsk(session: IHTTPSession): Response {
        val handler = onCommentaryAsk ?: return notImplemented("Commentary not available")
        val body = requireBodyMap(session) ?: return invalidBody()
        val requests = (body["requests"] as? List<*>)?.filterIsInstance<String>()
        if (requests.isNullOrEmpty()) {
            return missingField("requests", details = mapOf("expected" to "string array"))
        }
        handler(requests)
        return ok(mapOf("accepted" to requests.size))
    }

    private fun createAutomation(session: IHTTPSession): Response {
        val manager = automationManager ?: return notImplemented("Automation manager not configured")
        val params = requireBodyMap(session) ?: return invalidBody()
        return runCatching {
            manager.createAutomation(params)
        }.fold(
            onSuccess = { ok(it, status = Response.Status.CREATED) },
            onFailure = {
                error(
                    status = Response.Status.BAD_REQUEST,
                    message = it.message ?: "Failed to create automation",
                    errorCode = GatewayRoutes.ERROR_MISSING_FIELD
                )
            }
        )
    }

    private fun listAutomations(): Response {
        val manager = automationManager ?: return notImplemented("Automation manager not configured")
        val rules = manager.listAutomations()
        return ok(rules, meta = GatewayMeta(count = rules.size))
    }

    private fun getAutomation(uri: String): Response {
        val manager = automationManager ?: return notImplemented("Automation manager not configured")
        val automationId = uri.removePrefix("/api/automations/")
        val rule = manager.getAutomation(automationId)
        return if (rule != null) ok(rule) else notFound("Automation not found: $automationId", mapOf("automationId" to automationId))
    }

    private fun updateAutomation(uri: String, session: IHTTPSession): Response {
        val manager = automationManager ?: return notImplemented("Automation manager not configured")
        val automationId = uri.removePrefix("/api/automations/")
        val params = requireBodyMap(session) ?: return invalidBody()
        val rule = runCatching { manager.updateAutomation(automationId, params) }
            .getOrElse {
                return error(
                    status = Response.Status.BAD_REQUEST,
                    message = it.message ?: "Failed to update automation",
                    errorCode = GatewayRoutes.ERROR_INVALID_BODY
                )
            }
        return if (rule != null) ok(rule) else notFound("Automation not found: $automationId", mapOf("automationId" to automationId))
    }

    private fun getAutomationEvents(uri: String, session: IHTTPSession): Response {
        val manager = automationManager ?: return notImplemented("Automation manager not configured")
        val automationId = uri.removePrefix("/api/automations/").removeSuffix("/events")
        val since = session.parms["since"]?.toLongOrNull()
        val afterEventId = session.parms["afterEventId"]?.toLongOrNull()
        val events = manager.listAutomationEvents(automationId, since = since, afterEventId = afterEventId)
            ?: return notFound("Automation not found: $automationId", mapOf("automationId" to automationId))
        val nextSince = events.maxOfOrNull { it.createdAt } ?: since
        return ok(events, meta = GatewayMeta(count = events.size, nextSince = nextSince))
    }

    private fun acknowledgeAutomation(uri: String, session: IHTTPSession): Response {
        val manager = automationManager ?: return notImplemented("Automation manager not configured")
        val automationId = uri.removePrefix("/api/automations/").removeSuffix("/ack")
        val params = requireBodyMap(session) ?: return invalidBody()
        val eventId = (params["eventId"] as? Number)?.toLong() ?: return missingField("eventId")
        val status = params["status"]?.toString()?.trim().orEmpty().ifBlank { "received" }
        val message = params["message"]?.toString()
        val event = manager.acknowledgeAutomationEvent(automationId, eventId, status, message)
        return if (event != null) ok(event) else notFound(
            "Automation or event not found",
            mapOf("automationId" to automationId, "eventId" to eventId)
        )
    }

    private fun requireAgentService(): AgentFrameworkService? = agentService

    private fun requireBodyMap(session: IHTTPSession): Map<String, Any?>? {
        val body = readBody(session)
        return GatewayRoutes.parseBody(body)
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun snapshotResponse(unavailableMessage: String = "No frame available — stream may not be connected"): Response {
        val result = GatewayRoutes.snapshot(frameProvider)
        return if (result != null) {
            corsResponse(
                newFixedLengthResponse(
                    Response.Status.OK,
                    result.mimeType,
                    ByteArrayInputStream(result.bytes),
                    result.bytes.size.toLong()
                )
            )
        } else {
            unavailable(unavailableMessage)
        }
    }

    private fun ok(data: Any?, meta: GatewayMeta? = GatewayMeta(), status: Response.Status = Response.Status.OK): Response {
        return jsonResponse(GatewayRoutes.ok(data, meta), status)
    }

    private fun invalidBody(): Response = error(
        status = Response.Status.BAD_REQUEST,
        message = "Request body is missing or invalid JSON",
        errorCode = GatewayRoutes.ERROR_INVALID_BODY
    )

    private fun missingField(field: String, details: Any? = mapOf("field" to field)): Response = error(
        status = Response.Status.BAD_REQUEST,
        message = "Missing required field: $field",
        errorCode = GatewayRoutes.ERROR_MISSING_FIELD,
        details = details
    )

    private fun notFound(message: String, details: Any? = null): Response = error(
        status = Response.Status.NOT_FOUND,
        message = message,
        errorCode = GatewayRoutes.ERROR_NOT_FOUND,
        details = details
    )

    private fun invalidState(message: String, details: Any? = null): Response = error(
        status = Response.Status.BAD_REQUEST,
        message = message,
        errorCode = GatewayRoutes.ERROR_INVALID_STATE,
        details = details
    )

    private fun notImplemented(message: String): Response = error(
        status = Response.Status.NOT_IMPLEMENTED,
        message = message,
        errorCode = GatewayRoutes.ERROR_NOT_IMPLEMENTED
    )

    private fun unavailable(message: String): Response = error(
        status = Response.Status.SERVICE_UNAVAILABLE,
        message = message,
        errorCode = GatewayRoutes.ERROR_SERVICE_UNAVAILABLE,
        retryable = true
    )

    private fun conflict(message: String): Response = error(
        status = Response.Status.CONFLICT,
        message = message,
        errorCode = GatewayRoutes.ERROR_CONFLICT
    )

    private fun error(
        status: Response.Status,
        message: String,
        errorCode: String,
        details: Any? = null,
        retryable: Boolean = false
    ): Response {
        return jsonResponse(
            GatewayRoutes.error(
                message = message,
                errorCode = errorCode,
                details = details,
                retryable = retryable
            ),
            status
        )
    }

    private fun jsonResponse(json: String, status: Response.Status = Response.Status.OK): Response {
        return corsResponse(newFixedLengthResponse(status, "application/json; charset=utf-8", json))
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-API-Key, Authorization, X-Binding-Token")
        return response
    }

    private fun requiresApiAuthentication(uri: String): Boolean {
        if (!uri.startsWith("/api/")) return false
        return uri != "/api/health" && uri != "/api/device/identity"
    }

    private fun isAuthorized(session: IHTTPSession, uri: String): Boolean {
        val providedApiKey = session.headers["x-api-key"] ?: session.parms["api_key"]
        if (providedApiKey == apiKey) return true
        if (!uri.startsWith("/api/automations")) return false
        val manager = automationManager ?: return false
        val tokenHeader = session.headers["x-binding-token"]
        val authorization = session.headers["authorization"]
        val bearerToken = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.removePrefix("Bearer ")
            ?.trim()
        return manager.isValidBindingToken(tokenHeader) || manager.isValidBindingToken(bearerToken)
    }

    private fun unauthorizedMessage(uri: String): String {
        return if (uri.startsWith("/api/automations")) {
            "Invalid or missing API key / binding token"
        } else {
            "Invalid or missing API key"
        }
    }

    private fun unauthorizedCode(uri: String): String {
        return if (uri.startsWith("/api/automations")) {
            GatewayRoutes.ERROR_INVALID_TOKEN
        } else {
            GatewayRoutes.ERROR_INVALID_API_KEY
        }
    }

    private fun parseSignals(raw: Any?): List<AgentSignalSeed> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val channel = (map["channel"] as? String)
                ?.let { runCatching { SignalChannel.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            val content = map["content"] as? String ?: return@mapNotNull null
            AgentSignalSeed(
                channel = channel,
                content = content,
                metadata = parseStringMap(map["metadata"])
            )
        }
    }

    private fun parseSingleSignal(raw: Map<String, Any?>): AgentSignalSeed? {
        val channel = (raw["channel"] as? String)
            ?.let { runCatching { SignalChannel.valueOf(it) }.getOrNull() }
            ?: return null
        val content = raw["content"] as? String ?: return null
        return AgentSignalSeed(
            channel = channel,
            content = content,
            metadata = parseStringMap(raw["metadata"])
        )
    }

    private fun parseMemorySeeds(raw: Any?): List<AgentMemorySeed> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val scope = when ((map["scope"] as? String)?.lowercase()) {
                "episodic" -> AgentMemoryScope.Episodic
                "working" -> AgentMemoryScope.Working
                else -> null
            } ?: return@mapNotNull null
            val content = map["content"] as? String ?: return@mapNotNull null
            AgentMemorySeed(
                scope = scope,
                content = content,
                tags = parseStringSet(map["tags"])
            )
        }
    }

    private fun parseKnowledgeSeeds(raw: Any?): List<AgentKnowledgeSeed> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val content = map["content"] as? String ?: return@mapNotNull null
            AgentKnowledgeSeed(
                content = content,
                tags = parseStringSet(map["tags"]),
                metadata = parseStringMap(map["metadata"])
            )
        }
    }

    private fun parseStringSet(raw: Any?): Set<String> {
        return (raw as? List<*>)
            .orEmpty()
            .mapNotNull { it as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun parseStringMap(raw: Any?): Map<String, String> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { entry ->
            val key = entry.key as? String ?: return@mapNotNull null
            val value = entry.value?.toString() ?: return@mapNotNull null
            key to value
        }.toMap()
    }
}
