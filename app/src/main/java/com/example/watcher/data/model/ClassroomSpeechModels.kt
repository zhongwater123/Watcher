package com.example.watcher.data.model

enum class ClassroomSpeechProvider(val value: String) {
    ASR("asr"),
    AST("ast");

    companion object {
        fun fromValue(value: String?): ClassroomSpeechProvider {
            return entries.firstOrNull { it.value == value } ?: ASR
        }
    }
}

data class ClassroomSpeechRecognitionConfig(
    val provider: ClassroomSpeechProvider = ClassroomSpeechProvider.ASR,
    val astPreset: String = AST_PRESET_ZH_EN_MIXED,
    val fallbackEnabled: Boolean = true
) {
    val astEnabled: Boolean
        get() = provider == ClassroomSpeechProvider.AST

    companion object {
        const val AST_PRESET_ZH_EN_MIXED = "zhen"
        val Default = ClassroomSpeechRecognitionConfig()
    }
}
