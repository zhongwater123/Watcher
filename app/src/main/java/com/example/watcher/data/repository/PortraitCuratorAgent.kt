package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.agentframework.autonomy.SignalChannel
import com.example.watcher.agentframework.core.AgentDefinition
import com.example.watcher.agentframework.core.AgentMemoryScope
import com.example.watcher.agentframework.core.AgentRunConfig
import com.example.watcher.agentframework.integration.APP_DEFAULT_LLM_BRAIN_FACTORY_ID
import com.example.watcher.agentframework.service.AgentMemorySeed
import com.example.watcher.agentframework.service.AgentFrameworkService
import com.example.watcher.agentframework.service.AgentRegistration
import com.example.watcher.agentframework.service.AgentSignalSeed
import com.example.watcher.agentframework.service.AutonomousAgentStartRequest
import com.example.watcher.agentframework.service.AutonomousAgentRuntimeRecord
import com.example.watcher.data.local.BehaviorModelDao
import com.example.watcher.data.local.BlackboardDao
import com.example.watcher.data.local.SceneProfileDao
import com.example.watcher.data.model.MatchBreakdown
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Autonomous behavior-model curator agent.
 *
 * This class only coordinates runtime lifecycle and tool registration.
 * Concrete tools, prompt templates and helpers are split into dedicated files.
 */
