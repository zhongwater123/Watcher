package com.example.watcher.ui.intentrouter

import com.example.watcher.data.intentrouter.IntentRouteId
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRouterNavigationEventTest {
    @Test
    fun carriesRouteTraceAndSource() {
        val event = IntentRouterNavigationEvent(
            routeId = IntentRouteId.History,
            traceId = "intent-42",
            sourceLabel = "LLM"
        )

        assertEquals(IntentRouteId.History, event.routeId)
        assertEquals("intent-42", event.traceId)
        assertEquals("LLM", event.sourceLabel)
    }
}
