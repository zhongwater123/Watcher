package com.example.watcher.agentframework.service

import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.autonomy.AgentSignal
import com.example.watcher.agentframework.autonomy.AutonomousAgentEvent
import com.example.watcher.agentframework.autonomy.AutonomousAgentSnapshot
import com.example.watcher.agentframework.autonomy.AutonomousLifecycleState
import com.example.watcher.agentframework.autonomy.AutonomousStopReason
import com.example.watcher.agentframework.autonomy.CorrectionAction
import com.example.watcher.agentframework.autonomy.CorrectionRecord
import com.example.watcher.agentframework.autonomy.CorrectionTrigger
import com.example.watcher.agentframework.autonomy.PerceptionFrame
import com.example.watcher.agentframework.autonomy.ResolvedGoal
import com.example.watcher.agentframework.autonomy.SignalChannel
import com.example.watcher.agentframework.autonomy.TaskPlan
import com.example.watcher.agentframework.autonomy.ValidationOutcome
import com.example.watcher.agentframework.autonomy.ValidationStatus
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentMessageRole
import com.example.watcher.agentframework.core.AgentSessionSnapshot
import com.example.watcher.agentframework.core.AgentSessionStatus
import com.example.watcher.agentframework.core.AgentStopReason
import com.example.watcher.agentframework.knowledge.AgentKnowledgeEntry
import com.example.watcher.agentframework.knowledge.AgentKnowledgeStore
import com.example.watcher.agentframework.memory.AgentMemoryEntry
import com.example.watcher.agentframework.memory.AgentMemoryStore
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

class FileBackedAgentProfileStore(
    private val rootDir: File,
    private val gson: Gson = Gson(),
    private val onReadError: ((File, Exception) -> Unit)? = null
) : AgentProfileStore {
    private val mutex = Mutex()

    init {
        rootDir.mkdirs()
    }

    override suspend fun upsert(profile: RegisteredAgentProfile) {
        mutex.withLock {
            atomicWriteText(profileFile(profile.agentId), gson.toJson(profile))
        }
    }

    override suspend fun get(agentId: String): RegisteredAgentProfile? {
        return mutex.withLock {
            val file = profileFile(agentId)
            if (!file.exists()) return@withLock null
            gson.fromJson(file.readText(), RegisteredAgentProfile::class.java)?.normalize()
        }
    }

    override suspend fun list(): List<RegisteredAgentProfile> {
        return mutex.withLock {
            rootDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "json" }
                .mapNotNull { file ->
                    runCatching {
                        gson.fromJson(file.readText(), RegisteredAgentProfile::class.java)?.normalize()
                    }.onFailure { e -> onReadError?.invoke(file, e.asException()) }
                        .getOrNull()
                }
        }
    }

    override suspend fun remove(agentId: String) {
        mutex.withLock {
            profileFile(agentId).delete()
        }
    }

    private fun profileFile(agentId: String): File = File(rootDir, "${safeId(agentId)}.json")
}

class FileBackedAgentMemoryStore(
    private val rootDir: File,
    private val gson: Gson = Gson(),
    private val onReadError: ((File, Exception) -> Unit)? = null
) : AgentMemoryStore {
    private val mutex = Mutex()
    private val type = object : TypeToken<List<AgentMemoryEntry>>() {}.type

    init {
        rootDir.mkdirs()
    }

    override suspend fun write(sessionId: String, entry: AgentMemoryEntry) {
        mutex.withLock {
            val current = readEntries(sessionId).toMutableList()
            current += entry
            atomicWriteText(sessionFile(sessionId), gson.toJson(current, type))
        }
    }

    override suspend fun read(
        sessionId: String,
        scope: AgentMemoryScope?,
        limit: Int
    ): List<AgentMemoryEntry> {
        return mutex.withLock {
            val entries = readEntries(sessionId)
            val filtered = if (scope == null) entries else entries.filter { it.scope == scope }
            filtered.takeLast(limit)
        }
    }

    override suspend fun clear(sessionId: String) {
        mutex.withLock {
            sessionFile(sessionId).delete()
        }
    }

    private fun readEntries(sessionId: String): List<AgentMemoryEntry> {
        val file = sessionFile(sessionId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<AgentMemoryEntry>>(file.readText(), type)
        }.onFailure { e -> onReadError?.invoke(file, e.asException()) }
            .getOrNull().orEmpty()
    }

    private fun sessionFile(sessionId: String): File = File(rootDir, "${safeId(sessionId)}.json")
}

