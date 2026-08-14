package com.example.watcher.data.repository

import android.util.Log

internal object ClassroomRealtimeDiagnostics {
    const val ASR_TAG = "Watcher.Classroom.ASR"
    const val SPEECH_TAG = "Watcher.Classroom.Speech"
    const val AST_TAG = "Watcher.Classroom.AST"
    const val KNOWLEDGE_TREE_TAG = "Watcher.Classroom.KTree"

    fun asr(message: String) {
        Log.d(ASR_TAG, message)
    }

    fun asrWarning(message: String) {
        Log.w(ASR_TAG, message)
    }

    fun speech(message: String) {
        Log.d(SPEECH_TAG, message)
    }

    fun speechWarning(message: String) {
        Log.w(SPEECH_TAG, message)
    }

    fun ast(message: String) {
        Log.d(AST_TAG, message)
    }

    fun astWarning(message: String) {
        Log.w(AST_TAG, message)
    }

    fun knowledgeTree(message: String) {
        Log.d(KNOWLEDGE_TREE_TAG, message)
    }

    fun knowledgeTreeWarning(message: String) {
        Log.w(KNOWLEDGE_TREE_TAG, message)
    }

    fun knowledgeTreeChunked(kind: String, text: String) {
        if (text.isBlank()) return
        val chunks = text.chunked(LOG_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            Log.d(KNOWLEDGE_TREE_TAG, "$kind part=${index + 1}/${chunks.size} length=${text.length}\n$chunk")
        }
    }

    private const val LOG_CHUNK_SIZE = 1_800
}
