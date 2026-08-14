package com.example.watcher.ui.intentrouter

import com.example.watcher.data.intentrouter.IntentRouteId
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRouterUiStateTest {
    @Test
    fun exposesShortcutRoutesForAllV1MainPages() {
        assertEquals(
            listOf(
                IntentRouteId.Monitor,
                IntentRouteId.Home,
                IntentRouteId.Analysis,
                IntentRouteId.History,
                IntentRouteId.Templates
            ),
            IntentRouterUiState().shortcutRoutes.map { it.id }
        )
    }

    @Test
    fun exposesCuratedExamplePromptsForCommonWorkflows() {
        val state = IntentRouterUiState()

        assertEquals(2, state.examplePrompts.size)
        assertEquals(
            listOf(
                "帮我监控门口有没有人",
                "我想做课堂记录"
            ),
            state.examplePrompts
        )
    }
}
