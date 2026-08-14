package com.example.watcher.ui.intentrouter

import android.util.Log
import com.example.watcher.data.intentrouter.IntentRouterLog
import java.util.concurrent.atomic.AtomicBoolean

object QuickNavigationLaunchGate {
    private val hasAutoShownInProcess = AtomicBoolean(false)

    fun shouldAutoShowAfterFirstFrameReady(trigger: String): Boolean {
        val shouldShow = !hasAutoShownInProcess.getAndSet(true)
        Log.d(
            IntentRouterLog.TAG,
            "launchGate firstFrameAutoShow=$shouldShow trigger=$trigger"
        )
        return shouldShow
    }
}
