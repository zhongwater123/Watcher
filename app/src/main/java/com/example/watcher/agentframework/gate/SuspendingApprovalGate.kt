package com.example.watcher.agentframework.gate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production implementation of HumanGate that suspends the agent coroutine
 * until a decision is submitted externally (via Gateway API).
 *
 * The gate maintains a registry of pending requests. When [requestApproval] is called,
 * the coroutine suspends on a CompletableDeferred. When [submitDecision] is called
 * from the Gateway handler, the deferred completes and the agent resumes.
 */
class SuspendingApprovalGate(
    private val defaultTimeoutMillis: Long = 300_000L  // 5 minutes default
) : HumanGate {

    private val mutex = Mutex()
    private val pendingRequests = linkedMapOf<String, PendingEntry>()

    private data class PendingEntry(
        val request: ApprovalRequest,
        val deferred: CompletableDeferred<ApprovalDecision>
    )

    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        mutex.withLock {
            pendingRequests[request.gateId] = PendingEntry(request, deferred)
        }

        val timeout = if (request.timeoutMillis > 0) request.timeoutMillis else defaultTimeoutMillis

        val decision = if (timeout > 0) {
            withTimeoutOrNull(timeout) { deferred.await() }
        } else {
            deferred.await()
        }

        // Clean up and handle timeout
        mutex.withLock {
            pendingRequests.remove(request.gateId)
        }

        return decision ?: ApprovalDecision(
            gateId = request.gateId,
            decision = ApprovalStatus.Expired,
            feedback = "Approval request timed out after ${timeout}ms",
            decidedBy = "system"
        )
    }

    override suspend fun pendingApprovals(runtimeId: String?): List<ApprovalRequest> {
        return mutex.withLock {
            pendingRequests.values
                .map { it.request }
                .filter { runtimeId == null || it.runtimeId == runtimeId }
        }
    }

    override suspend fun getApproval(gateId: String): ApprovalRequest? {
        return mutex.withLock {
            pendingRequests[gateId]?.request
        }
    }

    override suspend fun submitDecision(decision: ApprovalDecision): Boolean {
        val entry = mutex.withLock {
            pendingRequests[decision.gateId]
        } ?: return false

        // Complete the deferred — this resumes the suspended agent coroutine
        entry.deferred.complete(decision)
        return true
    }
}
