package com.example.watcher.data.gateway

import java.util.UUID

internal class GatewayPairingRegistry(
    initialRequests: List<GatewayPairingRequest> = emptyList(),
    initialBindings: List<GatewayPairingRecord> = emptyList(),
    private val requestTtlMillis: Long = DEFAULT_REQUEST_TTL_MILLIS,
    private val idFactory: () -> String = {
        "pair_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    },
    private val tokenFactory: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    }
) {
    private val requests = initialRequests.toMutableList()
    private val bindings = initialBindings.toMutableList()

    fun createRequest(
        bridgeId: String,
        bridgeName: String,
        sourceHost: String?,
        now: Long = System.currentTimeMillis()
    ): GatewayPairingRequest {
        expireRequests(now)
        val normalizedId = bridgeId.trim().ifBlank { "watcher-bridge" }
        val normalizedName = bridgeName.trim().ifBlank { normalizedId }
        val request = GatewayPairingRequest(
            id = idFactory(),
            bridgeId = normalizedId,
            bridgeName = normalizedName,
            sourceHost = sourceHost?.trim()?.ifBlank { null },
            createdAt = now,
            updatedAt = now,
            expiresAt = now + requestTtlMillis
        )
        requests += request
        return request
    }

    fun getRequest(id: String, now: Long = System.currentTimeMillis()): GatewayPairingRequest? {
        expireRequests(now)
        return requests.firstOrNull { it.id == id }
    }

    fun pendingRequests(now: Long = System.currentTimeMillis()): List<GatewayPairingRequest> {
        expireRequests(now)
        return requests
            .filter { it.status == GatewayPairingRequestStatus.Pending }
            .sortedByDescending { it.createdAt }
    }

    fun requests(now: Long = System.currentTimeMillis()): List<GatewayPairingRequest> {
        expireRequests(now)
        return requests.sortedByDescending { it.createdAt }
    }

    fun bindings(): List<GatewayPairingRecord> = bindings.sortedBy { it.bridgeId }

    fun pair(
        bridgeId: String,
        bridgeName: String,
        now: Long = System.currentTimeMillis()
    ): GatewayPairingRecord {
        val normalizedId = bridgeId.trim().ifBlank { "watcher-bridge" }
        val normalizedName = bridgeName.trim().ifBlank { normalizedId }
        val existing = bindings.firstOrNull { it.bridgeId == normalizedId }
        val record = if (existing != null) {
            existing.copy(bridgeName = normalizedName, lastSeenAt = now)
        } else {
            GatewayPairingRecord(
                bridgeId = normalizedId,
                bridgeName = normalizedName,
                bindingToken = tokenFactory(),
                createdAt = now,
                lastSeenAt = now
            )
        }
        bindings.removeAll { it.bridgeId == normalizedId }
        bindings += record
        return record
    }

    fun approveRequest(
        requestId: String,
        deviceId: String = "",
        now: Long = System.currentTimeMillis()
    ): GatewayPairingRequest? {
        expireRequests(now)
        val current = requests.firstOrNull { it.id == requestId }
            ?: return null
        if (current.status != GatewayPairingRequestStatus.Pending) return current

        val record = pair(current.bridgeId, current.bridgeName, now)
        val approved = current.copy(
            status = GatewayPairingRequestStatus.Approved,
            updatedAt = now,
            bindingToken = record.bindingToken,
            deviceId = deviceId.ifBlank { null }
        )
        replaceRequest(approved)
        return approved
    }

    fun rejectRequest(
        requestId: String,
        now: Long = System.currentTimeMillis()
    ): GatewayPairingRequest? {
        expireRequests(now)
        val current = requests.firstOrNull { it.id == requestId }
            ?: return null
        if (current.status != GatewayPairingRequestStatus.Pending) return current

        val rejected = current.copy(
            status = GatewayPairingRequestStatus.Rejected,
            updatedAt = now
        )
        replaceRequest(rejected)
        return rejected
    }

    fun isValidBindingToken(token: String?): Boolean {
        val normalized = token?.trim().orEmpty()
        if (normalized.isBlank()) return false
        return bindings.any { it.bindingToken == normalized }
    }

    private fun expireRequests(now: Long) {
        val updated = requests.map { request ->
            if (request.status == GatewayPairingRequestStatus.Pending && now > request.expiresAt) {
                request.copy(
                    status = GatewayPairingRequestStatus.Expired,
                    updatedAt = now
                )
            } else {
                request
            }
        }
        requests.clear()
        requests += updated
    }

    private fun replaceRequest(request: GatewayPairingRequest) {
        requests.removeAll { it.id == request.id }
        requests += request
    }

    companion object {
        const val DEFAULT_REQUEST_TTL_MILLIS = GATEWAY_PAIRING_REQUEST_TTL_MILLIS
    }
}
