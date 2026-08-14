package com.example.watcher.ui.intentrouter

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.watcher.data.intentrouter.IntentRouteCatalog
import com.example.watcher.data.intentrouter.IntentRouteId
import com.example.watcher.data.intentrouter.IntentRouterLog
import com.example.watcher.data.intentrouter.IntentRouterRepository
import com.example.watcher.data.intentrouter.IntentRouterTrace
import com.example.watcher.data.remote.RetrofitClient
import com.example.watcher.watcherApplication
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class IntentRouterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = IntentRouterRepository(
        appContext = application,
        apiService = RetrofitClient.doubaoApiService,
        llmWalletRepository = application.watcherApplication().agentFrameworkContainer.llmWalletRepository
    )

    private val _uiState = MutableStateFlow(IntentRouterUiState())
    val uiState: StateFlow<IntentRouterUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<IntentRouterNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<IntentRouterNavigationEvent> = _navigationEvents.asSharedFlow()

    init {
        Log.d(
            IntentRouterLog.TAG,
            "viewModel init dialogVisible=${_uiState.value.visible}"
        )
    }

    fun showAutomaticallyAfterFirstFrameReady(trigger: String) {
        if (!QuickNavigationLaunchGate.shouldAutoShowAfterFirstFrameReady(trigger)) return
        Log.d(
            IntentRouterLog.TAG,
            "dialog auto show requested after first frame trigger=$trigger resetInput=true"
        )
        _uiState.update {
            it.copy(
                visible = true,
                input = "",
                errorMessage = null,
                selectedRoute = null
            )
        }
    }

    fun show() {
        Log.d(IntentRouterLog.TAG, "dialog show requested resetInput=true")
        _uiState.update {
            it.copy(
                visible = true,
                input = "",
                errorMessage = null,
                selectedRoute = null
            )
        }
    }

    fun dismiss() {
        Log.d(
            IntentRouterLog.TAG,
            "dialog dismiss selectedRoute=${_uiState.value.selectedRoute?.id?.wireId ?: "-"}"
        )
        _uiState.update { it.copy(visible = false, errorMessage = null) }
    }

    fun updateInput(value: String) {
        _uiState.update {
            it.copy(
                input = value,
                errorMessage = null,
                selectedRoute = null
            )
        }
    }

    fun selectExamplePrompt(prompt: String) {
        if (_uiState.value.isRouting) {
            Log.d(IntentRouterLog.TAG, "example ignored reason=already_routing")
            return
        }
        val request = prompt.trim()
        if (request.isBlank()) {
            Log.d(IntentRouterLog.TAG, "example ignored reason=blank_input")
            return
        }
        Log.d(
            IntentRouterLog.TAG,
            "example selected inputLength=${request.length} preview=\"${IntentRouterLog.preview(request)}\""
        )
        startRouting(
            request = request,
            traceId = IntentRouterTrace.next(prefix = "example")
        )
    }

    fun selectShortcut(routeId: IntentRouteId) {
        if (_uiState.value.isRouting) {
            Log.d(
                IntentRouterLog.TAG,
                "shortcut ignored reason=already_routing routeId=${routeId.wireId}"
            )
            return
        }
        val route = IntentRouteCatalog.routes.firstOrNull { it.id == routeId } ?: run {
            Log.w(IntentRouterLog.TAG, "shortcut ignored reason=unknown_route routeId=${routeId.wireId}")
            return
        }
        val traceId = IntentRouterTrace.next(prefix = "shortcut")
        Log.d(
            IntentRouterLog.TAG,
            "traceId=$traceId shortcut selected routeId=${route.id.wireId} title=\"${IntentRouterLog.preview(route.title)}\""
        )
        _uiState.update {
            it.copy(
                visible = true,
                isRouting = false,
                input = "",
                selectedRoute = route,
                errorMessage = null
            )
        }
        val emitted = _navigationEvents.tryEmit(
            IntentRouterNavigationEvent(
                routeId = route.id,
                traceId = traceId,
                sourceLabel = "Shortcut"
            )
        )
        Log.d(
            IntentRouterLog.TAG,
            "traceId=$traceId navigation event emitted=$emitted routeId=${route.id.wireId} source=Shortcut"
        )
    }

    fun submit() {
        val request = _uiState.value.input.trim()
        if (request.isBlank()) {
            Log.d(IntentRouterLog.TAG, "submit ignored reason=blank_input")
            _uiState.update {
                it.copy(
                    errorMessage = "先输入需求，或直接点下方入口。",
                    selectedRoute = null
                )
            }
            return
        }
        if (_uiState.value.isRouting) {
            Log.d(IntentRouterLog.TAG, "submit ignored reason=already_routing")
            return
        }

        startRouting(
            request = request,
            traceId = IntentRouterTrace.next()
        )
    }

    private fun startRouting(
        request: String,
        traceId: String
    ) {
        Log.d(
            IntentRouterLog.TAG,
            "traceId=$traceId submit start inputLength=${request.length} preview=\"${IntentRouterLog.preview(request)}\""
        )
        _uiState.update {
            it.copy(
                input = request,
                isRouting = true,
                errorMessage = null,
                selectedRoute = null
            )
        }
        viewModelScope.launch {
            repository.route(request, traceId = traceId)
                .onSuccess { decision ->
                    Log.d(
                        IntentRouterLog.TAG,
                        "traceId=$traceId submit success routeId=${decision.route.id.wireId} confidence=${decision.confidence} source=${decision.source}"
                    )
                    _uiState.update {
                        it.copy(
                            isRouting = false,
                            input = "",
                            selectedRoute = decision.route,
                            errorMessage = null,
                            visible = true
                        )
                    }
                    val emitted = _navigationEvents.tryEmit(
                        IntentRouterNavigationEvent(
                            routeId = decision.route.id,
                            traceId = traceId,
                            sourceLabel = decision.source.name
                        )
                    )
                    Log.d(
                        IntentRouterLog.TAG,
                        "traceId=$traceId navigation event emitted=$emitted routeId=${decision.route.id.wireId}"
                    )
                }
                .onFailure { error ->
                    Log.w(
                        IntentRouterLog.TAG,
                        "traceId=$traceId submit failed error=${error::class.java.simpleName}: ${error.message}",
                        error
                    )
                    _uiState.update {
                        it.copy(
                            isRouting = false,
                            errorMessage = "没识别清楚，请换个说法。",
                            selectedRoute = null
                        )
                    }
                }
        }
    }
}
