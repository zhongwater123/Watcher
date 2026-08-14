package com.example.watcher.data.intentrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterResponseParserTest {
    @Test
    fun parsesValidWhitelistedRoute() {
        val result = IntentRouterResponseParser.parse(
            """{"routeId":"history","confidence":0.91}"""
        )

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.History, success.decision.route.id)
        assertEquals(0.91f, success.decision.confidence, 0.0001f)
        assertEquals(IntentRouterDecisionSource.Llm, success.decision.source)
    }

    @Test
    fun parsesSnakeCaseRouteIdField() {
        val result = IntentRouterResponseParser.parse(
            """{"route_id":"home","confidence":0.82}"""
        )

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.Home, success.decision.route.id)
    }

    @Test
    fun parsesChineseRouteTitleInJson() {
        val result = IntentRouterResponseParser.parse(
            """{"route":"视频分析","confidence":0.88}"""
        )

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.Analysis, success.decision.route.id)
    }

    @Test
    fun parsesChineseKeywordInJson() {
        val result = IntentRouterResponseParser.parse(
            """{"route":"监控","confidence":0.87}"""
        )

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.Monitor, success.decision.route.id)
    }

    @Test
    fun parsesJsonInsideMarkdownFence() {
        val result = IntentRouterResponseParser.parse(
            """
            ```json
            {"routeId":"analysis","confidence":0.86}
            ```
            """.trimIndent()
        )

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.Analysis, success.decision.route.id)
    }

    @Test
    fun parsesLooseRouteIdOutput() {
        val result = IntentRouterResponseParser.parse("routeId: templates, confidence: 0.78")

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.Templates, success.decision.route.id)
        assertEquals(0.78f, success.decision.confidence, 0.0001f)
    }

    @Test
    fun parsesExactBareRouteIdOutput() {
        val result = IntentRouterResponseParser.parse("monitor")

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.Monitor, success.decision.route.id)
        assertTrue(success.decision.confidence >= 0.55f)
    }

    @Test
    fun parsesBareChineseRouteTitleOutput() {
        val result = IntentRouterResponseParser.parse("历史记录")

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.History, success.decision.route.id)
    }

    @Test
    fun parsesBareKeywordOutputUsingBestKeywordScore() {
        val result = IntentRouterResponseParser.parse("课堂记录")

        assertTrue(result is IntentRouterParseResult.Success)
        val success = result as IntentRouterParseResult.Success
        assertEquals(IntentRouteId.Analysis, success.decision.route.id)
    }

    @Test
    fun rejectsUnknownRoute() {
        val result = IntentRouterResponseParser.parse(
            """{"routeId":"wallet","confidence":0.94}"""
        )

        assertTrue(result is IntentRouterParseResult.Failure)
    }

    @Test
    fun rejectsEmptyAndNonJsonResponses() {
        assertTrue(IntentRouterResponseParser.parse("") is IntentRouterParseResult.Failure)
        assertTrue(IntentRouterResponseParser.parse("go somewhere") is IntentRouterParseResult.Failure)
    }

    @Test
    fun rejectsAmbiguousLooseRouteOutput() {
        val result = IntentRouterResponseParser.parse("monitor or history")

        assertTrue(result is IntentRouterParseResult.Failure)
    }

    @Test
    fun rejectsAmbiguousChineseTitleOutput() {
        val result = IntentRouterResponseParser.parse("实时监控 或 历史记录")

        assertTrue(result is IntentRouterParseResult.Failure)
    }

    @Test
    fun rejectsAmbiguousKeywordOutput() {
        val result = IntentRouterResponseParser.parse("报警 首页")

        assertTrue(result is IntentRouterParseResult.Failure)
    }

    @Test
    fun rejectsLowConfidenceRoute() {
        val result = IntentRouterResponseParser.parse(
            """{"routeId":"monitor","confidence":0.31}"""
        )

        assertTrue(result is IntentRouterParseResult.Failure)
    }
}
