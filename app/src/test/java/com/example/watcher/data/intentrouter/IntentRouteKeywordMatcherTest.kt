package com.example.watcher.data.intentrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouteKeywordMatcherTest {
    @Test
    fun matchesObviousMonitorRequestLocally() {
        val decision = IntentRouteKeywordMatcher.match("我想看监控了")

        assertEquals(IntentRouteId.Monitor, decision?.route?.id)
        assertEquals(IntentRouterDecisionSource.LocalKeyword, decision?.source)
        assertTrue((decision?.confidence ?: 0f) >= 0.7f)
    }

    @Test
    fun matchesHistoryRequestLocally() {
        val decision = IntentRouteKeywordMatcher.match("帮我找一下以前的历史记录")

        assertEquals(IntentRouteId.History, decision?.route?.id)
        assertEquals(IntentRouterDecisionSource.LocalKeyword, decision?.source)
    }

    @Test
    fun matchesClassroomRecordingRequestLocally() {
        val decision = IntentRouteKeywordMatcher.match("我想做课堂记录")

        assertEquals(IntentRouteId.Analysis, decision?.route?.id)
    }

    @Test
    fun matchesTemplateConfigurationRequestLocally() {
        val decision = IntentRouteKeywordMatcher.match("我要配置模型钱包")

        assertEquals(IntentRouteId.Templates, decision?.route?.id)
    }

    @Test
    fun matchesEnglishDashboardRequestLocally() {
        val decision = IntentRouteKeywordMatcher.match("open dashboard")

        assertEquals(IntentRouteId.Home, decision?.route?.id)
    }

    @Test
    fun usesKeywordsFromRouteCatalog() {
        val route = IntentRouteCatalog.findByWireId("templates")
        val keyword = route?.keywords?.firstOrNull { it == "统一配置" }
        assertNotNull(keyword)

        val decision = IntentRouteKeywordMatcher.match("我想处理$keyword")

        assertEquals(IntentRouteId.Templates, decision?.route?.id)
    }

    @Test
    fun returnsNullForAmbiguousOrEmptyRequest() {
        assertNull(IntentRouteKeywordMatcher.match(""))
        assertNull(IntentRouteKeywordMatcher.match("我想看看"))
    }
}