class FileBackedAgentKnowledgeStore(
    private val rootDir: File,
    private val gson: Gson = Gson(),
    private val onReadError: ((File, Exception) -> Unit)? = null
) : AgentKnowledgeStore {
    private val mutex = Mutex()
    private val type = object : TypeToken<List<AgentKnowledgeEntry>>() {}.type

    init {
        rootDir.mkdirs()
    }

    override suspend fun write(agentId: String, entry: AgentKnowledgeEntry) {
        mutex.withLock {
            val current = readEntries(agentId).toMutableList()
            current += entry
            atomicWriteText(agentFile(agentId), gson.toJson(current, type))
        }
    }

    override suspend fun read(agentId: String, limit: Int): List<AgentKnowledgeEntry> {
        return mutex.withLock {
            readEntries(agentId).takeLast(limit)
        }
    }

    override suspend fun query(
        agentId: String,
        query: String,
        tags: Set<String>,
        limit: Int
    ): List<AgentKnowledgeEntry> {
        val normalized = query.trim().lowercase()
        return mutex.withLock {
            readEntries(agentId)
                .asSequence()
                .filter { entry ->
                    val tagMatch = tags.isEmpty() || tags.all { it in entry.tags }
                    val queryMatch = normalized.isBlank() ||
                        entry.content.lowercase().contains(normalized) ||
                        entry.metadata.values.any { it.lowercase().contains(normalized) }
                    tagMatch && queryMatch
                }
                .take(limit)
                .toList()
        }
    }

    override suspend fun remove(agentId: String, entryId: String): Boolean {
        return mutex.withLock {
            val current = readEntries(agentId)
            if (current.isEmpty()) return@withLock false
            val remaining = current.filterNot { it.entryId == entryId }
            if (remaining.size == current.size) return@withLock false
            if (remaining.isEmpty()) {
                agentFile(agentId).delete()
            } else {
                atomicWriteText(agentFile(agentId), gson.toJson(remaining, type))
            }
            true
        }
    }

    override suspend fun clear(agentId: String) {
        mutex.withLock {
            agentFile(agentId).delete()
        }
    }

    private fun readEntries(agentId: String): List<AgentKnowledgeEntry> {
        val file = agentFile(agentId)
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<AgentKnowledgeEntry>>(file.readText(), type)
        }.onFailure { e -> onReadError?.invoke(file, e.asException()) }
            .getOrNull().orEmpty()
    }

    private fun agentFile(agentId: String): File = File(rootDir, "${safeId(agentId)}.json")
}

class FileBackedAgentInvocationStore(
    private val rootDir: File,
    private val gson: Gson = Gson(),
    private val onReadError: ((File, Exception) -> Unit)? = null
) : AgentInvocationStore {
    private val mutex = Mutex()
    private val type = object : TypeToken<List<PersistedInvocationRecord>>() {}.type

    init {
        rootDir.mkdirs()
    }

    override suspend fun upsert(record: AgentInvocationRecord) {
        mutex.withLock {
            val current = readAll().associateBy { it.invocationId }.toMutableMap()
            current[record.invocationId] = record.toPersisted()
            writeAll(current.values.sortedBy { it.createdAt })
        }
    }

    override suspend fun get(invocationId: String): AgentInvocationRecord? {
        return mutex.withLock {
            readAll().firstOrNull { it.invocationId == invocationId }?.toDomain()
        }
    }

    override suspend fun list(): List<AgentInvocationRecord> {
        return mutex.withLock {
            readAll().map { it.toDomain() }
        }
    }

    override suspend fun remove(invocationId: String) {
        mutex.withLock {
            val remaining = readAll().filterNot { it.invocationId == invocationId }
            writeAll(remaining)
        }
    }

    private fun readAll(): List<PersistedInvocationRecord> {
        val file = recordsFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<PersistedInvocationRecord>>(file.readText(), type)
        }.onFailure { e -> onReadError?.invoke(file, e.asException()) }
            .getOrNull().orEmpty()
    }

    private fun writeAll(records: List<PersistedInvocationRecord>) {
        atomicWriteText(recordsFile(), gson.toJson(records, type))
    }

    private fun recordsFile(): File = File(rootDir, "invocations.json")
}

