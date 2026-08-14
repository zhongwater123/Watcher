package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.watcher.data.repository.ProviderConnectivitySnapshot
import com.example.watcher.ui.viewmodel.ApiWalletDraft
import com.example.watcher.ui.viewmodel.ApiWalletUiState
import com.example.watcher.ui.viewmodel.AstConfigDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiWalletScreen(
    uiState: ApiWalletUiState,
    onBack: () -> Unit,
    onStartCreate: () -> Unit,
    onEditProvider: (String) -> Unit,
    onCancelEditing: () -> Unit,
    onDraftChange: ((ApiWalletDraft) -> ApiWalletDraft) -> Unit,
    onAsrDraftChange: ((com.example.watcher.ui.viewmodel.AsrConfigDraft) -> com.example.watcher.ui.viewmodel.AsrConfigDraft) -> Unit,
    onAstDraftChange: ((AstConfigDraft) -> AstConfigDraft) -> Unit,
    onSaveDraft: () -> Unit,
    onSaveAsrDraft: () -> Unit,
    onClearAsrConfig: () -> Unit,
    onTestAsrConfig: () -> Unit,
    onSaveAstDraft: () -> Unit,
    onClearAstConfig: () -> Unit,
    onTestAstConfig: () -> Unit,
    onTestProvider: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onClearMessage: () -> Unit,
    onShowImportDialog: () -> Unit = {},
    onDismissImportDialog: () -> Unit = {},
    onImportFromText: (String) -> Unit = {},
    onRequestExport: (String?) -> Unit = {},
    onConfirmExport: () -> String? = { null },
    onDismissExportWarning: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    if (uiState.showImportDialog) {
        WalletImportDialog(
            onDismiss = onDismissImportDialog,
            onImport = onImportFromText
        )
    }

    if (uiState.showExportWarning) {
        WalletExportWarningDialog(
            onConfirm = {
                val json = onConfirmExport()
                if (json != null) {
                    clipboardManager.setText(AnnotatedString(json))
                }
            },
            onDismiss = onDismissExportWarning
        )
    }

    LaunchedEffect(uiState.statusMessage, uiState.errorMessage) {
        val message = uiState.errorMessage ?: uiState.statusMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            onClearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("API 钱包") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onRequestExport(null) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "导出全部配置")
                    }
                    IconButton(onClick = onShowImportDialog) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "导入配置")
                    }
                    IconButton(onClick = onStartCreate) {
                        Icon(Icons.Default.Add, contentDescription = "新增供应商")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                WalletSummaryCard(uiState)
            }

            item {
                ProviderEditorSection(
                    uiState = uiState,
                    onStartCreate = onStartCreate,
                    onDraftChange = onDraftChange,
                    onSaveDraft = onSaveDraft,
                    onCancelEditing = onCancelEditing
                )
            }

            item {
                SectionTitle(
                    title = "已保存的供应商",
                    subtitle = "每张卡片都会显示使用足迹，方便你判断该供应商当前在哪些功能中生效、可选或回退。"
                )
            }

            if (uiState.providers.isEmpty()) {
                item {
                    EmptyWalletCard(uiState.arkFallbackAvailable)
                }
            } else {
                items(uiState.providers, key = { it.id }) { provider ->
                    ProviderItemCard(
                        provider = provider,
                        isDefault = provider.id == uiState.defaultProviderId,
                        isTesting = provider.id == uiState.testingProviderId,
                        connectivity = uiState.providerConnectivity[provider.id]
                            ?: ProviderConnectivitySnapshot(),
                        onEdit = { onEditProvider(provider.id) },
                        onTest = { onTestProvider(provider.id) },
                        onDelete = { onDeleteProvider(provider.id) },
                        onSetDefault = { onSetDefault(provider.id) },
                        onToggleEnabled = { enabled -> onToggleEnabled(provider.id, enabled) },
                        onExport = { onRequestExport(provider.id) }
                    )
                }
            }

            item {
                SectionTitle(
                    title = "语音识别（ASR）",
                    subtitle = "服务于 Live、Council 和视频分析中的语音输入，和上面的 LLM 供应商钱包分开管理。"
                )
            }

            item {
                AsrConfigCard(
                    state = uiState.asrConfig,
                    onDraftChange = onAsrDraftChange,
                    onSave = onSaveAsrDraft,
                    onClear = onClearAsrConfig,
                    onTest = onTestAsrConfig
                )
            }

            item {
                SectionTitle(
                    title = "复杂语种转写（AST）",
                    subtitle = "仅在一键录课中手动开启。AST 输出会进入同一套课堂字幕、知识树和快速问答链路。"
                )
            }

            item {
                AstConfigCard(
                    state = uiState.astConfig,
                    onDraftChange = onAstDraftChange,
                    onSave = onSaveAstDraft,
                    onClear = onClearAstConfig,
                    onTest = onTestAstConfig
                )
            }
        }
    }
}

@Composable
private fun ProviderEditorSection(
    uiState: ApiWalletUiState,
    onStartCreate: () -> Unit,
    onDraftChange: ((ApiWalletDraft) -> ApiWalletDraft) -> Unit,
    onSaveDraft: () -> Unit,
    onCancelEditing: () -> Unit
) {
    if (uiState.isEditing) {
        ProviderEditorCard(
            draft = uiState.draft,
            isSaving = uiState.isSaving,
            onDraftChange = onDraftChange,
            onSave = onSaveDraft,
            onCancel = onCancelEditing
        )
        return
    }

    FilledTonalButton(
        onClick = onStartCreate,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text("新增供应商", modifier = Modifier.padding(start = 8.dp))
    }
}
