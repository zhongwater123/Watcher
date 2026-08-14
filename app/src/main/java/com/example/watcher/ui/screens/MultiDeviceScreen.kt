package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
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
import com.example.watcher.data.gateway.NtfyConnectionState
import com.example.watcher.data.gateway.NtfyDebugEntry
import com.example.watcher.data.gateway.NtfyDebugStats
import com.example.watcher.data.gateway.NtfyRelayConfig
import com.example.watcher.ui.components.RoseFourLoader
import kotlinx.coroutines.launch
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
    ntfyConnectionState: NtfyConnectionState,
    ntfyDebugStats: NtfyDebugStats = NtfyDebugStats(),
    ntfyDebugLog: List<NtfyDebugEntry> = emptyList(),
    ntfyConfig: NtfyRelayConfig,
    phoneAvailable: Boolean,
    relayError: String? = null,
    initialConversationId: String? = null,
    onToggleGateway: (Boolean) -> Unit,
    onApprovePairingRequest: (String) -> Unit,
    onRejectPairingRequest: (String) -> Unit,
    onCreateLocalRelayConversation: (String, String) -> Unit,
    onSendPhoneRelayMessage: (String, String) -> Unit,
    onHandBack: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit = {},
    onSetPhoneAvailable: (Boolean) -> Unit,
    onUpdateNtfyConfig: (NtfyRelayConfig) -> Unit,
    onClearRelayError: () -> Unit = {},
    onClose: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("对话", "配置")
    val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }

    // Show relay errors as snackbar
    androidx.compose.runtime.LaunchedEffect(relayError) {
        if (relayError != null) {
            snackbarHostState.showSnackbar(relayError)
            onClearRelayError()
        }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
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
                conversations = relayConversations,
                messages = relayMessages,
                ntfyConnectionState = ntfyConnectionState,
                phoneAvailable = phoneAvailable,
                initialConversationId = initialConversationId,
                onHandBack = onHandBack,
                onDeleteConversation = onDeleteConversation,
                onCreateConversation = onCreateLocalRelayConversation,
                onSendMessage = onSendPhoneRelayMessage,
                onSetPhoneAvailable = onSetPhoneAvailable
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
                ntfyConfig = ntfyConfig,
                ntfyDebugStats = ntfyDebugStats,
                ntfyDebugLog = ntfyDebugLog,
                onToggleGateway = onToggleGateway,
                onApprovePairingRequest = onApprovePairingRequest,
                onRejectPairingRequest = onRejectPairingRequest,
                onUpdateNtfyConfig = onUpdateNtfyConfig
            )
        }
    }
}

