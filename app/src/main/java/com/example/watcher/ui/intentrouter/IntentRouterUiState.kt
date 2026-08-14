package com.example.watcher.ui.intentrouter

import com.example.watcher.data.intentrouter.IntentRouteDefinition
import com.example.watcher.data.intentrouter.IntentRouteCatalog
import com.example.watcher.data.intentrouter.IntentRouteId

data class IntentRouterUiState(
    val visible: Boolean = false,
    val input: String = "",
    val isRouting: Boolean = false,
    val selectedRoute: IntentRouteDefinition? = null,
    val errorMessage: String? = null,
    val shortcutRoutes: List<IntentRouteDefinition> = IntentRouteCatalog.routes,
    val examplePrompts: List<String> = defaultExamplePrompts()
)

private fun defaultExamplePrompts(): List<String> {
    val featuredRoutes = listOf(
        IntentRouteId.Monitor,
        IntentRouteId.Analysis
    )
    return featuredRoutes.mapNotNull { routeId ->
        IntentRouteCatalog.routes.firstOrNull { it.id == routeId }
            ?.examples
            ?.firstOrNull()
    }
}
