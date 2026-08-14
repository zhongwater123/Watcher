package com.example.watcher

import android.app.Activity
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
import com.example.watcher.ui.screens.FitnessCompanionScreen
import com.example.watcher.ui.theme.WatcherTheme
import com.example.watcher.ui.viewmodel.FitnessCompanionViewModel

class FitnessCompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatcherTheme {
                FitnessCompanionRoute(onClose = ::finish)
            }
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        super.finish()
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, FitnessCompanionActivity::class.java)
        }
    }
}

@Composable
private fun FitnessCompanionRoute(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: FitnessCompanionViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as android.app.Application
        )
    )
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val draft = viewModel.draft.collectAsStateWithLifecycle().value
    FitnessCompanionScreen(
        uiState = uiState,
        draft = draft,
        viewModel = viewModel,
        onClose = onClose
    )
}
