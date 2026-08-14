package com.example.watcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watcher.ui.screens.ApiWalletScreen
import com.example.watcher.ui.theme.WatcherTheme
import com.example.watcher.ui.viewmodel.ApiWalletViewModel

class ApiWalletActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatcherTheme {
                ApiWalletRoute(onClose = ::finish)
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, ApiWalletActivity::class.java)
        }
    }
}

@Composable
private fun ApiWalletRoute(
    onClose: () -> Unit,
    viewModel: ApiWalletViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    ApiWalletScreen(
        uiState = uiState,
        onBack = onClose,
        onStartCreate = viewModel::startCreate,
        onEditProvider = viewModel::startEdit,
        onCancelEditing = viewModel::cancelEditing,
        onDraftChange = viewModel::updateDraft,
        onAsrDraftChange = viewModel::updateAsrDraft,
        onAstDraftChange = viewModel::updateAstDraft,
        onSaveDraft = viewModel::saveDraft,
        onSaveAsrDraft = viewModel::saveAsrDraft,
        onClearAsrConfig = viewModel::clearAsrConfig,
        onTestAsrConfig = viewModel::testAsrConfig,
        onSaveAstDraft = viewModel::saveAstDraft,
        onClearAstConfig = viewModel::clearAstConfig,
        onTestAstConfig = viewModel::testAstConfig,
        onTestProvider = viewModel::testProvider,
        onSetDefault = viewModel::setDefaultProvider,
        onToggleEnabled = viewModel::setProviderEnabled,
        onDeleteProvider = viewModel::deleteProvider,
        onClearMessage = viewModel::clearMessage,
        onShowImportDialog = viewModel::showImportDialog,
        onDismissImportDialog = viewModel::dismissImportDialog,
        onImportFromText = viewModel::importFromText,
        onRequestExport = viewModel::requestExportProvider,
        onConfirmExport = viewModel::confirmExport,
        onDismissExportWarning = viewModel::dismissExportWarning
    )
}