@Composable
private fun RelayChatTab(
    modifier: Modifier,
    conversations: List<GatewayRelayConversation>,
    messages: List<GatewayRelayMessage>,
    ntfyConnectionState: NtfyConnectionState,
    phoneAvailable: Boolean,
    initialConversationId: String? = null,
    onCreateConversation: (String, String) -> Unit,
    onSendMessage: (String, String) -> Unit,
    onHandBack: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onSetPhoneAvailable: (Boolean) -> Unit
) {
    var selectedConversationId by rememberSaveable { mutableStateOf(initialConversationId) }
    androidx.compose.runtime.LaunchedEffect(initialConversationId) {
        if (initialConversationId != null) {
            selectedConversationId = initialConversationId
        }
    }
    var input by rememberSaveable { mutableStateOf("") }
    var contextExpanded by rememberSaveable { mutableStateOf(true) }
    val allConversations = conversations.sortedByDescending { it.updatedAt }
    val selectedConversation = allConversations.firstOrNull { it.id == selectedConversationId }
        ?: allConversations.firstOrNull()
    val visibleMessages = messages
        .filter { selectedConversation != null && it.conversationId == selectedConversation.id }
        .sortedBy { it.id }

    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed
    )
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val activeConversations = allConversations.filter { it.status != "handed_back" }
    val historyConversations = allConversations.filter { it.status == "handed_back" }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(modifier = Modifier.widthIn(max = 280.dp)) {
                Text(
                    "会话",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                if (activeConversations.isNotEmpty()) {
                    Text(
                        "活跃",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    activeConversations.forEach { conv ->
                        androidx.compose.material3.NavigationDrawerItem(
                            label = { Text(conv.title, maxLines = 1) },
                            badge = {
                                IconButton(onClick = { onDeleteConversation(conv.id) }) {
                                    Text("\u2715", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            selected = conv.id == selectedConversation?.id,
                            onClick = {
                                selectedConversationId = conv.id
                                coroutineScope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
                if (historyConversations.isNotEmpty()) {
                    Text(
                        "历史",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    historyConversations.forEach { conv ->
                        androidx.compose.material3.NavigationDrawerItem(
                            label = {
                                Text(
                                    conv.title,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            badge = {
                                IconButton(onClick = { onDeleteConversation(conv.id) }) {
                                    Text("\u2715", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            selected = conv.id == selectedConversation?.id,
                            onClick = {
                                selectedConversationId = conv.id
                                coroutineScope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ── Header bar ──
        var showHandBackDialog by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hamburger
            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, contentDescription = "会话列表")
            }

            // Status group: dot + text + switch (tightly coupled)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (phoneAvailable && ntfyConnectionState == NtfyConnectionState.Connected)
                        androidx.compose.ui.graphics.Color(0xFF0E8B65)
                    else if (phoneAvailable)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.outline,
                    modifier = Modifier.requiredSize(8.dp)
                ) {}
                Text(
                    if (!phoneAvailable) "离线"
                    else if (ntfyConnectionState == NtfyConnectionState.Connected) "在线"
                    else "连接中",
                    style = MaterialTheme.typography.labelMedium
                )
                androidx.compose.material3.Switch(
                    checked = phoneAvailable,
                    onCheckedChange = onSetPhoneAvailable,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = androidx.compose.ui.graphics.Color(0xFF0E8B65),
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.heightIn(max = 28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Context toggle (capsule with dynamic text + color)
            if (selectedConversation?.summary?.isNotBlank() == true) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { contextExpanded = !contextExpanded },
                    modifier = Modifier.heightIn(max = 32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (contextExpanded)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (contextExpanded)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        if (contextExpanded) "收起上下文" else "查看上下文",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Hand-back button (capsule, archive icon + text, only for active conversations)
            if (selectedConversation != null && selectedConversation.status != "handed_back") {
                Spacer(modifier = Modifier.padding(2.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { showHandBackDialog = true },
                    modifier = Modifier.heightIn(max = 32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Text("\u21A9", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text("交回", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (showHandBackDialog && selectedConversation != null) {
            HandBackDialog(
                onConfirm = { summary ->
                    showHandBackDialog = false
                    onHandBack(selectedConversation.id, summary)
                },
                onDismiss = { showHandBackDialog = false }
            )
        }

        // ── Disconnection warning (only when had conversations before) ──
        if (phoneAvailable && ntfyConnectionState == NtfyConnectionState.Disconnected && conversations.isNotEmpty()) {
            Text(
                "ntfy 连接断开，消息可能延迟",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (selectedConversation == null) {
            // ── Onboarding: always visible, tracks all steps ──
            OnboardingCard(
                isOnline = phoneAvailable,
                isConnected = phoneAvailable && ntfyConnectionState == NtfyConnectionState.Connected,
                hasConversation = false,
                modifier = Modifier.weight(1f).padding(16.dp)
            )
        } else {
            val isHandedBack = selectedConversation.status == "handed_back"

                // ── Collapsible context card ──
                // Context card: controlled by header button
                if (selectedConversation.summary.isNotBlank() && contextExpanded) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            selectedConversation.summary,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                // ── Message list (fills remaining space) ──
                val showLoading = !isHandedBack &&
                    visibleMessages.lastOrNull()?.author == "phone_user" &&
                    visibleMessages.lastOrNull()?.sendStatus == com.example.watcher.data.gateway.SendStatus.Confirmed
                MessageListCard(
                    messages = visibleMessages,
                    showAgentLoading = showLoading,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                // ── Bottom bar: input + actions ──
                if (isHandedBack) {
                    Text(
                        "已交回 PC",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    // Input bar only (hand-back moved to top bar)
                    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("输入消息") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .heightIn(max = 120.dp),
                        maxLines = 4,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val text = input.trim()
                                    if (text.isNotBlank()) {
                                        input = ""
                                        onSendMessage(selectedConversation.id, text)
                                        keyboardController?.hide()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "发送")
                            }
                        }
                    )
                }
            }
        } // Column
    } // ModalNavigationDrawer content
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
    ntfyConfig: NtfyRelayConfig,
    ntfyDebugStats: NtfyDebugStats,
    ntfyDebugLog: List<NtfyDebugEntry>,
    onToggleGateway: (Boolean) -> Unit,
    onApprovePairingRequest: (String) -> Unit,
    onRejectPairingRequest: (String) -> Unit,
    onUpdateNtfyConfig: (NtfyRelayConfig) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Section: ntfy 消息通道 ──
        Text(
            "ntfy 消息通道",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        NtfyConfigCard(
            config = ntfyConfig,
            onUpdateConfig = onUpdateNtfyConfig
        )
        NtfyDebugCard(
            stats = ntfyDebugStats,
            log = ntfyDebugLog
        )

        // ── Section: Gateway 本地服务 ──
        Text(
            "Gateway 本地服务",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
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
private fun NtfyDebugCard(
    stats: NtfyDebugStats,
    log: List<NtfyDebugEntry>
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("调试信息", style = MaterialTheme.typography.titleMedium)

            // Stats summary
            val stateLabel = when (stats.connectionState) {
                NtfyConnectionState.Connected -> "Connected"
                NtfyConnectionState.Connecting -> "Connecting..."
                NtfyConnectionState.Disconnected -> "Disconnected"
            }
            Text("连接: $stateLabel", style = MaterialTheme.typography.bodySmall)
            if (stats.lastHeartbeatAt > 0) {
                Text(
                    "上次心跳: ${timeFormat.format(Date(stats.lastHeartbeatAt))}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "发送: ${stats.publishCount}  接收: ${stats.receiveCount}  活跃会话: ${stats.activeConversationCount}",
                style = MaterialTheme.typography.bodySmall
            )
            if (stats.subscriptionStartedAt > 0) {
                val durationMin = (System.currentTimeMillis() - stats.subscriptionStartedAt) / 60000
                Text(
                    "订阅时长: ${durationMin} 分钟",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Expandable raw log
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起原始日志" else "展开原始日志 (${log.size})")
            }

            if (expanded && log.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    log.forEach { entry ->
                        val dir = if (entry.direction == "tx") "↑" else "↓"
                        val time = timeFormat.format(Date(entry.ts))
                        Text(
                            "$dir $time [${entry.type}] ${entry.author} #${entry.conversationId.takeLast(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (entry.contentPreview.isNotBlank()) {
                            Text(
                                "  ${entry.contentPreview}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun NtfyConfigCard(
    config: NtfyRelayConfig,
    onUpdateConfig: (NtfyRelayConfig) -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(config.serverUrl) }
    var topic by rememberSaveable { mutableStateOf(config.topic) }
    var authToken by rememberSaveable { mutableStateOf(config.authToken ?: "") }
    var enabled by rememberSaveable { mutableStateOf(config.enabled) }

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
            Text("ntfy 消息通道", style = MaterialTheme.typography.titleMedium)
            Text(
                "配置自建 ntfy 服务器地址，启用后聊天消息通过 ntfy 实时同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://ntfy.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic") },
                    placeholder = { Text("watcher-xxxxxxxx") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    readOnly = true
                )
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                Button(onClick = {
                    val configText = "topic: $topic\nserver: $serverUrl\ntoken: $authToken"
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(configText))
                }) {
                    Text("复制配置", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "首次使用时点击复制配置，粘贴给 PC Agent 即可，仅需一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = authToken,
                onValueChange = { authToken = it },
                label = { Text("Auth Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (authToken.isBlank()) {
                Text(
                    "\u26A0 未设置 Token，任何人可读写此 Topic。建议在 ntfy 服务端配置访问控制并填入 Token。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用 ntfy", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = { newEnabled ->
                        enabled = newEnabled
                        onUpdateConfig(
                            NtfyRelayConfig(
                                serverUrl = serverUrl.trim(),
                                topic = topic.trim().ifBlank { "shokz-watcher" },
                                authToken = authToken.trim().ifBlank { null },
                                enabled = newEnabled
                            )
                        )
                    }
                )
            }

            Button(
                onClick = {
                    onUpdateConfig(
                        NtfyRelayConfig(
                            serverUrl = serverUrl.trim(),
                            topic = topic.trim().ifBlank { "shokz-watcher" },
                            authToken = authToken.trim().ifBlank { null },
                            enabled = enabled
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存配置")
            }
        }
    }
}
@Composable
private fun MessageListCard(
    messages: List<GatewayRelayMessage>,
    showAgentLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(messages.size, showAgentLoading) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                Text(
                    "等待消息...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                messages.forEach { MessageBubble(it) }
            }
            if (showAgentLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        modifier = Modifier.widthIn(max = 120.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("PC Agent", style = MaterialTheme.typography.labelSmall)
                            RoseFourLoader(modifier = Modifier.requiredSize(56.dp))
                        }
                    }
                }
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
                        "phone_user" -> "我"
                        "pc_agent" -> "PC Agent"
                        else -> "系统"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatMultiDeviceTimestamp(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (message.sendStatus) {
                        com.example.watcher.data.gateway.SendStatus.Sending ->
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.padding(start = 2.dp).then(
                                    Modifier.requiredSize(12.dp)
                                ),
                                strokeWidth = 1.5.dp
                            )
                        com.example.watcher.data.gateway.SendStatus.Failed ->
                            Text("!", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        else -> {}
                    }
                }
            }
        }
    }
}
@Composable
private fun OnboardingCard(
    isOnline: Boolean,
    isConnected: Boolean,
    hasConversation: Boolean,
    modifier: Modifier = Modifier
) {
    data class Step(val label: String, val done: Boolean)
    val steps = listOf(
        Step("打开\u300C已上线\u300D开关", isOnline),
        Step("连接到 ntfy 消息服务器", isConnected),
        Step("在 PC 对话中告诉 Agent 你的需求，并指出它可以使用 Watcher MCP 工具", false),
        Step("Agent 将自动检测手机可用性并发起跨端会话", hasConversation)
    )

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("如何开始跨端对话", style = MaterialTheme.typography.titleMedium)
            Text(
                "完成以下步骤后，PC Agent 可随时向你发起会话接续",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            steps.forEachIndexed { index, step ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (step.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.requiredSize(28.dp)
                    ) {
                        androidx.compose.foundation.layout.Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                if (step.done) "\u2713" else "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (step.done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        step.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step.done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (step.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                }
            }
        }
    }
}

@Composable
private fun HandBackDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var summaryInput by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("交回 PC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "确认将对话控制权交回 PC Agent？可以附带一段总结。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = summaryInput,
                    onValueChange = { summaryInput = it },
                    label = { Text("总结（可选）") },
                    placeholder = { Text("手机端对话已结束") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(summaryInput.trim().ifBlank { "手机端对话已结束" })
            }) {
                Text("确认交回")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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
