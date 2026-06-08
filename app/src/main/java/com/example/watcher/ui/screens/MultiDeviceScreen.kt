package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.example.watcher.data.gateway.GatewayPairingRecord
import com.example.watcher.data.gateway.GatewayPairingRequest
import com.example.watcher.data.gateway.GatewayPairingRequestStatus
import com.example.watcher.data.gateway.GatewayRelayConversation
import com.example.watcher.data.gateway.GatewayRelayMessage
import com.example.watcher.data.gateway.GatewayRuntimeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiDeviceScreen(
    isGatewayRunning: Boolean,
    gatewayStatus: GatewayRuntimeStatus,
    pairingRequests: List<GatewayPairingRequest>,
    pairingBindings: List<GatewayPairingRecord>,
    relayConversations: List<GatewayRelayConversation>,
    relayMessages: List<GatewayRelayMessage>,
    gatewayPort: Int,
    gatewayApiKey: String,
    gatewayLocalIp: String,
    onToggleGateway: (Boolean) -> Unit,
    onApprovePairingRequest: (String) -> Unit,
    onRejectPairingRequest: (String) -> Unit,
    onCreateLocalRelayConversation: (String, String) -> Unit,
    onSendPhoneRelayMessage: (String, String) -> Unit,
    onClose: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("功能", "配置")

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("多端聚合") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> RelayChatTab(
                modifier = Modifier.padding(innerPadding),
                bindings = pairingBindings,
                conversations = relayConversations,
                messages = relayMessages,
                onCreateConversation = onCreateLocalRelayConversation,
                onSendMessage = onSendPhoneRelayMessage
            )
            else -> ConfigurationTab(
                modifier = Modifier.padding(innerPadding),
                isGatewayRunning = isGatewayRunning,
                gatewayStatus = gatewayStatus,
                pairingRequests = pairingRequests,
                pairingBindings = pairingBindings,
                gatewayPort = gatewayPort,
                gatewayApiKey = gatewayApiKey,
                gatewayLocalIp = gatewayLocalIp,
                onToggleGateway = onToggleGateway,
                onApprovePairingRequest = onApprovePairingRequest,
                onRejectPairingRequest = onRejectPairingRequest
            )
        }
    }
}

