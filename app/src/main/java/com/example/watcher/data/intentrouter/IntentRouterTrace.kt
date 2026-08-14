package com.example.watcher.data.intentrouter

import java.util.concurrent.atomic.AtomicLong

object IntentRouterTrace {
    private val sequence = AtomicLong(0)

    fun next(prefix: String = "intent"): String {
        return format(prefix = prefix, number = sequence.incrementAndGet())
    }

    fun format(prefix: String, number: Long): String {
        return "$prefix-$number"
    }
}
