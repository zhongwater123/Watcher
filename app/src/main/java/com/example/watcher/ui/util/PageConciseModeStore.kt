package com.example.watcher.ui.util

import android.content.Context
import com.example.watcher.ui.screens.HubPage
import java.util.Locale

internal class PageConciseModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun loadModes(): Map<HubPage, Boolean> {
        return HubPage.entries.associateWith(::isConciseMode)
    }

    fun isConciseMode(page: HubPage): Boolean {
        val conciseKey = conciseKeyFor(page)
        if (prefs.contains(conciseKey)) {
            return prefs.getBoolean(conciseKey, false)
        }
        return prefs.getBoolean(legacyGuidedKeyFor(page), false)
    }

    fun setConciseMode(page: HubPage, enabled: Boolean) {
        prefs.edit().putBoolean(conciseKeyFor(page), enabled).apply()
    }

    private fun conciseKeyFor(page: HubPage): String {
        return CONCISE_KEY_PREFIX + page.name.lowercase(Locale.US)
    }

    private fun legacyGuidedKeyFor(page: HubPage): String {
        return LEGACY_GUIDED_KEY_PREFIX + page.name.lowercase(Locale.US)
    }

    private companion object {
        const val PREFS_NAME = "watcher_page_display_mode"
        const val CONCISE_KEY_PREFIX = "concise_mode_"
        const val LEGACY_GUIDED_KEY_PREFIX = "guided_mode_"
    }
}
