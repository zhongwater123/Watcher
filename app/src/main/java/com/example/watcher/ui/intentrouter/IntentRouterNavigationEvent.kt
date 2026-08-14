package com.example.watcher.ui.intentrouter

import com.example.watcher.data.intentrouter.IntentRouteId

data class IntentRouterNavigationEvent(
    val routeId: IntentRouteId,
    val traceId: String,
    val sourceLabel: String
)
