package com.example.watcher.data.gateway

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.MonitorStatus

class GatewayContractTest {

    private val gson = Gson()

    @Test
    fun parseBodyReturnsNullForInvalidJson() {
        val parsed = GatewayRoutes.parseBody("{invalid")

        assertNull(parsed)
    }

    @Test
    fun errorResponseIncludesStableFields() {
        val json = GatewayRoutes.error(
            message = "Task not found",
            errorCode = GatewayRoutes.ERROR_NOT_FOUND,
            details = mapOf("taskId" to "abc"),
            retryable = false
        )

        val response = gson.fromJson(json, GatewayResponse::class.java)

        assertFalse(response.ok)
        assertEquals("Task not found", response.error)
        assertEquals(GatewayRoutes.ERROR_NOT_FOUND, response.errorCode)
        assertEquals(false, response.retryable)
        assertNotNull(response.details)
    }

    @Test
    fun capabilitiesMarksCouncilAsNotImplementedAndDocumentsEventPolling() {
        val capabilities = GatewayRoutes.capabilities("http://127.0.0.1:8080")

        val polling = capabilities["polling"] as Map<*, *>
        assertEquals("GET /api/tasks/{id}/events?afterEventId=<n> or ?since=<timestamp>", polling["taskEvents"])
        assertEquals(
            "GET /api/automations/{id}/events?afterEventId=<n> or ?since=<timestamp>",
            polling["automationEvents"]
        )

        val tools = capabilities["tools"] as List<*>
        val council = tools
            .mapNotNull { it as? Map<*, *> }
            .first { (it["function"] as? Map<*, *>)?.get("name") == "council" }
        assertEquals("not_implemented", council["status"])

        val automations = capabilities["automations"] as List<*>
        val deskAbsence = automations
            .mapNotNull { it as? Map<*, *> }
            .first { it["triggerType"] == GatewayAutomationManager.TRIGGER_DESK_ABSENCE }
        assertEquals(listOf("desktop_bridge"), deskAbsence["deliveryTypes"])

        val endpoints = capabilities["endpoints"] as Map<*, *>
        assertNotNull(endpoints["device_pair_request_create"])
        assertNotNull(endpoints["device_pair_request_get"])
        assertNotNull(endpoints["relay_conversations_register"])
        assertNotNull(endpoints["relay_conversations_list"])
        assertNotNull(endpoints["relay_messages_list"])
        assertNotNull(endpoints["relay_messages_create"])
        assertNotNull(endpoints["relay_messages_seen"])
    }

    @Test
    fun pairingRegistryTracksPendingExpiryApproveAndReject() {
        val registry = GatewayPairingRegistry()

        val request = registry.createRequest(
            bridgeId = "watcher-mcp",
            bridgeName = "Watcher MCP",
            sourceHost = "10.0.0.9",
            now = 1_000L
        )

        assertEquals(GatewayPairingRequestStatus.Pending, request.status)
        assertEquals("10.0.0.9", request.sourceHost)
        assertTrue(registry.pendingRequests(now = 1_500L).any { it.id == request.id })

        val expired = registry.getRequest(request.id, now = request.expiresAt + 1L)
        assertEquals(GatewayPairingRequestStatus.Expired, expired?.status)
        assertTrue(registry.pendingRequests(now = request.expiresAt + 1L).isEmpty())

        val approvedRequest = registry.createRequest(
            bridgeId = "claude-code",
            bridgeName = "Claude Code",
            sourceHost = "10.0.0.10",
            now = 3_000L
        )
        val approved = registry.approveRequest(approvedRequest.id, now = 4_000L)

        assertNotNull(approved)
        assertEquals(GatewayPairingRequestStatus.Approved, approved?.status)
        assertNotNull(approved?.bindingToken)
        assertTrue(registry.bindings().any { it.bridgeId == "claude-code" })
        assertTrue(registry.isValidBindingToken(approved?.bindingToken))

        val rejectedRequest = registry.createRequest(
            bridgeId = "codex",
            bridgeName = "Codex",
            sourceHost = "10.0.0.11",
            now = 5_000L
        )
        val rejected = registry.rejectRequest(rejectedRequest.id, now = 6_000L)

        assertNotNull(rejected)
        assertEquals(GatewayPairingRequestStatus.Rejected, rejected?.status)
        assertNull(rejected?.bindingToken)
        assertFalse(registry.bindings().any { it.bridgeId == "codex" })
    }

