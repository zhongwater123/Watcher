package com.example.watcher.data.intentrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTraceTest {
    @Test
    fun formatsTraceIdWithPrefixAndNumber() {
        assertEquals("intent-7", IntentRouterTrace.format(prefix = "intent", number = 7))
    }

    @Test
    fun nextReturnsReadableIntentRouterId() {
        val traceId = IntentRouterTrace.next()

        assertTrue(traceId.startsWith("intent-"))
        assertTrue(traceId.removePrefix("intent-").toLongOrNull() != null)
    }
}
