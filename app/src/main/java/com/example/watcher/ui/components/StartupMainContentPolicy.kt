package com.example.watcher.ui.components

object StartupMainContentPolicy {
    fun shouldCreateMainContentBeforeReveal(hasStartupVideoOverlay: Boolean): Boolean {
        return hasStartupVideoOverlay
    }

    fun canShowBlockingDialogs(mainContentInteractive: Boolean): Boolean {
        return mainContentInteractive
    }
}