    @Test
    fun taskManagerAssignsEventIdsAndSupportsIncrementalReads() {
        val manager = GatewayTaskManager()
        manager.registerExecutor("test") { _, onEvent ->
            onEvent(GatewayEvent(type = "first", data = 1, timestamp = 100L))
            onEvent(GatewayEvent(type = "second", data = 2, timestamp = 200L))
            mapOf("done" to true)
        }

        val task = manager.createTask("test", emptyMap())
        waitForTerminalState(manager, task.id)

        val allEvents = manager.listTaskEvents(task.id)
        assertNotNull(allEvents)
        assertEquals(listOf(1L, 2L), allEvents!!.map { it.id })

        val incremental = manager.listTaskEvents(task.id, afterEventId = 1L)
        assertEquals(1, incremental!!.size)
        assertEquals("second", incremental.first().type)

        val sinceFiltered = manager.listTaskEvents(task.id, since = 150L)
        assertEquals(1, sinceFiltered!!.size)
        assertEquals(2L, sinceFiltered.first().id)
    }

    @Test
    fun cancelTaskDistinguishesNotFoundAndFinished() {
        val manager = GatewayTaskManager()
        manager.registerExecutor("quick") { _, _ -> "ok" }

        assertEquals(GatewayTaskCancelResult.NotFound, manager.cancelTask("missing"))

        val task = manager.createTask("quick", emptyMap())
        waitForTerminalState(manager, task.id)

        assertEquals(GatewayTaskCancelResult.AlreadyFinished, manager.cancelTask(task.id))
    }

    @Test
    fun deskAbsenceRuleRequiresHoldTimeAndHonorsCooldown() {
        val rule = GatewayAutomationRule(
            id = "auto_1",
            name = "Leave desk",
            trigger = GatewayAutomationTrigger(
                type = GatewayAutomationManager.TRIGGER_DESK_ABSENCE,
                params = mapOf("absenceHoldSeconds" to 60, "cooldownSeconds" to 300)
            ),
            delivery = GatewayAutomationDeliveryTarget(type = "desktop_bridge", targetId = "watcher-desktop")
        )
        val absent = MonitorStatus(isRunning = true, lastResult = CheckResult.ALERT, totalCheckCount = 5)

        val first = evaluateDeskAbsenceRule(rule, absent, now = 1_000L, nextEventId = 1L)
        assertTrue(first.emittedEvents.isEmpty())
        assertEquals(1_000L, first.updatedRule.lastConditionMatchedAt)

        val second = evaluateDeskAbsenceRule(first.updatedRule, absent, now = 60_500L, nextEventId = 2L)
        assertEquals(1, second.emittedEvents.size)
        assertEquals(60_500L, second.updatedRule.lastTriggeredAt)

        val coolingDown = evaluateDeskAbsenceRule(second.updatedRule, absent, now = 120_000L, nextEventId = 3L)
        assertTrue(coolingDown.emittedEvents.isEmpty())

        val reset = evaluateDeskAbsenceRule(
            second.updatedRule,
            MonitorStatus(isRunning = true, lastResult = CheckResult.NORMAL),
            now = 121_000L,
            nextEventId = 4L
        )
        assertNull(reset.updatedRule.lastConditionMatchedAt)
    }

    @Test
    fun healthCanIncludeGatewayDiagnostics() {
        val health = GatewayRoutes.health(
            hasFrame = false,
            agentConfigured = true,
            commentaryConfigured = false,
            streamManagementConfigured = true,
            gateway = mapOf(
                "running" to true,
                "listeningPort" to 8080,
                "localIp" to "10.0.0.5",
                "lastError" to null
            )
        )

        val gateway = health["gateway"] as Map<*, *>
        assertEquals(true, gateway["running"])
        assertEquals(8080, gateway["listeningPort"])
        assertEquals("10.0.0.5", gateway["localIp"])
    }

    private fun waitForTerminalState(manager: GatewayTaskManager, taskId: String) {
        repeat(50) {
            val status = manager.getTask(taskId)?.status
            if (status == GatewayTaskStatus.Completed ||
                status == GatewayTaskStatus.Failed ||
                status == GatewayTaskStatus.Cancelled
            ) {
                return
            }
            Thread.sleep(20L)
        }
        error("Task did not finish in time: $taskId")
    }
}
