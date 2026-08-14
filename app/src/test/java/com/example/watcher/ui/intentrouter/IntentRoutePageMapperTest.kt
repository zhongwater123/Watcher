package com.example.watcher.ui.intentrouter

import com.example.watcher.data.intentrouter.IntentRouteId
import com.example.watcher.ui.screens.HubPage
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRoutePageMapperTest {
    @Test
    fun mapsV1RoutesToMainPages() {
        assertEquals(HubPage.Monitor, IntentRouteId.Monitor.toHubPage())
        assertEquals(HubPage.Hub, IntentRouteId.Home.toHubPage())
        assertEquals(HubPage.Analysis, IntentRouteId.Analysis.toHubPage())
        assertEquals(HubPage.History, IntentRouteId.History.toHubPage())
        assertEquals(HubPage.Templates, IntentRouteId.Templates.toHubPage())
    }
}
