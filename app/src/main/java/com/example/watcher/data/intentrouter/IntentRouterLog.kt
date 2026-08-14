package com.example.watcher.data.intentrouter

object IntentRouterLog {
    const val TAG = "IntentRouter"

    fun preview(text: String, maxLength: Int = 80): String {
        return text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .let { value ->
                if (value.length <= maxLength) {
                    value
                } else {
                    value.take(maxLength) + "..."
                }
            }
    }
}