class FileBackedAutonomousRuntimeRecordStore(
    private val rootDir: File,
    private val gson: Gson = Gson(),
    private val onReadError: ((File, Exception) -> Unit)? = null
) : AutonomousRuntimeRecordStore {
    private val mutex = Mutex()
    private val type = object : TypeToken<List<PersistedAutonomousRuntimeRecord>>() {}.type

    init {
        rootDir.mkdirs()
    }

    override suspend fun upsert(record: AutonomousAgentRuntimeRecord) {
        mutex.withLock {
            val current = readAll().associateBy { it.runtimeId }.toMutableMap()
            current[record.runtimeId] = record.toPersisted()
            writeAll(current.values.sortedBy { it.createdAt })
        }
    }

    override suspend fun get(runtimeId: String): AutonomousAgentRuntimeRecord? {
        return mutex.withLock {
            readAll().firstOrNull { it.runtimeId == runtimeId }?.toDomain()
        }
    }

    override suspend fun list(): List<AutonomousAgentRuntimeRecord> {
        return mutex.withLock {
            readAll().map { it.toDomain() }
        }
    }

    override suspend fun remove(runtimeId: String) {
        mutex.withLock {
            val remaining = readAll().filterNot { it.runtimeId == runtimeId }
            writeAll(remaining)
        }
    }

    private fun readAll(): List<PersistedAutonomousRuntimeRecord> {
        val file = recordsFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            gson.fromJson<List<PersistedAutonomousRuntimeRecord>>(file.readText(), type)
        }.onFailure { e -> onReadError?.invoke(file, e.asException()) }
            .getOrNull().orEmpty()
    }

    private fun writeAll(records: List<PersistedAutonomousRuntimeRecord>) {
        atomicWriteText(recordsFile(), gson.toJson(records, type))
    }

    private fun recordsFile(): File = File(rootDir, "autonomous-runtimes.json")
}

