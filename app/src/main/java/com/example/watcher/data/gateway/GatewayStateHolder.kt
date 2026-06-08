package com.example.watcher.data.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-level holder that bridges gateway state across Activities.
 * The single GatewayDelegate instance is assigned here by IntentViewModel,
 * enabling MultiDeviceViewModel (or any other consumer) to observe the same state.
 */
class GatewayStateHolder {
    internal interface Delegate {
        val running: StateFlow<Boolean>
        val status: StateFlow<GatewayRuntimeStatus>
        val pairingRequests: StateFlow<List<GatewayPairingRequest>>
        val pairingBindings: StateFlow<List<GatewayPairingRecord>>
        val relayConversations: StateFlow<List<GatewayRelayConversation>>
        val relayMessages: StateFlow<List<GatewayRelayMessage>>
        val apiKey: String
        val port: Int
        fun getLocalIpAddress(): String
        fun toggle(enabled: Boolean)
        fun approvePairingRequest(requestId: String)
        fun rejectPairingRequest(requestId: String)
        fun createLocalRelayConversation(agentBridgeId: String, title: String)
        fun sendPhoneRelayMessage(conversationId: String, content: String)
    }

    @Volatile
    internal var delegate: Delegate? = null

    val running: StateFlow<Boolean>
        get() = delegate?.running ?: MutableStateFlow(false)

    val status: StateFlow<GatewayRuntimeStatus>
        get() = delegate?.status ?: MutableStateFlow(GatewayRuntimeStatus())

    val pairingRequests: StateFlow<List<GatewayPairingRequest>>
        get() = delegate?.pairingRequests ?: MutableStateFlow(emptyList())

    val pairingBindings: StateFlow<List<GatewayPairingRecord>>
        get() = delegate?.pairingBindings ?: MutableStateFlow(emptyList())

    val relayConversations: StateFlow<List<GatewayRelayConversation>>
        get() = delegate?.relayConversations ?: MutableStateFlow(emptyList())

    val relayMessages: StateFlow<List<GatewayRelayMessage>>
        get() = delegate?.relayMessages ?: MutableStateFlow(emptyList())

    val apiKey: String
        get() = delegate?.apiKey ?: ""

    val port: Int
        get() = delegate?.port ?: GatewayServer.DEFAULT_PORT

    fun getLocalIpAddress(): String = delegate?.getLocalIpAddress() ?: "0.0.0.0"

    fun toggle(enabled: Boolean) {
        delegate?.toggle(enabled)
    }

    fun approvePairingRequest(requestId: String) {
        delegate?.approvePairingRequest(requestId)
    }

    fun rejectPairingRequest(requestId: String) {
        delegate?.rejectPairingRequest(requestId)
    }

    fun createLocalRelayConversation(agentBridgeId: String, title: String) {
        delegate?.createLocalRelayConversation(agentBridgeId, title)
    }

    fun sendPhoneRelayMessage(conversationId: String, content: String) {
        delegate?.sendPhoneRelayMessage(conversationId, content)
    }
}
