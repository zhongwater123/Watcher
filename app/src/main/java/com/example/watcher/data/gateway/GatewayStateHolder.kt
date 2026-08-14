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
        val ntfyConnectionState: StateFlow<NtfyConnectionState>
        val ntfyDebugStats: StateFlow<NtfyDebugStats>
        val ntfyDebugLog: StateFlow<List<NtfyDebugEntry>>
        val relayError: StateFlow<String?>
        val apiKey: String
        val port: Int
        fun getLocalIpAddress(): String
        fun toggle(enabled: Boolean)
        fun approvePairingRequest(requestId: String)
        fun rejectPairingRequest(requestId: String)
        fun createLocalRelayConversation(agentBridgeId: String, title: String)
        fun sendPhoneRelayMessage(conversationId: String, content: String)
        val phoneAvailable: StateFlow<Boolean>
        fun getNtfyConfig(): NtfyRelayConfig
        fun updateNtfyConfig(config: NtfyRelayConfig)
        fun setPhoneAvailable(available: Boolean)
        fun handBackConversation(conversationId: String, summary: String)
        fun deleteConversation(conversationId: String)
        fun clearRelayError()
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

    val ntfyConnectionState: StateFlow<NtfyConnectionState>
        get() = delegate?.ntfyConnectionState ?: MutableStateFlow(NtfyConnectionState.Disconnected)

    val ntfyDebugStats: StateFlow<NtfyDebugStats>
        get() = delegate?.ntfyDebugStats ?: MutableStateFlow(NtfyDebugStats())

    val ntfyDebugLog: StateFlow<List<NtfyDebugEntry>>
        get() = delegate?.ntfyDebugLog ?: MutableStateFlow(emptyList())

    val relayError: StateFlow<String?>
        get() = delegate?.relayError ?: MutableStateFlow(null)

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

    fun getNtfyConfig(): NtfyRelayConfig = delegate?.getNtfyConfig() ?: NtfyRelayConfig()

    val phoneAvailable: StateFlow<Boolean>
        get() = delegate?.phoneAvailable ?: MutableStateFlow(false)

    fun updateNtfyConfig(config: NtfyRelayConfig) {
        delegate?.updateNtfyConfig(config)
    }

    fun setPhoneAvailable(available: Boolean) {
        delegate?.setPhoneAvailable(available)
    }

    fun handBackConversation(conversationId: String, summary: String) {
        delegate?.handBackConversation(conversationId, summary)
    }

    fun deleteConversation(conversationId: String) {
        delegate?.deleteConversation(conversationId)
    }

    fun clearRelayError() {
        delegate?.clearRelayError()
    }
}