class PortraitCuratorAgent(
    private val service: AgentFrameworkService,
    private val blackboardDao: BlackboardDao,
    private val behaviorModelDao: BehaviorModelDao,
    private val behaviorClaimConsolidator: BehaviorClaimConsolidator,
    private val sceneProfileDao: SceneProfileDao,
    private val sceneMemoryManager: SceneMemoryManager? = null,
    private val currentMatchBreakdownProvider: () -> MatchBreakdown? = { null }
) {
    companion object {
        internal const val TAG = "PortraitCurator"
        private const val MAX_ACTIVITY_ITEMS = 60
        private const val MEMORY_PREVIEW_LIMIT = 5
    }

    private val registeredToolNames = linkedSetOf<String>()

    private var registered = false
    private var runtimeId: String? = null
    private var lastKnownRuntimeId: String? = null
    var currentSceneId: String? = null
    var currentSceneLabel: String = "未命名场景"
    private val activitySequence = AtomicLong(0)
    private val _activityLog = MutableStateFlow<List<PortraitCuratorActivityEntry>>(emptyList())
    val activityLog: StateFlow<List<PortraitCuratorActivityEntry>> = _activityLog.asStateFlow()
    private val _memoryDebugState = MutableStateFlow(PortraitCuratorMemoryDebugState())
    val memoryDebugState: StateFlow<PortraitCuratorMemoryDebugState> = _memoryDebugState.asStateFlow()
    private val modelingState = PortraitCuratorModelingState()
    private val maxRuntimeMillis = 600_000L

    suspend fun ensureRegistered() {
        if (!registered) {
            registerTools()
        }

        // Re-register agent definition each time to pick up evolved modeling rules
        val activeRules = try {
            service.queryAgentKnowledge(
                PORTRAIT_CURATOR_AGENT_ID,
                query = "modeling_rule",
                tags = setOf("modeling_rule"),
                limit = 5
            ).map { it.content }
        } catch (_: Exception) {
            emptyList()
        }

        try {
            service.unregisterAgent(PORTRAIT_CURATOR_AGENT_ID)
        } catch (_: Exception) {
        }
        service.registerAgent(
            AgentRegistration(
                definition = AgentDefinition(
                    agentId = PORTRAIT_CURATOR_AGENT_ID,
                    name = "Behavior Curator",
                    systemInstruction = PortraitCuratorPrompts.systemInstruction(activeRules),
                    goal = "围绕具体场景持续构建可演化的用户行为模型，并在必要时收敛跨场景通用模式"
                ),
                brainFactoryId = APP_DEFAULT_LLM_BRAIN_FACTORY_ID,
                config = AgentRunConfig(
                    maxSteps = 50,
                    maxToolCallsPerStep = 8,
                    maxConsecutiveFailures = 5,
                    maxIdleTurns = 20,
                    maxRuntimeMillis = maxRuntimeMillis,
                    toolTimeoutMillis = 30_000L
                )
            )
        )
        registered = true
        recordActivity(
            type = "system",
            summary = "Agent 已注册",
            detail = if (activeRules.isNotEmpty()) {
                "已挂载工具 + 注入 ${activeRules.size} 条建模经验规则。"
            } else {
                "已挂载 workspace memory、claim CRUD、memory/knowledge、归一、推理与观察请求工具。"
            }
        )
    }

    suspend fun start() {
        ensureRegistered()
        clearTerminalRuntime()
        check(runtimeId == null) { "Agent runtime is still active; wait for shutdown before starting a new one." }
        recordActivity(type = "lifecycle", summary = "启动 Agent", detail = "开始初始化 autonomous runtime。")

        val workspaceSnapshot = buildWorkspaceSnapshot()
        val preloadMemory = buildPreloadedMemory(workspaceSnapshot)
        recordActivity(
            type = "write",
            summary = "预载 workspace memory",
            detail = workspaceSnapshot.toWorkingMemoryText().replace("\n", " / ").take(220),
            status = "success"
        )
        if (preloadMemory.any { it.scope == AgentMemoryScope.Episodic }) {
            recordActivity(
                type = "write",
                summary = "桥接长期 knowledge 到 session",
                detail = "recent durable lessons have been preloaded into episodic memory",
                status = "success"
            )
        }
        Log.d(TAG, "Starting autonomous behavior curator agent")
        val record = service.startAutonomousAgent(
            AutonomousAgentStartRequest(
                agentId = PORTRAIT_CURATOR_AGENT_ID,
                preloadMemory = preloadMemory,
                allowedToolNames = registeredToolNames.toSet(),
                initialSignals = listOf(
                    AgentSignalSeed(
                        channel = SignalChannel.System,
                        content = PortraitCuratorPrompts.startupSignal(
                            sceneLabel = currentSceneLabel,
                            sceneId = currentSceneId
                        )
                    )
                )
            )
        )
        runtimeId = record.runtimeId
        lastKnownRuntimeId = record.runtimeId
        modelingState.markPreloaded(record.runtimeId)
        Log.d(TAG, "Agent started, runtimeId=${record.runtimeId}")
        recordActivity(
            type = "lifecycle",
            summary = "Agent 已启动",
            detail = "runtimeId=${record.runtimeId}, preloadedMemory=${preloadMemory.size}"
        )
        refreshMemoryDebug()
    }

    suspend fun feedObservation(text: String) {
        val rid = runtimeId ?: return
        try {
            val dimensionContext = buildClaimContextForSignal(behaviorModelDao, currentSceneId)
            val enrichedSignal = text + dimensionContext
            service.submitAutonomousSignal(
                rid,
                AgentSignalSeed(
                    channel = SignalChannel.Environment,
                    content = enrichedSignal
                )
            )
            recordActivity(
                type = "signal",
                summary = "收到 observation signal",
                detail = text.lineSequence().take(3).joinToString(" / ").take(180)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to feed observation signal", e)
            recordActivity(
                type = "signal",
                summary = "提交 observation signal 失败",
                detail = e.message ?: "unknown error",
                status = "error"
            )
        }
    }

    suspend fun beginConsolidation() {
        val rid = runtimeId ?: return
        try {
            service.writeRuntimeMemory(
                runtimeId = rid,
                scope = AgentMemoryScope.Working,
                content = "Consolidation phase for scene=${currentSceneLabel}. All workspace context has been loaded during observation phase. Proceed directly: consolidate_behavior_claims -> resolve goals -> write knowledge if needed -> Finish.",
                tags = setOf("phase", "consolidation")
            )
            service.writeRuntimeMemory(
                runtimeId = rid,
                scope = AgentMemoryScope.Episodic,
                content = "Consolidation phase for scene=${currentSceneLabel}. Remaining steps: consolidate_behavior_claims, resolve open goals, write durable lessons to knowledge if any, then Finish. No further reads needed.",
                tags = setOf("session", "consolidation")
            )
            service.submitAutonomousSignal(
                rid,
                AgentSignalSeed(
                    channel = SignalChannel.System,
                    content = PortraitCuratorPrompts.consolidationSignal()
                )
            )
            recordActivity(
                type = "lifecycle",
                summary = "进入收敛阶段",
                detail = "观察输入已关闭，Agent 将继续归一收敛直到自行结束或被手动停止。",
                status = "success"
            )
            refreshMemoryDebug()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to submit consolidation signal", e)
            recordActivity(
                type = "lifecycle",
                summary = "进入收敛阶段失败",
                detail = e.message ?: "unknown error",
                status = "error"
            )
        }
    }

    suspend fun stop() {
        val rid = runtimeId ?: return
        Log.d(TAG, "Stopping autonomous behavior curator agent")
        recordActivity(type = "lifecycle", summary = "停止 Agent", detail = "收到手动停止请求，准备关闭 runtime。")
        try {
            service.stopAutonomousRuntime(rid)
            recordActivity(type = "lifecycle", summary = "停止请求已发送", detail = "等待 runtime 完成当前循环后结束。")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop agent runtime", e)
            recordActivity(
                type = "lifecycle",
                summary = "停止 Agent 失败",
                detail = e.message ?: "unknown error",
                status = "error"
            )
        }
    }

    suspend fun stopAndAwaitTermination() {
        val rid = runtimeId ?: return
        stop()
        service.awaitAutonomousRuntime(rid)
        clearTerminalRuntime()
    }

    suspend fun clearTerminalRuntime(): Boolean {
        val rid = runtimeId ?: return false
        val record = service.getAutonomousRuntime(rid) ?: return false
        if (!record.lifecycleState.isTerminal) return false
        lastKnownRuntimeId = rid
        runtimeId = null
        modelingState.clear(rid)
        recordActivity(
            type = "lifecycle",
            summary = "Agent 运行结束",
            detail = buildString {
                append("lifecycle=")
                append(record.lifecycleState.name)
                record.stopReason?.let {
                    append(", stopReason=")
                    append(it.name)
                }
            },
            status = if (record.errorMessage == null) "success" else "error"
        )
        refreshMemoryDebug()
        return true
    }

    suspend fun resetModel() {
        behaviorModelDao.deleteAllClaims()
        behaviorModelDao.deleteAllGoals()
        recordActivity(
            type = "write",
            summary = "重置行为模型",
            detail = "已清空 claims 与 observation goals。"
        )
    }

    val isRunning: Boolean get() = runtimeId != null

    suspend fun getStatus(): PortraitCuratorStatus {
        val rid = runtimeId ?: return PortraitCuratorStatus.Idle
        return try {
            val record = service.getAutonomousRuntime(rid)
            if (record != null) {
                record.toStatus()
            } else {
                PortraitCuratorStatus.Idle
            }
        } catch (_: Exception) {
            PortraitCuratorStatus.Idle
        }
    }

    suspend fun refreshMemoryDebug() {
        val targetRuntimeId = runtimeId ?: lastKnownRuntimeId
        val workingEntries = targetRuntimeId?.let {
            service.readRuntimeMemory(it, AgentMemoryScope.Working, MEMORY_PREVIEW_LIMIT)
        }.orEmpty()
        val episodicEntries = targetRuntimeId?.let {
            service.readRuntimeMemory(it, AgentMemoryScope.Episodic, MEMORY_PREVIEW_LIMIT)
        }.orEmpty()
        val structuredEntries = targetRuntimeId?.let {
            service.readStructuredMemory(it)
        }.orEmpty()
        val knowledgeEntries = try {
            service.readAgentKnowledge(PORTRAIT_CURATOR_AGENT_ID, MEMORY_PREVIEW_LIMIT)
        } catch (_: Exception) {
            emptyList()
        }

        _memoryDebugState.value = PortraitCuratorMemoryDebugState(
            runtimeId = targetRuntimeId,
            workingEntries = workingEntries.map {
                PortraitCuratorMemoryEntry(
                    scope = "working",
                    content = it.content,
                    tags = it.tags.toList(),
                    createdAt = it.createdAt
                )
            },
            episodicEntries = episodicEntries.map {
                PortraitCuratorMemoryEntry(
                    scope = "episodic",
                    content = it.content,
                    tags = it.tags.toList(),
                    createdAt = it.createdAt
                )
            },
            knowledgeEntries = knowledgeEntries.map {
                PortraitCuratorMemoryEntry(
                    scope = "knowledge",
                    content = it.content,
                    tags = it.tags.toList(),
                    createdAt = it.updatedAt
                )
            },
            structuredShortTermCount = structuredEntries.count { it.scope.name == "ShortTerm" },
            structuredWorkingCount = structuredEntries.count { it.scope.name == "Working" },
            structuredLongTermCount = structuredEntries.count { it.scope.name == "LongTerm" }
        )
    }

    private suspend fun buildWorkspaceSnapshot(): PortraitWorkspaceMemorySnapshot {
        return PortraitWorkspaceSnapshotBuilder(
            blackboardDao = blackboardDao,
            behaviorModelDao = behaviorModelDao,
            sceneProfileDao = sceneProfileDao,
            sceneMemoryManager = sceneMemoryManager,
            currentSceneIdProvider = { currentSceneId },
            currentSceneLabelProvider = { currentSceneLabel },
            currentMatchBreakdownProvider = currentMatchBreakdownProvider
        ).snapshot()
    }

    private suspend fun buildPreloadedMemory(
        workspaceSnapshot: PortraitWorkspaceMemorySnapshot
    ): List<AgentMemorySeed> {
        // Semantic query: scene-relevant + general modeling rules (replaces generic "last 5")
        val sceneRelevant = try {
            service.queryAgentKnowledge(
                PORTRAIT_CURATOR_AGENT_ID,
                query = currentSceneLabel,
                limit = 3
            )
        } catch (_: Exception) {
            emptyList()
        }
        val generalRules = try {
            service.queryAgentKnowledge(
                PORTRAIT_CURATOR_AGENT_ID,
                query = "modeling_rule",
                tags = setOf("modeling_rule"),
                limit = 3
            )
        } catch (_: Exception) {
            emptyList()
        }
        val relevantKnowledge = (sceneRelevant + generalRules).distinctBy { it.content }.take(5)

        return buildList {
            add(
                AgentMemorySeed(
                    scope = AgentMemoryScope.Working,
                    content = workspaceSnapshot.toWorkingMemoryText(),
                    tags = setOf("workspace", "startup")
                )
            )
            add(
                AgentMemorySeed(
                    scope = AgentMemoryScope.Working,
                    content = PortraitCuratorPrompts.schemaPreloadText(),
                    tags = setOf("schema", "startup")
                )
            )
            if (relevantKnowledge.isNotEmpty()) {
                add(
                    AgentMemorySeed(
                        scope = AgentMemoryScope.Episodic,
                        content = buildString {
                            appendLine("场景相关经验和建模规则")
                            relevantKnowledge.forEach { appendLine("- ${it.content}") }
                        }.trim(),
                        tags = setOf("knowledge_bridge", "startup")
                    )
                )
            }
        }
    }

    private suspend fun registerTools() {
        registeredToolNames.clear()

        // Write tools (core — these are what the agent should actually use)
        registerTool(CuratorWriteMemoryTool(::recordActivity))
        registerTool(CuratorWriteKnowledgeTool({ currentSceneLabel }, ::recordActivity))
        registerTool(
            CreateBehaviorClaimTool(
                behaviorModelDao,
                currentSceneIdProvider = { currentSceneId },
                modelingState = modelingState,
                recordActivity = ::recordActivity
            )
        )
        registerTool(
            UpdateBehaviorClaimTool(
                behaviorModelDao,
                currentSceneIdProvider = { currentSceneId },
                modelingState = modelingState,
                recordActivity = ::recordActivity
            )
        )
        registerTool(
            MergeBehaviorClaimsTool(
                behaviorModelDao,
                currentSceneIdProvider = { currentSceneId },
                modelingState = modelingState,
                recordActivity = ::recordActivity
            )
        )
        registerTool(
            DeleteBehaviorClaimTool(
                behaviorModelDao,
                currentSceneIdProvider = { currentSceneId },
                modelingState = modelingState,
                recordActivity = ::recordActivity
            )
        )
        registerTool(
            ConsolidateBehaviorClaimsTool(
                consolidator = behaviorClaimConsolidator,
                currentSceneIdProvider = { currentSceneId },
                currentSceneLabelProvider = { currentSceneLabel },
                modelingState = modelingState,
                recordActivity = ::recordActivity
            )
        )
        registerTool(
            WriteInferenceTool(
                behaviorModelDao,
                currentSceneIdProvider = { currentSceneId },
                recordActivity = ::recordActivity
            )
        )
        // Read tool (safety valve — only for targeted pre-update checks)
        registerTool(
            ReadClaimsByDimensionTool(
                behaviorModelDao,
                currentSceneIdProvider = { currentSceneId },
                currentSceneLabelProvider = { currentSceneLabel },
                modelingState = modelingState,
                recordActivity = ::recordActivity
            )
        )
        // Goal & observation tools
        registerTool(
            ResolveObservationGoalTool(
                behaviorModelDao,
                currentSceneIdProvider = { currentSceneId },
                recordActivity = ::recordActivity
            )
        )
        registerTool(
            RequestObservationTool(
                behaviorModelDao,
                sceneMemoryManager,
                currentSceneIdProvider = { currentSceneId },
                recordActivity = ::recordActivity
            )
        )
    }

    private fun registerTool(tool: com.example.watcher.agentframework.tools.AgentTool) {
        registeredToolNames += tool.definition.name
        service.registerTool(tool)
    }

    private fun recordActivity(
        type: String,
        summary: String,
        detail: String = "",
        status: String = "info"
    ) {
        val entry = PortraitCuratorActivityEntry(
            id = activitySequence.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            type = type,
            summary = summary,
            detail = detail,
            status = status
        )
        _activityLog.value = listOf(entry) + _activityLog.value.take(MAX_ACTIVITY_ITEMS - 1)
    }
}

data class PortraitCuratorActivityEntry(
    val id: Long,
    val timestamp: Long,
    val type: String,
    val summary: String,
    val detail: String = "",
    val status: String = "info"
)

sealed class PortraitCuratorStatus {
    data object Idle : PortraitCuratorStatus()
    data class Running(
        val runtimeId: String,
        val lifecycleState: String,
        val signalCount: Int,
        val outputCount: Int,
        val cycle: Int,
        val idleCount: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val lastValidationStatus: String? = null,
        val lastValidationFeedback: String? = null,
        val lastOutputPreview: String? = null,
        val stopReason: String? = null,
        val error: String? = null
    ) : PortraitCuratorStatus() {
        val isTerminal: Boolean
            get() = lifecycleState == "Stopped" ||
                lifecycleState == "Failed" ||
                lifecycleState == "Destroyed"
    }
}

internal fun AutonomousAgentRuntimeRecord.toStatus(): PortraitCuratorStatus.Running {
    val snapshot = snapshot
    return PortraitCuratorStatus.Running(
        runtimeId = runtimeId,
        lifecycleState = lifecycleState.name,
        signalCount = submittedSignals.size,
        outputCount = outputs.size,
        cycle = snapshot?.cycle ?: 0,
        idleCount = snapshot?.idleCount ?: 0,
        createdAt = createdAt,
        updatedAt = snapshot?.updatedAt ?: updatedAt,
        lastValidationStatus = snapshot?.lastValidation?.status?.name,
        lastValidationFeedback = snapshot?.lastValidation?.feedback,
        lastOutputPreview = outputs.lastOrNull()?.take(160),
        stopReason = stopReason?.name,
        error = errorMessage
    )
}
