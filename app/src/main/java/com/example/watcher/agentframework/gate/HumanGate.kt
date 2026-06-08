package com.example.watcher.agentframework.gate

import java.util.UUID

/**
 * Risk level for actions requiring human approval.
 */
enum class RiskLevel {
    Low,
    Medium,
    High,
    Critical
}

/**
 * Status of an approval request.
 */
enum class ApprovalStatus {
    Pending,
    Approved,
    Rejected,
    Expired
}

/**
 * Context provided to the approver to make an informed decision.
 */
data class ApprovalContext(
    val goal: String,
    val currentStep: String,
    val pendingAction: String,
    val riskLevel: RiskLevel = RiskLevel.Medium,
    val toolName: String? = null,
    val toolArguments: Map<String, Any?> = emptyMap(),
    val reasoning: String = "",
    val agentOutputSoFar: List<String> = emptyList()
)

/**
 * A request for human approval submitted by the agent runtime.
 */
data class ApprovalRequest(
    val gateId: String = UUID.randomUUID().toString(),
    val runtimeId: String,
    val agentId: String,
    val agentName: String = "",
    val context: ApprovalContext,
    val status: ApprovalStatus = ApprovalStatus.Pending,
    val timeoutMillis: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
)

/**
 * Decision made by a human reviewer on an approval request.
 */
data class ApprovalDecision(
    val gateId: String,
    val decision: ApprovalStatus,
    val feedback: String = "",
    val decidedBy: String = "user",
    val decidedAt: Long = System.currentTimeMillis()
)

/**
 * Human Gate interface.
 *
 * Called by the agent runtime when a step requires human approval.
 * The runtime suspends execution until a decision is submitted externally
 * (typically via the Gateway HTTP API).
 */
interface HumanGate {
    /**
     * Submit an approval request and suspend until resolved.
     * Returns the decision once a human responds.
     */
    suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision

    /**
     * Query pending approval requests.
     * Called by the Gateway API to list items awaiting review.
     */
    suspend fun pendingApprovals(runtimeId: String? = null): List<ApprovalRequest>

    /**
     * Get a specific approval request by gate ID.
     */
    suspend fun getApproval(gateId: String): ApprovalRequest?

    /**
     * Submit a decision for a pending approval.
     * Called by the Gateway API when a human approves/rejects.
     * Returns true if the decision was applied (request was pending).
     */
    suspend fun submitDecision(decision: ApprovalDecision): Boolean
}

/**
 * A no-op gate that auto-approves everything. Used when human gating is disabled.
 */
class AutoApproveGate : HumanGate {
    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        return ApprovalDecision(
            gateId = request.gateId,
            decision = ApprovalStatus.Approved,
            feedback = "Auto-approved (gate disabled)",
            decidedBy = "system"
        )
    }

    override suspend fun pendingApprovals(runtimeId: String?): List<ApprovalRequest> = emptyList()

    override suspend fun getApproval(gateId: String): ApprovalRequest? = null

    override suspend fun submitDecision(decision: ApprovalDecision): Boolean = false
}
