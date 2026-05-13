package com.example.watcher.ui.util

import android.content.Context

internal class DigitalLifeConciseModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isConciseMode(): Boolean {
        return prefs.getBoolean(KEY_CONCISE_MODE, false)
    }

    fun setConciseMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONCISE_MODE, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "watcher_digital_life_display_mode"
        const val KEY_CONCISE_MODE = "digital_life_concise_mode"
    }
}