@Composable
private fun RelayChatTab(
    modifier: Modifier,
    bindings: List<GatewayPairingRecord>,
    conversations: List<GatewayRelayConversation>,
    messages: List<GatewayRelayMessage>,
    onCreateConversation: (String, String) -> Unit,
    onSendMessage: (String, String) -> Unit
) {
    var selectedBridgeId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    val selectedBinding = bindings.firstOrNull { it.bridgeId == selectedBridgeId } ?: bindings.firstOrNull()
    val agentConversations = conversations
        .filter { selectedBinding != null && it.agentBridgeId == selectedBinding.bridgeId }
        .sortedByDescending { it.updatedAt }
    val selectedConversation = agentConversations.firstOrNull { it.id == selectedConversationId }
        ?: agentConversations.firstOrNull()
    val visibleMessages = messages
        .filter { selectedConversation != null && it.conversationId == selectedConversation.id }
        .sortedBy { it.id }
    val hasUnseenPhoneMessage = visibleMessages.any {
        it.author == "phone_user" && it.seenByAgentAt == null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RelayHeaderCard(bindings.size, conversations.size)

        if (bindings.isEmpty()) {
            EmptyStateCard("暂无已连接 Agent", "先在 PC 端运行 watcher.bind_device，并在手机上允许首次绑定。")
        } else {
            AgentSelector(
                bindings = bindings,
                selectedBridgeId = selectedBinding?.bridgeId,
                onSelect = {
                    selectedBridgeId = it
                    selectedConversationId = null
                }
            )

            ConversationSelector(
                conversations = agentConversations,
                selectedConversationId = selectedConversation?.id,
                onSelect = { selectedConversationId = it },
                onCreate = {
                    selectedBinding?.let {
                        onCreateConversation(it.bridgeId, "手机接续会话")
                    }
                }
            )

            if (selectedConversation == null) {
                EmptyStateCard("暂无接续会话", "PC Agent 可注册当前工作会话；你也可以先从手机新建一个临时会话。")
            } else {
                if (hasUnseenPhoneMessage) {
                    StatusCard("等待 PC Agent 拉取消息", "PC 端调用 watcher.get_relay_messages 后会看到手机端发出的内容。")
                }
                MessageListCard(messages = visibleMessages, modifier = Modifier.weight(1f))
                RelayComposer(
                    value = input,
                    enabled = true,
                    onValueChange = { input = it },
                    onSend = {
                        val text = input.trim()
                        if (text.isNotBlank()) {
                            input = ""
                            onSendMessage(selectedConversation.id, text)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ConfigurationTab(
    modifier: Modifier,
    isGatewayRunning: Boolean,
    gatewayStatus: GatewayRuntimeStatus,
    pairingRequests: List<GatewayPairingRequest>,
    pairingBindings: List<GatewayPairingRecord>,
    gatewayPort: Int,
    gatewayApiKey: String,
    gatewayLocalIp: String,
    onToggleGateway: (Boolean) -> Unit,
    onApprovePairingRequest: (String) -> Unit,
    onRejectPairingRequest: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GatewaySettingsCard(
            isRunning = isGatewayRunning,
            status = gatewayStatus,
            port = gatewayPort,
            apiKey = gatewayApiKey,
            localIp = gatewayLocalIp,
            onToggle = onToggleGateway
        )
        PairingRequestsCard(
            requests = pairingRequests,
            onApprove = onApprovePairingRequest,
            onReject = onRejectPairingRequest
        )
        BoundAgentsCard(bindings = pairingBindings)
        McpUsageCard()
    }
}

@Composable
private fun RelayHeaderCard(agentCount: Int, conversationCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Agent Relay Chat", style = MaterialTheme.typography.titleMedium)
            Text(
                "已连接 $agentCount 个 Agent，$conversationCount 个接续会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AgentSelector(
    bindings: List<GatewayPairingRecord>,
    selectedBridgeId: String?,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择 Agent", style = MaterialTheme.typography.labelLarge)
        bindings.forEach { binding ->
            val selected = binding.bridgeId == selectedBridgeId
            if (selected) {
                Button(onClick = { onSelect(binding.bridgeId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(binding.bridgeName)
                }
            } else {
                TextButton(onClick = { onSelect(binding.bridgeId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${binding.bridgeName} · ${binding.bridgeId}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSelector(
    conversations: List<GatewayRelayConversation>,
    selectedConversationId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("接续会话", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onCreate) {
                Text("新建")
            }
        }
        conversations.forEach { conversation ->
            val selected = conversation.id == selectedConversationId
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                onClick = { onSelect(conversation.id) }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(conversation.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        conversation.summary.ifBlank { "暂无摘要" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "最近更新 ${formatMultiDeviceTimestamp(conversation.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageListCard(
    messages: List<GatewayRelayMessage>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                Text(
                    "还没有消息。手机发送后，PC Agent 可通过 MCP 拉取并回复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                messages.forEach { MessageBubble(it) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: GatewayRelayMessage) {
    val isPhoneUser = message.author == "phone_user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isPhoneUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (isPhoneUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when (message.author) {
                        "phone_user" -> "手机"
                        "pc_agent" -> "PC Agent"
                        else -> "系统"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatMultiDeviceTimestamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RelayComposer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 128.dp),
                placeholder = { Text("发给 PC Agent") },
                maxLines = 4
            )
            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(title: String, message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PairingRequestsCard(
    requests: List<GatewayPairingRequest>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    val pending = requests.filter { it.status == GatewayPairingRequestStatus.Pending }
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("首次连接确认", style = MaterialTheme.typography.titleMedium)
            if (pending.isEmpty()) {
                Text(
                    "暂无待确认的 PC Agent。电脑端运行 watcher.bind_device 后，可在这里允许连接。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                pending.forEach { request ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(request.bridgeName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "来源 ${request.sourceHost ?: "同一局域网"} · ${formatMultiDeviceTimestamp(request.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onApprove(request.id) }) {
                                Text("允许")
                            }
                            TextButton(onClick = { onReject(request.id) }) {
                                Text("拒绝")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoundAgentsCard(bindings: List<GatewayPairingRecord>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("已连接 Agent", style = MaterialTheme.typography.titleMedium)
            if (bindings.isEmpty()) {
                Text(
                    "暂无已绑定的 PC Agent。首次允许后，Claude Code、Codex 等 MCP 客户端会自动复用连接令牌。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                bindings.forEach { binding ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(binding.bridgeName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${binding.bridgeId} · 最近 ${formatMultiDeviceTimestamp(binding.lastSeenAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpUsageCard() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("PC 端 MCP 连接", style = MaterialTheme.typography.titleMedium)
            Text(
                "同一 Wi-Fi 下，PC Agent 可通过 D:\\watcher\\mcp 或 watcher-mcp 自动发现手机 Gateway，并在首次连接时等待手机确认。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Codex: codex mcp add watcher -- node D:\\watcher\\mcp\\server.js", style = MaterialTheme.typography.bodySmall)
            Text("Claude Code: claude mcp add --transport stdio watcher -- node D:\\watcher\\mcp\\server.js", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatMultiDeviceTimestamp(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
