package com.example.watcher.data.intentrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouteCatalogTest {
    @Test
    fun containsOnlyV1MainPageRoutes() {
        assertEquals(
            listOf(
                IntentRouteId.Monitor,
                IntentRouteId.Home,
                IntentRouteId.Analysis,
                IntentRouteId.History,
                IntentRouteId.Templates
            ),
            IntentRouteCatalog.routes.map { it.id }
        )
    }

    @Test
    fun everyRouteHasUserGuidance() {
        IntentRouteCatalog.routes.forEach { route ->
            assertTrue(route.title.isNotBlank())
            assertTrue(route.description.isNotBlank())
            assertTrue(route.guidance.isNotBlank())
            assertTrue(route.examples.isNotEmpty())
            assertTrue(route.keywords.isNotEmpty())
            route.examples.forEach { example ->
                assertTrue(example.isNotBlank())
            }
            route.keywords.forEach { keyword ->
                assertTrue(keyword.isNotBlank())
            }
        }
    }

    @Test
    fun findsRouteByWireId() {
        assertEquals(IntentRouteId.Analysis, IntentRouteCatalog.findByWireId("analysis")?.id)
        assertNotNull(IntentRouteCatalog.findByWireId(" templates "))
    }

    @Test
    fun findsRouteByWireIdOrTitle() {
        assertEquals(IntentRouteId.History, IntentRouteCatalog.findByWireIdOrTitle("历史记录")?.id)
        assertEquals(IntentRouteId.Monitor, IntentRouteCatalog.findByWireIdOrTitle(" monitor ")?.id)
    }
}
