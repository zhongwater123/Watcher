package com.example.watcher.agentframework.autonomy

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FileBackedStructuredMemoryStore(
    private val rootDir: File,
    private val gson: Gson = Gson(),
    private val onReadError: ((File, Exception) -> Unit)? = null
) : StructuredMemoryStore {
    private val mutex = Mutex()
    private val type = object : TypeToken<List<StructuredMemoryEntry>>() {}.type

    init {
        rootDir.mkdirs()
    }

    override suspend fun read(sessionId: String): List<StructuredMemoryEntry> {
        return mutex.withLock {
            val file = sessionFile(sessionId)
            if (!file.exists()) return@withLock emptyList()
            runCatching {
                gson.fromJson<List<StructuredMemoryEntry>>(file.readText(), type)
            }.onFailure { e -> onReadError?.invoke(file, e.asException()) }
                .getOrNull().orEmpty()
        }
    }

    override suspend fun write(sessionId: String, entries: List<StructuredMemoryEntry>) {
        mutex.withLock {
            val file = sessionFile(sessionId)
            if (entries.isEmpty()) {
                file.delete()
            } else {
                atomicWriteText(file, gson.toJson(entries, type))
            }
        }
    }

    override suspend fun clear(sessionId: String) {
        mutex.withLock {
            sessionFile(sessionId).delete()
        }
    }

    private fun sessionFile(sessionId: String): File = File(rootDir, "${safeId(sessionId)}.json")
}

class FileStructuredMemoryManager(
    rootDir: File,
    gson: Gson = Gson(),
    maxEntriesPerSession: Int = 400
) : DefaultStructuredMemoryManager(
    store = FileBackedStructuredMemoryStore(rootDir, gson),
    maxEntriesPerSession = maxEntriesPerSession
)

private fun atomicWriteText(target: File, content: String) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
        tmp.writeText(content)
        try {
            Files.move(tmp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        tmp.delete()
        throw IOException("Failed to write structured memory file: ${target.absolutePath}", e)
    }
}

private fun safeId(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

private fun Throwable.asException(): Exception {
    return this as? Exception ?: RuntimeException(message, this)
}
