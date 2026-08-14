package com.example.watcher.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupMainContentPolicyTest {
    @Test
    fun startupVideoOverlayCreatesMainContentBeforeReveal() {
        assertTrue(
            StartupMainContentPolicy.shouldCreateMainContentBeforeReveal(
                hasStartupVideoOverlay = true
            )
        )
    }

    @Test
    fun missingStartupVideoOverlayDoesNotNeedHiddenWarmup() {
        assertFalse(
            StartupMainContentPolicy.shouldCreateMainContentBeforeReveal(
                hasStartupVideoOverlay = false
            )
        )
    }

    @Test
    fun blockingDialogsWaitUntilMainContentIsInteractive() {
        assertFalse(
            StartupMainContentPolicy.canShowBlockingDialogs(
                mainContentInteractive = false
            )
        )
        assertTrue(
            StartupMainContentPolicy.canShowBlockingDialogs(
                mainContentInteractive = true
            )
        )
    }
}
