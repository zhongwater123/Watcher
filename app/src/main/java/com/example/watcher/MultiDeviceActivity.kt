package com.example.watcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watcher.ui.screens.MultiDeviceScreen
import com.example.watcher.ui.theme.WatcherTheme
import com.example.watcher.ui.viewmodel.MultiDeviceViewModel

class MultiDeviceActivity : ComponentActivity() {
    private var targetConversationId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        targetConversationId = intent?.getStringExtra("conversationId")
        setContent {
            WatcherTheme {
                MultiDeviceRoute(
                    initialConversationId = targetConversationId,
                    onClose = ::finish
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("conversationId")?.let {
            targetConversationId = it
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, MultiDeviceActivity::class.java)
        }
    }
}

@Composable
private fun MultiDeviceRoute(initialConversationId: String? = null, onClose: () -> Unit) {
    val viewModel: MultiDeviceViewModel = viewModel()
    val gatewayRunning by viewModel.gatewayRunning.collectAsStateWithLifecycle()
    val gatewayStatus by viewModel.gatewayStatus.collectAsStateWithLifecycle()
    val pairingRequests by viewModel.pairingRequests.collectAsStateWithLifecycle()
    val pairingBindings by viewModel.pairingBindings.collectAsStateWithLifecycle()
    val relayConversations by viewModel.relayConversations.collectAsStateWithLifecycle()
    val relayMessages by viewModel.relayMessages.collectAsStateWithLifecycle()
    val ntfyConnectionState by viewModel.ntfyConnectionState.collectAsStateWithLifecycle()
    val ntfyDebugStats by viewModel.ntfyDebugStats.collectAsStateWithLifecycle()
    val ntfyDebugLog by viewModel.ntfyDebugLog.collectAsStateWithLifecycle()
    val relayError by viewModel.relayError.collectAsStateWithLifecycle()
    val phoneAvailable by viewModel.phoneAvailable.collectAsStateWithLifecycle()

    MultiDeviceScreen(
        isGatewayRunning = gatewayRunning,
        gatewayStatus = gatewayStatus,
        pairingRequests = pairingRequests,
        pairingBindings = pairingBindings,
        relayConversations = relayConversations,
        relayMessages = relayMessages,
        gatewayPort = viewModel.gatewayPort,
        gatewayApiKey = viewModel.gatewayApiKey,
        gatewayLocalIp = viewModel.getLocalIpAddress(),
        ntfyConnectionState = ntfyConnectionState,
        ntfyDebugStats = ntfyDebugStats,
        ntfyDebugLog = ntfyDebugLog,
        ntfyConfig = viewModel.getNtfyConfig(),
        phoneAvailable = phoneAvailable,
        relayError = relayError,
        initialConversationId = initialConversationId,
        onToggleGateway = viewModel::toggleGateway,
        onApprovePairingRequest = viewModel::approvePairingRequest,
        onRejectPairingRequest = viewModel::rejectPairingRequest,
        onCreateLocalRelayConversation = viewModel::createLocalRelayConversation,
        onSendPhoneRelayMessage = viewModel::sendPhoneRelayMessage,
        onHandBack = viewModel::handBackConversation,
        onDeleteConversation = viewModel::deleteConversation,
        onSetPhoneAvailable = viewModel::setPhoneAvailable,
        onUpdateNtfyConfig = viewModel::updateNtfyConfig,
        onClearRelayError = viewModel::clearRelayError,
        onClose = onClose
    )
}
