package com.example.watcher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watcher.ui.screens.LocalAgentScreen
import com.example.watcher.ui.theme.WatcherTheme
import com.example.watcher.ui.viewmodel.LocalAgentViewModel

class LocalAgentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatcherTheme {
                LocalAgentRoute(onClose = ::finish)
            }
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, LocalAgentActivity::class.java)
        }
    }
}

@Composable
private fun LocalAgentRoute(onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel: LocalAgentViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as Application
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LocalAgentScreen(
        uiState = uiState,
        onSendMessage = viewModel::sendMessage,
        onClose = onClose
    )
}
