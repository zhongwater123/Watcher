package com.example.watcher.ui.intentrouter

import com.example.watcher.data.intentrouter.IntentRouteId
import com.example.watcher.ui.screens.HubPage

internal fun IntentRouteId.toHubPage(): HubPage {
    return when (this) {
        IntentRouteId.Monitor -> HubPage.Monitor
        IntentRouteId.Home -> HubPage.Hub
        IntentRouteId.Analysis -> HubPage.Analysis
        IntentRouteId.History -> HubPage.History
        IntentRouteId.Templates -> HubPage.Templates
    }
}