private data class PersistedInvocationRecord(
    val invocationId: String,
    val agentId: String,
    val sessionId: String? = null,
    val status: String,
    val inputs: List<PersistedInvocationInput> = emptyList(),
    val outputs: List<String> = emptyList(),
    val finalSnapshot: PersistedSessionSnapshot? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

private data class PersistedInvocationInput(
    val role: String,
    val content: String,
    val name: String? = null
)

private data class PersistedSessionSnapshot(
    val sessionId: String,
    val agentId: String,
    val agentName: String,
    val goal: String,
    val status: String,
    val stopReason: String? = null,
    val failureMessage: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val updatedAt: Long,
    val stepCount: Int,
    val lastReply: String? = null
)

private data class PersistedAutonomousRuntimeRecord(
    val runtimeId: String,
    val agentId: String,
    val lifecycleState: String,
    val stopReason: String? = null,
    val submittedSignals: List<PersistedSignal>? = null,
    val outputs: List<String>? = null,
    val snapshot: PersistedAutonomousSnapshot? = null,
    val events: List<PersistedAutonomousEvent>? = null,
    val correctionRecords: List<PersistedCorrectionRecord>? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

private data class PersistedAutonomousSnapshot(
    val sessionId: String,
    val definition: AgentDefinition,
    val lifecycleState: String,
    val stopReason: String? = null,
    val cycle: Int,
    val failureCount: Int,
    val idleCount: Int,
    val lastPerceptionSummary: String? = null,
    val lastGoalRoot: String? = null,
    val lastGoalSubGoals: List<String>? = null,
    val lastGoalConstraints: List<String>? = null,
    val lastGoalPriority: Int? = null,
    val lastPlanSummary: String? = null,
    val lastPlanSteps: List<String>? = null,
    val lastPlanPreferredTools: List<String>? = null,
    val lastValidationStatus: String? = null,
    val lastValidationFeedback: String? = null,
    val outputs: List<String>? = null,
    val correctionRecords: List<PersistedCorrectionRecord>? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

private data class PersistedSignal(
    val id: String,
    val channel: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long
)

private data class PersistedAutonomousEvent(
    val type: String,
    val sessionId: String,
    val timestamp: Long,
    val lifecycleState: String? = null,
    val stopReason: String? = null,
    val cycle: Int? = null,
    val validationStatus: String? = null,
    val output: String? = null,
    val message: String? = null
)

private data class PersistedCorrectionRecord(
    val cycle: Int,
    val attempt: Int,
    val trigger: String,
    val action: String,
    val reason: String,
    val failureSignature: String,
    val validationStatus: String? = null,
    val error: String? = null,
    val createdAt: Long
)

private fun AgentInvocationRecord.toPersisted(): PersistedInvocationRecord {
    return PersistedInvocationRecord(
        invocationId = invocationId,
        agentId = agentId,
        sessionId = sessionId,
        status = status.name,
        inputs = inputs.map {
            PersistedInvocationInput(
                role = it.role.name,
                content = it.content,
                name = it.name
            )
        },
        outputs = outputs,
        finalSnapshot = finalSnapshot?.toPersisted(),
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun PersistedInvocationRecord.toDomain(): AgentInvocationRecord {
    return AgentInvocationRecord(
        invocationId = invocationId,
        agentId = agentId,
        sessionId = sessionId,
        status = runCatching { AgentInvocationStatus.valueOf(status) }.getOrDefault(AgentInvocationStatus.Pending),
        inputs = inputs.map {
            AgentInvocationInput(
                role = runCatching { AgentMessageRole.valueOf(it.role) }.getOrDefault(AgentMessageRole.User),
                content = it.content,
                name = it.name
            )
        },
        outputs = outputs,
        finalSnapshot = finalSnapshot?.toDomain(),
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun AgentSessionSnapshot.toPersisted(): PersistedSessionSnapshot {
    return PersistedSessionSnapshot(
        sessionId = sessionId,
        agentId = agentId,
        agentName = agentName,
        goal = goal,
        status = status.name,
        stopReason = stopReason?.name,
        failureMessage = failureMessage,
        createdAt = createdAt,
        startedAt = startedAt,
        updatedAt = updatedAt,
        stepCount = stepCount,
        lastReply = lastReply
    )
}

private fun PersistedSessionSnapshot.toDomain(): AgentSessionSnapshot {
    return AgentSessionSnapshot(
        sessionId = sessionId,
        agentId = agentId,
        agentName = agentName,
        goal = goal,
        status = runCatching { AgentSessionStatus.valueOf(status) }.getOrDefault(AgentSessionStatus.Created),
        stopReason = stopReason?.let { runCatching { AgentStopReason.valueOf(it) }.getOrNull() },
        failureMessage = failureMessage,
        createdAt = createdAt,
        startedAt = startedAt,
        updatedAt = updatedAt,
        stepCount = stepCount,
        lastReply = lastReply
    )
}

private fun AutonomousAgentRuntimeRecord.toPersisted(): PersistedAutonomousRuntimeRecord {
    return PersistedAutonomousRuntimeRecord(
        runtimeId = runtimeId,
        agentId = agentId,
        lifecycleState = lifecycleState.name,
        stopReason = stopReason?.name,
        submittedSignals = submittedSignals.map {
            PersistedSignal(
                id = it.id,
                channel = it.channel.name,
                content = it.content,
                metadata = it.metadata,
                createdAt = it.createdAt
            )
        },
        outputs = outputs,
        snapshot = snapshot?.toPersisted(),
        events = events.map { it.toPersisted() },
        correctionRecords = correctionRecords.map { it.toPersisted() },
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun PersistedAutonomousRuntimeRecord.toDomain(): AutonomousAgentRuntimeRecord {
    return AutonomousAgentRuntimeRecord(
        runtimeId = runtimeId,
        agentId = agentId,
        lifecycleState = runCatching {
            AutonomousLifecycleState.valueOf(lifecycleState)
        }.getOrDefault(AutonomousLifecycleState.Created),
        stopReason = stopReason?.let { runCatching { AutonomousStopReason.valueOf(it) }.getOrNull() },
        submittedSignals = submittedSignals.orEmpty().map {
            AgentSignal(
                id = it.id,
                channel = runCatching { SignalChannel.valueOf(it.channel) }.getOrDefault(SignalChannel.System),
                content = it.content,
                metadata = it.metadata,
                createdAt = it.createdAt
            )
        },
        outputs = outputs.orEmpty(),
        snapshot = snapshot?.toDomain(),
        events = events.orEmpty().mapNotNull { it.toDomain() },
        correctionRecords = correctionRecords.orEmpty().mapNotNull { it.toDomain() },
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun AutonomousAgentSnapshot.toPersisted(): PersistedAutonomousSnapshot {
    return PersistedAutonomousSnapshot(
        sessionId = sessionId,
        definition = definition,
        lifecycleState = lifecycleState.name,
        stopReason = stopReason?.name,
        cycle = cycle,
        failureCount = failureCount,
        idleCount = idleCount,
        lastPerceptionSummary = lastPerception?.contextSummary,
        lastGoalRoot = lastGoal?.rootGoal,
        lastGoalSubGoals = lastGoal?.subGoals.orEmpty(),
        lastGoalConstraints = lastGoal?.constraints.orEmpty(),
        lastGoalPriority = lastGoal?.priority,
        lastPlanSummary = lastPlan?.summary,
        lastPlanSteps = lastPlan?.steps.orEmpty(),
        lastPlanPreferredTools = lastPlan?.preferredTools.orEmpty(),
        lastValidationStatus = lastValidation?.status?.name,
        lastValidationFeedback = lastValidation?.feedback,
        outputs = outputs,
        correctionRecords = correctionRecords.map { it.toPersisted() },
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun PersistedAutonomousSnapshot.toDomain(): AutonomousAgentSnapshot {
    return AutonomousAgentSnapshot(
        sessionId = sessionId,
        definition = definition,
        lifecycleState = runCatching {
            AutonomousLifecycleState.valueOf(lifecycleState)
        }.getOrDefault(AutonomousLifecycleState.Created),
        stopReason = stopReason?.let { runCatching { AutonomousStopReason.valueOf(it) }.getOrNull() },
        cycle = cycle,
        failureCount = failureCount,
        idleCount = idleCount,
        lastPerception = lastPerceptionSummary?.let { summary ->
            PerceptionFrame(
                rawSignals = emptyList(),
                cleanedSignals = emptyList(),
                extractedFeatures = emptyMap(),
                contextSummary = summary
            )
        },
        lastGoal = lastGoalRoot?.let { root ->
            ResolvedGoal(
                rootGoal = root,
                subGoals = lastGoalSubGoals.orEmpty(),
                constraints = lastGoalConstraints.orEmpty(),
                priority = lastGoalPriority ?: 50
            )
        },
        lastPlan = lastPlanSummary?.let { summary ->
            TaskPlan(
                summary = summary,
                steps = lastPlanSteps.orEmpty(),
                preferredTools = lastPlanPreferredTools.orEmpty()
            )
        },
        lastValidation = lastValidationStatus?.let { status ->
            ValidationOutcome(
                status = runCatching { ValidationStatus.valueOf(status) }.getOrDefault(ValidationStatus.Running),
                shouldContinue = true,
                shouldRetry = false,
                feedback = lastValidationFeedback.orEmpty()
            )
        },
        outputs = outputs.orEmpty(),
        correctionRecords = correctionRecords.orEmpty().mapNotNull { it.toDomain() },
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun RegisteredAgentProfile.normalize(): RegisteredAgentProfile {
    return copy(config = config.normalize())
}

private fun AgentRunConfig.normalize(): AgentRunConfig {
    val defaults = AgentRunConfig()
    return copy(
        maxSteps = maxSteps.takeIf { it > 0 } ?: defaults.maxSteps,
        maxToolCallsPerStep = maxToolCallsPerStep.takeIf { it > 0 } ?: defaults.maxToolCallsPerStep,
        maxConsecutiveFailures = maxConsecutiveFailures.takeIf { it > 0 } ?: defaults.maxConsecutiveFailures,
        maxIdleTurns = maxIdleTurns.takeIf { it > 0 } ?: defaults.maxIdleTurns,
        maxRuntimeMillis = maxRuntimeMillis.takeIf { it > 0L } ?: defaults.maxRuntimeMillis,
        maxWaitMillis = maxWaitMillis.takeIf { it > 0L } ?: defaults.maxWaitMillis,
        defaultWaitMillis = defaultWaitMillis.takeIf { it > 0L } ?: defaults.defaultWaitMillis,
        maxHistoryItems = maxHistoryItems.takeIf { it > 0 } ?: defaults.maxHistoryItems,
        toolTimeoutMillis = toolTimeoutMillis.takeIf { it > 0L } ?: defaults.toolTimeoutMillis,
        maxCorrectionAttemptsPerStep = maxCorrectionAttemptsPerStep.takeIf { it > 0 } ?: defaults.maxCorrectionAttemptsPerStep,
        maxCorrectionAttemptsPerRun = maxCorrectionAttemptsPerRun.takeIf { it > 0 } ?: defaults.maxCorrectionAttemptsPerRun
    )
}

private fun CorrectionRecord.toPersisted(): PersistedCorrectionRecord {
    return PersistedCorrectionRecord(
        cycle = cycle,
        attempt = attempt,
        trigger = trigger.name,
        action = action.name,
        reason = reason,
        failureSignature = failureSignature,
        validationStatus = validationStatus?.name,
        error = error,
        createdAt = createdAt
    )
}

private fun PersistedCorrectionRecord.toDomain(): CorrectionRecord? {
    val triggerValue = runCatching { CorrectionTrigger.valueOf(trigger) }.getOrNull() ?: return null
    val actionValue = runCatching { CorrectionAction.valueOf(action) }.getOrNull() ?: return null
    return CorrectionRecord(
        cycle = cycle,
        attempt = attempt,
        trigger = triggerValue,
        action = actionValue,
        reason = reason,
        failureSignature = failureSignature,
        validationStatus = validationStatus?.let {
            runCatching { ValidationStatus.valueOf(it) }.getOrNull()
        },
        error = error,
        createdAt = createdAt
    )
}

private fun AutonomousAgentEvent.toPersisted(): PersistedAutonomousEvent {
    return when (this) {
        is AutonomousAgentEvent.LifecycleChanged -> PersistedAutonomousEvent(
            type = "lifecycle_changed",
            sessionId = sessionId,
            timestamp = timestamp,
            lifecycleState = state.name,
            stopReason = stopReason?.name
        )

        is AutonomousAgentEvent.CycleCompleted -> PersistedAutonomousEvent(
            type = "cycle_completed",
            sessionId = sessionId,
            timestamp = timestamp,
            cycle = cycle,
            validationStatus = validationStatus.name
        )

        is AutonomousAgentEvent.OutputPublished -> PersistedAutonomousEvent(
            type = "output_published",
            sessionId = sessionId,
            timestamp = timestamp,
            output = output
        )

        is AutonomousAgentEvent.FailureRecorded -> PersistedAutonomousEvent(
            type = "failure_recorded",
            sessionId = sessionId,
            timestamp = timestamp,
            cycle = cycle,
            message = message
        )
    }
}

private fun PersistedAutonomousEvent.toDomain(): AutonomousAgentEvent? {
    return when (type) {
        "lifecycle_changed" -> {
            val state = lifecycleState?.let {
                runCatching { AutonomousLifecycleState.valueOf(it) }.getOrNull()
            } ?: return null
            AutonomousAgentEvent.LifecycleChanged(
                sessionId = sessionId,
                state = state,
                stopReason = stopReason?.let { runCatching { AutonomousStopReason.valueOf(it) }.getOrNull() },
                timestamp = timestamp
            )
        }

        "cycle_completed" -> {
            val cycleValue = cycle ?: return null
            val validation = validationStatus?.let {
                runCatching { ValidationStatus.valueOf(it) }.getOrNull()
            } ?: return null
            AutonomousAgentEvent.CycleCompleted(
                sessionId = sessionId,
                cycle = cycleValue,
                validationStatus = validation,
                timestamp = timestamp
            )
        }

        "output_published" -> {
            val content = output ?: return null
            AutonomousAgentEvent.OutputPublished(
                sessionId = sessionId,
                output = content,
                timestamp = timestamp
            )
        }

        "failure_recorded" -> {
            val cycleValue = cycle ?: return null
            AutonomousAgentEvent.FailureRecorded(
                sessionId = sessionId,
                cycle = cycleValue,
                message = message.orEmpty(),
                timestamp = timestamp
            )
        }

        else -> null
    }
}

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
        throw IOException("Failed to write file-backed store: ${target.absolutePath}", e)
    }
}

private fun safeId(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

private fun Throwable.asException(): Exception {
    return this as? Exception ?: RuntimeException(message, this)
}
