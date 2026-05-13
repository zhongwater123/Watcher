package com.example.watcher.agentframework.autonomy

import com.example.watcher.agentframework.core.AgentDecision
import com.example.watcher.agentframework.core.AgentMemoryScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface StructuredMemoryStore {
    suspend fun read(sessionId: String): List<StructuredMemoryEntry>
    suspend fun write(sessionId: String, entries: List<StructuredMemoryEntry>)
    suspend fun clear(sessionId: String)
}

class InMemoryStructuredMemoryStore : StructuredMemoryStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, List<StructuredMemoryEntry>>()

    override suspend fun read(sessionId: String): List<StructuredMemoryEntry> {
        return mutex.withLock {
            store[sessionId].orEmpty()
        }
    }

    override suspend fun write(sessionId: String, entries: List<StructuredMemoryEntry>) {
        mutex.withLock {
            if (entries.isEmpty()) {
                store.remove(sessionId)
            } else {
                store[sessionId] = entries.toList()
            }
        }
    }

    override suspend fun clear(sessionId: String) {
        mutex.withLock {
            store.remove(sessionId)
        }
    }
}

interface StructuredMemoryManagerFactory {
    fun create(store: StructuredMemoryStore): StructuredMemoryManager
}

class DefaultStructuredMemoryManagerFactory(
    private val maxEntriesPerSession: Int = 400
) : StructuredMemoryManagerFactory {
    override fun create(store: StructuredMemoryStore): StructuredMemoryManager {
        return DefaultStructuredMemoryManager(
            store = store,
            maxEntriesPerSession = maxEntriesPerSession
        )
    }
}

open class DefaultStructuredMemoryManager(
    private val store: StructuredMemoryStore,
    private val maxEntriesPerSession: Int = 400
) : StructuredMemoryManager {
    private val mutex = Mutex()

    override suspend fun snapshot(sessionId: String): StructuredMemorySnapshot {
        return mutex.withLock {
            store.read(sessionId).toSnapshot()
        }
    }

    override suspend fun onPerception(sessionId: String, perception: PerceptionFrame) {
        append(
            sessionId,
            perception.cleanedSignals.map {
                StructuredMemoryEntry(
                    scope = StructuredMemoryScope.ShortTerm,
                    content = it.content,
                    metadata = mapOf("channel" to it.channel.name)
                )
            }
        )
    }

    override suspend fun onDecision(sessionId: String, decision: AgentDecision) {
        val entries = buildList {
            if (decision.thinking.isNotBlank()) {
                add(
                    StructuredMemoryEntry(
                        scope = StructuredMemoryScope.Working,
                        content = decision.thinking,
                        metadata = mapOf("kind" to "thinking")
                    )
                )
            }
            if (!decision.reply.isNullOrBlank()) {
                add(
                    StructuredMemoryEntry(
                        scope = StructuredMemoryScope.ShortTerm,
                        content = decision.reply,
                        metadata = mapOf("kind" to "reply")
                    )
                )
            }
            decision.memoryWrites.forEach { write ->
                add(
                    StructuredMemoryEntry(
                        scope = when (write.scope) {
                            AgentMemoryScope.Working -> StructuredMemoryScope.Working
                            AgentMemoryScope.Episodic -> StructuredMemoryScope.LongTerm
                        },
                        content = write.content,
                        metadata = write.tags.associateWith { "true" }
                    )
                )
            }
        }
        append(sessionId, entries)
    }

    override suspend fun onFeedback(
        sessionId: String,
        outcome: ExecutionOutcome,
        validation: ValidationOutcome
    ) {
        append(
            sessionId,
            listOf(
                StructuredMemoryEntry(
                    scope = StructuredMemoryScope.Working,
                    content = validation.feedback,
                    metadata = buildMap {
                        put("status", validation.status.name)
                        outcome.error?.let { put("error", it) }
                    }
                )
            )
        )
    }

    override suspend fun onLearning(sessionId: String, lesson: StructuredMemoryEntry) {
        append(sessionId, listOf(lesson.copy(scope = StructuredMemoryScope.LongTerm)))
    }

    override suspend fun onCorrection(sessionId: String, record: CorrectionRecord) {
        mutex.withLock {
            val current = store.read(sessionId)
            val correctionEntry = StructuredMemoryEntry(
                scope = StructuredMemoryScope.Working,
                content = "Correction ${record.action}: ${record.reason}",
                metadata = mapOf(
                    "kind" to "correction",
                    "trigger" to record.trigger.name,
                    "signature" to record.failureSignature
                )
            )
            val nonCorrection = current.filterNot {
                it.scope == StructuredMemoryScope.Working && it.metadata["kind"] == "correction"
            }
            val recentCorrection = current.filter {
                it.scope == StructuredMemoryScope.Working && it.metadata["kind"] == "correction"
            }.takeLast(2)
            store.write(
                sessionId,
                (nonCorrection + recentCorrection + correctionEntry).takeLast(maxEntriesPerSession)
            )
        }
    }

    override suspend fun clear(sessionId: String) {
        mutex.withLock {
            store.clear(sessionId)
        }
    }

    private suspend fun append(sessionId: String, entries: List<StructuredMemoryEntry>) {
        if (entries.isEmpty()) return
        mutex.withLock {
            val updated = (store.read(sessionId) + entries)
                .takeLast(maxEntriesPerSession)
            store.write(sessionId, updated)
        }
    }
}

class InMemoryStructuredMemoryManager(
    maxEntriesPerSession: Int = 400
) : DefaultStructuredMemoryManager(
    store = InMemoryStructuredMemoryStore(),
    maxEntriesPerSession = maxEntriesPerSession
)

private fun List<StructuredMemoryEntry>.toSnapshot(): StructuredMemorySnapshot {
    return StructuredMemorySnapshot(
        shortTerm = filter { it.scope == StructuredMemoryScope.ShortTerm }.takeLast(20),
        working = filter { it.scope == StructuredMemoryScope.Working }.takeLast(20),
        longTerm = filter { it.scope == StructuredMemoryScope.LongTerm }.takeLast(50)
    )
}
