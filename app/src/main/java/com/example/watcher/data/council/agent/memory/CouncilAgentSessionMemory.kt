package com.example.watcher.data.council.agent.memory

/** Per-agent session memory. Volatile — cleared on session start. */
class CouncilAgentSessionMemory {

    private val store = mutableMapOf<String, MutableList<String>>()

    fun read(agentId: String, limit: Int = 10): List<String> =
        store[agentId]?.takeLast(limit) ?: emptyList()

    fun write(agentId: String, content: String) {
        val list = store.getOrPut(agentId) { mutableListOf() }
        list.add(content)
        while (list.size > MAX_ENTRIES) list.removeAt(0)
    }

    fun clear() = store.clear()

    fun clearAgent(agentId: String) = store.remove(agentId)

    fun allEntries(): Map<String, List<String>> = store.mapValues { it.value.toList() }

    companion object {
        private const val MAX_ENTRIES = 15
    }
}
