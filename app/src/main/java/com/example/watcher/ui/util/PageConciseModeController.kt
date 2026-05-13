package com.example.watcher.ui.util

import com.example.watcher.ui.screens.HubPage

internal class PageConciseModeController(
    private val store: PageConciseModeStore
) {
    fun initialModes(): Map<HubPage, Boolean> {
        return store.loadModes()
    }

    fun isConciseMode(modes: Map<HubPage, Boolean>, page: HubPage): Boolean {
        return modes[page] ?: false
    }

    fun updateMode(
        currentModes: Map<HubPage, Boolean>,
        page: HubPage,
        enabled: Boolean
    ): Map<HubPage, Boolean> {
        store.setConciseMode(page, enabled)
        return currentModes + (page to enabled)
    }
}
