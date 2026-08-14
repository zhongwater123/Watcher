package com.example.watcher.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.watcher.data.gateway.GatewayPairingRecord
import com.example.watcher.data.gateway.GatewayPairingRequest
import com.example.watcher.data.gateway.GatewayRelayConversation
import com.example.watcher.data.gateway.GatewayRelayMessage
import com.example.watcher.data.gateway.GatewayRuntimeStatus
import com.example.watcher.data.gateway.NtfyConnectionState
import com.example.watcher.data.gateway.NtfyDebugEntry
import com.example.watcher.data.gateway.NtfyDebugStats
import com.example.watcher.data.gateway.NtfyRelayConfig
import com.example.watcher.watcherApplication
import kotlinx.coroutines.flow.StateFlow

class MultiDeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val holder = application.watcherApplication().agentFrameworkContainer.gatewayStateHolder

    val gatewayRunning: StateFlow<Boolean> get() = holder.running
    val gatewayStatus: StateFlow<GatewayRuntimeStatus> get() = holder.status
    val pairingRequests: StateFlow<List<GatewayPairingRequest>> get() = holder.pairingRequests
    val pairingBindings: StateFlow<List<GatewayPairingRecord>> get() = holder.pairingBindings
    val relayConversations: StateFlow<List<GatewayRelayConversation>> get() = holder.relayConversations
    val relayMessages: StateFlow<List<GatewayRelayMessage>> get() = holder.relayMessages
    val ntfyConnectionState: StateFlow<NtfyConnectionState> get() = holder.ntfyConnectionState
    val ntfyDebugStats: StateFlow<NtfyDebugStats> get() = holder.ntfyDebugStats
    val ntfyDebugLog: StateFlow<List<NtfyDebugEntry>> get() = holder.ntfyDebugLog
    val relayError: StateFlow<String?> get() = holder.relayError
    val phoneAvailable: StateFlow<Boolean> get() = holder.phoneAvailable
    val gatewayApiKey: String get() = holder.apiKey
    val gatewayPort: Int get() = holder.port
    fun getLocalIpAddress(): String = holder.getLocalIpAddress()
    fun toggleGateway(enabled: Boolean) = holder.toggle(enabled)
    fun approvePairingRequest(requestId: String) = holder.approvePairingRequest(requestId)
    fun rejectPairingRequest(requestId: String) = holder.rejectPairingRequest(requestId)
    fun createLocalRelayConversation(agentBridgeId: String, title: String) =
        holder.createLocalRelayConversation(agentBridgeId, title)
    fun sendPhoneRelayMessage(conversationId: String, content: String) =
        holder.sendPhoneRelayMessage(conversationId, content)
    fun getNtfyConfig(): NtfyRelayConfig = holder.getNtfyConfig()
    fun updateNtfyConfig(config: NtfyRelayConfig) = holder.updateNtfyConfig(config)
    fun setPhoneAvailable(available: Boolean) = holder.setPhoneAvailable(available)
    fun handBackConversation(conversationId: String, summary: String) =
        holder.handBackConversation(conversationId, summary)
    fun deleteConversation(conversationId: String) = holder.deleteConversation(conversationId)
    fun clearRelayError() = holder.clearRelayError()
}
