package com.example.watcher.data.repository.council

import android.graphics.Bitmap
import android.util.Log
import com.example.watcher.data.council.agent.orchestration.CouncilDiscussionRequest
import com.example.watcher.data.council.agent.orchestration.CouncilExpertAgentBinding
import com.example.watcher.data.council.agent.orchestration.CouncilExpertAgentEngine
import com.example.watcher.data.council.agent.orchestration.CouncilGatherRequest
import com.example.watcher.data.local.AiAudienceMessageDao
import com.example.watcher.data.local.CouncilExpertDao
import com.example.watcher.data.local.CouncilKnowledgeDao
import com.example.watcher.data.model.CouncilKnowledgeEntity
import com.example.watcher.data.model.CouncilAnalysisPhase
import com.example.watcher.data.model.CouncilConfig
import com.example.watcher.data.model.CouncilDiscussionSummary
import com.example.watcher.data.model.CouncilDiscussionTurn
import com.example.watcher.data.model.CouncilExpertConsoleState
import com.example.watcher.data.model.CouncilExpertKind
import com.example.watcher.data.model.CouncilExpertOpinion
import com.example.watcher.data.model.CouncilExpertStage
import com.example.watcher.data.model.CouncilSynthesis
import com.example.watcher.data.model.CouncilUiState
import com.example.watcher.data.model.CouncilVoteLevel
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.remote.ChatMessage
import com.example.watcher.data.remote.OpenAiCompatibleProvider
import com.example.watcher.data.repository.CommentaryMemoryManager
import com.example.watcher.data.repository.LlmWalletRepository
import com.example.watcher.data.repository.SceneMemoryManager
import com.example.watcher.data.repository.context.LiveSharedContextProfiles
import com.example.watcher.data.repository.context.LiveSharedContextProvider
import com.example.watcher.data.repository.context.LiveSharedContextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class CouncilManager(
    private val llmWalletRepository: LlmWalletRepository,
    private val councilExpertDao: CouncilExpertDao,
    private val knowledgeDao: CouncilKnowledgeDao,
    messageDao: AiAudienceMessageDao,
    memoryManager: CommentaryMemoryManager,
    sceneMemoryManager: SceneMemoryManager,
    private val expertAgentEngine: CouncilExpertAgentEngine,
    sharedContextProvider: LiveSharedContextProvider? = null
) {
    companion object {
        private const val TAG = "CouncilManager"
        private const val HEARTBEAT_MS = 15_000L
        private const val MIN_ANALYSIS_GAP_MS = 6_000L
        private const val FIRST_ANALYSIS_DELAY_MS = 10_000L
        private const val EXPERT_PARALLELISM = 2
        private const val MAX_LINEUP_SIZE = 5
        private const val MAX_DISCUSSION_ROUNDS = 2
        private val VALID_KNOWLEDGE_CATEGORIES = setOf("session_fact", "expert_calibration", "user_profile")
        private const val FALLBACK_SYNTHESIZER_KEY = "__fallback_synthesizer__"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(CouncilUiState())
    val state: StateFlow<CouncilUiState> = _state.asStateFlow()
    private val promptBuilder = CouncilPromptBuilder()
    private val responseParser = CouncilResponseParser()
    private val coordinator = CouncilCoordinator()
    private val sceneMemory = sceneMemoryManager
    private val contextProvider = sharedContextProvider ?: LiveSharedContextProvider(
        messageDao = messageDao,
        memoryManager = memoryManager,
        sceneMemoryManager = sceneMemoryManager
    )
    private val speechEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val triggerChannel = Channel<String>(Channel.CONFLATED)
    private val analysisMutex = Mutex()
    private val debugPromptCache = mutableMapOf<String, Pair<Long, String>>()
    private val debugResponseCache = mutableMapOf<String, Pair<Long, String>>()
    // Accumulated analysis history for end-of-session knowledge extraction.
    // Accumulated analysis history for end-of-session knowledge extraction
    private val sessionOpinionHistory = mutableListOf<CouncilExpertOpinion>()
    private val sessionTurnHistory = mutableListOf<CouncilDiscussionTurn>()
    private var sessionSynthesisHistory = mutableListOf<CouncilSynthesis>()
    private var workerJob: Job? = null
    private var speechJob: Job? = null
    private var config: CouncilConfig = CouncilConfig()
    private var lastAnalysisAt = 0L
    private var isFirstAnalysis = true

    fun updateConfig(newConfig: CouncilConfig) {
        config = newConfig
        _state.value = _state.value.copy(config = newConfig)
        triggerAnalysis("config")
    }

    fun start(
        frameProvider: () -> Bitmap?,
        speechProvider: (() -> List<Pair<Long, String>>)? = null,
        initialConfig: CouncilConfig = config
    ) {
        config = initialConfig
        isFirstAnalysis = true
        expertAgentEngine.clearSession()
        sessionOpinionHistory.clear()
        sessionTurnHistory.clear()
        sessionSynthesisHistory.clear()
        contextProvider.updateProviders(frameProvider = frameProvider, speechProvider = speechProvider)
        _state.value = CouncilUiState(isActive = true, config = initialConfig)
        workerJob?.cancel()
        workerJob = scope.launch { runWorkerLoop() }
        // Speech events are collected for context but do NOT trigger analysis.
        // Only heartbeat and manual triggers start an analysis cycle.
        speechJob?.cancel()
        speechJob = scope.launch { speechEvents.collect { /* data collected via speechProvider */ } }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        speechJob?.cancel()
        speechJob = null
        contextProvider.updateProviders(frameProvider = null, speechProvider = null)
        // End-of-session knowledge extraction (fire-and-forget)
        if (sessionOpinionHistory.isNotEmpty()) {
            val cfg = config
            val opinions = sessionOpinionHistory.toList()
            val turns = sessionTurnHistory.toList()
            val syntheses = sessionSynthesisHistory.toList()
            val memories = expertAgentEngine.allSessionMemory()
            scope.launch {
                extractAndPersistKnowledge(cfg, opinions, turns, syntheses, memories)
            }
        }
        _state.value = _state.value.copy(
            isActive = false,
            isAnalyzing = false,
            analysisPhase = CouncilAnalysisPhase.Idle,
            discussionTurns = emptyList(),
            discussionSummary = null,
            discussionRound = 0
        )
    }

    fun release() {
        stop()
        scope.cancel()
    }

    fun onSpeechEvent(text: String) { speechEvents.tryEmit(text) }
    fun triggerAnalysis(reason: String = "manual") { triggerChannel.trySend(reason) }
    fun getLastPrompt(expertId: String): String? = debugPromptCache[expertId]?.second
    fun getLastResponse(expertId: String): String? = debugResponseCache[expertId]?.second
    fun getSessionMemory(expertId: String): List<String> = expertAgentEngine.readSessionMemory(expertId)

    private suspend fun runWorkerLoop() {
        while (currentCoroutineContext().isActive) {
            val trigger = select<String> {
                triggerChannel.onReceive { it }
                onTimeout(HEARTBEAT_MS) { "heartbeat" }
            }
            if (isFirstAnalysis) {
                Log.d(TAG, "首轮分析：等待 ${FIRST_ANALYSIS_DELAY_MS}ms 以积累上下文…")
                _state.value = _state.value.copy(
                    analysisPhase = CouncilAnalysisPhase.Idle,
                    errorMessage = "正在等待画面和语音数据积累（${FIRST_ANALYSIS_DELAY_MS / 1000}秒）…"
                )
                delay(FIRST_ANALYSIS_DELAY_MS)
                isFirstAnalysis = false
                _state.value = _state.value.copy(errorMessage = null)
            }
            val remainingGap = MIN_ANALYSIS_GAP_MS - (System.currentTimeMillis() - lastAnalysisAt)
            if (remainingGap > 0) delay(remainingGap)
            runCatching { analyze(trigger) }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Council analysis failed", error)
                _state.value = _state.value.copy(
                    isAnalyzing = false,
                    analysisPhase = CouncilAnalysisPhase.Idle,
                    errorMessage = error.message ?: "Council analysis failed."
                )
            }
        }
    }

    private suspend fun analyze(trigger: String) = analysisMutex.withLock {
        val providers = llmWalletRepository.listProviders()
        val fallbackProvider = providers.firstOrNull { it.enabled } ?: providers.firstOrNull()
        if (fallbackProvider == null) {
            _state.value = _state.value.copy(
                isAnalyzing = false,
                analysisPhase = CouncilAnalysisPhase.Idle,
                lastTrigger = trigger,
                discussionTurns = emptyList(),
                discussionSummary = null,
                discussionRound = 0,
                errorMessage = "No enabled model provider."
            )
            return
        }
        val lineup = resolveCouncilExpertSpecs(councilExpertDao.getAll())
            .filter { it.enabled && it.selectedForCouncil }
            .sortedBy { it.sortOrder }
            .take(MAX_LINEUP_SIZE)
        if (lineup.isEmpty()) {
            _state.value = _state.value.copy(
                isAnalyzing = false,
                analysisPhase = CouncilAnalysisPhase.Idle,
                activeProviderName = fallbackProvider.name,
                lastTrigger = trigger,
                console = emptyList(),
                experts = emptyList(),
                discussionTurns = emptyList(),
                discussionSummary = null,
                discussionRound = 0,
                latestAlert = null,
                synthesis = null,
                errorMessage = "No selected council experts."
            )
            return
        }
        val synthesizer = lineup.firstOrNull { it.expertKind == CouncilExpertKind.Synthesizer }
        val specialists = lineup.filter { it.expertKind != CouncilExpertKind.Synthesizer }
        if (specialists.isEmpty()) {
            _state.value = _state.value.copy(
                isAnalyzing = false,
                analysisPhase = CouncilAnalysisPhase.Idle,
                activeProviderName = fallbackProvider.name,
                lastTrigger = trigger,
                discussionTurns = emptyList(),
                discussionSummary = null,
                discussionRound = 0,
                errorMessage = "Need at least one specialist in the lineup.",
                console = defaultConsoleStates(lineup, CouncilExpertStage.Blocked, "Lineup incomplete", "Need at least one specialist.")
            )
            return
        }
        val context = contextProvider.getSnapshot(
            audience = null,
            activeAudienceNames = emptyList(),
            mentionSince = 0L,
            profile = LiveSharedContextProfiles.Council
        )
        if (isContextEmpty(context)) {
            _state.value = _state.value.copy(
                isAnalyzing = false,
                analysisPhase = CouncilAnalysisPhase.Idle,
                activeProviderName = fallbackProvider.name,
                lastTrigger = trigger,
                discussionTurns = emptyList(),
                discussionSummary = null,
                discussionRound = 0,
                errorMessage = null,
                console = defaultConsoleStates(lineup, CouncilExpertStage.WaitingContext, "Waiting for context", "Need recent visual text, speech, or memory.")
            )
            return
        }
        lastAnalysisAt = System.currentTimeMillis()
        _state.value = _state.value.copy(
            isAnalyzing = true,
            analysisPhase = CouncilAnalysisPhase.Gathering,
            activeProviderName = fallbackProvider.name,
            lastTrigger = trigger,
            errorMessage = null,
            discussionTurns = emptyList(),
            discussionSummary = null,
            discussionRound = 0,
            console = defaultConsoleStates(lineup, CouncilExpertStage.Standby, "排队中", "等待分析轮次。")
        )
        // Council expert gathering runs through the council-owned engine.
        val councilAgentSessionId = "council_${lastAnalysisAt}"
        val expertBindings = specialists.map { spec ->
            val provider = resolveProvider(spec, providers, fallbackProvider)
            updateConsoleState(spec.expertId, CouncilExpertStage.Observing, "读取上下文", "扫描最新画面、语音和记忆。", isLead = true)
            CouncilExpertAgentBinding(spec = spec, provider = provider.toLlm())
        }

        val initialOpinions = expertAgentEngine.gather(
            CouncilGatherRequest(
                experts = expertBindings,
                context = context,
                config = config,
                roundNumber = sessionOpinionHistory.size / specialists.size.coerceAtLeast(1) + 1,
                sessionId = councilAgentSessionId
            )
        ).onEach { councilOpinion ->
            publishOpinion(councilOpinion)
            updateConsoleState(
                councilOpinion.expertId,
                CouncilExpertStage.Voted,
                councilOpinion.summary.take(60),
                councilOpinion.voteReason.take(80),
                voteLevel = councilOpinion.voteLevel,
                confidence = councilOpinion.confidence,
                isLead = false
            )
        }
        // Observation requests are handled by council tools; clear stale ones.
        sceneMemory.setExpertRequests(emptyList())

        _state.value = _state.value.copy(
            experts = initialOpinions.sortedBy { lineupIndex(it.expertId) },
            latestAlert = coordinator.buildAlert(initialOpinions),
            isAnalyzing = true,
            analysisPhase = CouncilAnalysisPhase.Discussing,
            activeProviderName = fallbackProvider.name,
            lastTrigger = trigger,
            errorMessage = null
        )
        // Council discussion remains inside the same council session.
        val allDiscussionTurns = mutableListOf<CouncilDiscussionTurn>()
        for (round in 1..MAX_DISCUSSION_ROUNDS) {
            _state.value = _state.value.copy(discussionRound = round)
            val agentTurns = expertAgentEngine.discuss(
                CouncilDiscussionRequest(
                    sessionId = councilAgentSessionId,
                    round = round,
                    previousTurns = allDiscussionTurns
                )
            )
            if (agentTurns.isEmpty()) break
            agentTurns.forEach { councilTurn ->
                allDiscussionTurns += councilTurn
                publishDiscussionTurn(councilTurn, round)
            }
        }
        val discussionTurns = allDiscussionTurns.toList()

        // Synthesis still uses the selected council provider.
        val synthesisProvider = synthesizer?.let { resolveProvider(it, providers, fallbackProvider) } ?: fallbackProvider
        if (synthesizer != null) {
            updateConsoleState(synthesizer.expertId, CouncilExpertStage.Synthesizing, "整合最终建议", "合并各专家发现、风险和行动建议。", isLead = true)
        }
        _state.value = _state.value.copy(
            analysisPhase = CouncilAnalysisPhase.Synthesizing,
            activeProviderName = synthesisProvider.name
        )
        val synthesis = buildSynthesis(synthesisProvider.toLlm(), initialOpinions, discussionTurns, null, synthesizer)
        _state.value = _state.value.copy(
            experts = initialOpinions.sortedBy { lineupIndex(it.expertId) },
            console = finalizeConsoleStates(lineup, initialOpinions, synthesis, synthesizer),
            latestAlert = coordinator.buildAlert(initialOpinions),
            synthesis = synthesis,
            discussionTurns = discussionTurns,
            isAnalyzing = false,
            analysisPhase = CouncilAnalysisPhase.Complete,
            activeProviderName = synthesisProvider.name,
            lastTrigger = trigger,
            errorMessage = null
        )
        // Keep one summary line for the existing session-memory UI.
        initialOpinions.forEach { opinion ->
            expertAgentEngine.recordSessionSummary(
                opinion.expertId,
                "第${sessionOpinionHistory.size / specialists.size.coerceAtLeast(1) + 1}轮: ${opinion.summary}"
            )
        }
        // Accumulate session history for end-of-session knowledge extraction
        sessionOpinionHistory.addAll(initialOpinions)
        sessionTurnHistory.addAll(discussionTurns)
        if (synthesis != null) sessionSynthesisHistory.add(synthesis)
        Unit
    }

    /**
     * End-of-session knowledge extraction. Processes the entire session's accumulated
     * analysis results and uses LLM to identify knowledge worth persisting.
     * Called once from stop(), not per-round.
     */
    private suspend fun extractAndPersistKnowledge(
        cfg: CouncilConfig,
        opinions: List<CouncilExpertOpinion>,
        turns: List<CouncilDiscussionTurn>,
        syntheses: List<CouncilSynthesis>,
        expertMemories: Map<String, List<String>>
    ) {
        try {
            val now = System.currentTimeMillis()
            val scene = cfg.sceneType.name
            val providers = llmWalletRepository.listProviders()
            val provider = providers.firstOrNull { it.enabled } ?: providers.firstOrNull() ?: return

            // Deduplicate opinions by expert — keep the latest per expert
            val latestByExpert = opinions.groupBy { it.expertId }
                .mapValues { (_, list) -> list.last() }
            // Build expert ID → name mapping for LLM output → DB linking
            val expertIdByName = latestByExpert.mapValues { it.value.name }
                .entries.associate { (k, v) -> v to k }

            val digest = buildString {
                appendLine("# 本次直播会话摘要")
                appendLine("场景: ${scene}")
                if (cfg.speakerRole.isNotBlank()) appendLine("用户角色: ${cfg.speakerRole}")
                if (cfg.targetRole.isNotBlank()) appendLine("对方角色: ${cfg.targetRole}")
                appendLine("总分析轮次: ${opinions.size / latestByExpert.size.coerceAtLeast(1)}")
                appendLine()

                appendLine("# 各专家最终意见")
                latestByExpert.values.forEach { o ->
                    appendLine("【${o.name}】(expertId=${o.expertId}, confidence=${o.confidence})")
                    appendLine("  结论: ${o.summary}")
                    if (o.findings.isNotEmpty()) appendLine("  发现: ${o.findings.joinToString("; ")}")
                }
                appendLine()

                if (expertMemories.isNotEmpty()) {
                    appendLine("# 各专家会话记忆轨迹")
                    expertMemories.forEach { (expertId, mems) ->
                        val name = latestByExpert[expertId]?.name ?: expertId
                        appendLine("【${name}】")
                        mems.forEach { appendLine("  - $it") }
                    }
                    appendLine()
                }

                if (turns.isNotEmpty()) {
                    appendLine("# 讨论精华（最后10条）")
                    turns.takeLast(10).forEach { t ->
                        appendLine("  ${t.fromExpertName}→${t.toExpertName}: ${t.message}")
                    }
                    appendLine()
                }

                if (syntheses.isNotEmpty()) {
                    appendLine("# 综合研判（最后一轮）")
                    val last = syntheses.last()
                    appendLine("  建议: ${last.finalAdvice}")
                    if (last.topFindings.isNotEmpty()) appendLine("  关键发现: ${last.topFindings.joinToString("; ")}")
                }
            }

            val existingContent = try {
                knowledgeDao.getRelevant(scene, limit = 30).map { it.content }.toSet()
            } catch (_: Exception) { emptySet() }

            val systemPrompt = """你是知识萃取器。从一场完整的智囊团直播会话摘要中，提取值得跨会话持久记忆的知识。

知识分三类，每条必须标注 expertId（来源专家的ID，从摘要中获取）：

1. expert_calibration — 专家校准知识
   该专家在类似场景中能复用的分析经验和模式规律。
   示例："面试场景中对方反复追问同一问题说明对答案不满意"
   特征：可复用、跨会话有效、绑定到具体专家。expertId 填该经验所属专家的 ID。

2. user_profile — 用户画像
   关于用户本人的偏好、习惯、关注点。所有专家共享。
   示例："用户更关注期权而非底薪"、"用户倾向主动追问"
   特征：跨场景通用、长期有效。expertId 留空 ""。

3. session_fact — 关键会话事实
   本次直播中发现的重要事实，值得在后续同场景分析中参考。
   示例："对方公司有竞业条款限制"
   特征：具体可验证。expertId 填发现该事实的专家 ID。

绝对不要提取：
- "信息不足/无法判断" 类警告
- "继续观察/保持警惕" 类通用建议
- 单次推测、对AI能力的评价

宁缺毋滥。如果没有值得记住的，返回 []。

返回 JSON: [{"category":"...","content":"...","source":"专家名","expertId":"..."}]
只返回 JSON。"""

            val llm = provider.toLlm()
            val raw = llm.chat(systemPrompt = systemPrompt, messages = listOf(ChatMessage("user", digest))).trim()

            val items = runCatching {
                val gson = com.google.gson.Gson()
                val type = object : com.google.gson.reflect.TypeToken<List<KnowledgeExtractionItem>>() {}.type
                gson.fromJson<List<KnowledgeExtractionItem>>(raw, type)
            }.getOrNull().orEmpty()

            val newEntries = items
                .filter { it.content.isNotBlank() && it.content !in existingContent }
                .map { item ->
                    val category = item.category.takeIf { it in VALID_KNOWLEDGE_CATEGORIES } ?: "session_fact"
                    // Resolve expertId: use provided value, fallback to name-based lookup
                    val resolvedExpertId = when (category) {
                        "user_profile" -> "" // shared, no expert binding
                        else -> item.expertId.takeIf { it.isNotBlank() }
                            ?: expertIdByName[item.source].orEmpty()
                    }
                    CouncilKnowledgeEntity(
                        category = category,
                        expertId = resolvedExpertId,
                        sceneType = scene,
                        content = item.content.take(300),
                        source = item.source.take(50),
                        relevance = 0.8f,
                        createdAt = now,
                        updatedAt = now
                    )
                }
                .take(8) // Max 8 entries per session (more generous since once-per-session)

            if (newEntries.isNotEmpty()) {
                knowledgeDao.insertAll(newEntries)
                Log.d(TAG, "End-of-session knowledge extraction: ${newEntries.size} entries persisted")
            } else {
                Log.d(TAG, "End-of-session knowledge extraction: nothing worth persisting")
            }
            knowledgeDao.pruneStale(now - 30L * 24 * 60 * 60 * 1000)
        } catch (e: Exception) {
            Log.w(TAG, "Knowledge extraction failed", e)
        }
    }

    private data class KnowledgeExtractionItem(
        val category: String = "session_fact",
        val content: String = "",
        val source: String = "",
        val expertId: String = ""
    )

    private suspend fun buildSynthesis(
        llm: OpenAiCompatibleProvider,
        opinions: List<CouncilExpertOpinion>,
        turns: List<CouncilDiscussionTurn>,
        discussionSummary: CouncilDiscussionSummary?,
        synthesizer: CouncilExpertSpec?
    ): CouncilSynthesis {
        val systemPrompt = promptBuilder.buildSynthesisSystemPrompt(synthesizer, config)
        val messages = listOf(
            ChatMessage(
                "user",
                promptBuilder.buildSynthesisUserPrompt(
                    config = config,
                    opinions = opinions,
                    turns = turns,
                    discussionSummary = discussionSummary
                )
            )
        )
        val cacheKey = synthesizer?.expertId ?: FALLBACK_SYNTHESIZER_KEY
        cacheDebugPrompt(cacheKey, buildDebugPrompt(systemPrompt, messages))
        val raw = llm.chat(systemPrompt = systemPrompt, messages = messages).trim()
        cacheDebugResponse(cacheKey, raw)
        return responseParser.parseSynthesis(raw)
    }

    private fun resolveProvider(
        spec: CouncilExpertSpec,
        providers: List<LlmProviderEntity>,
        fallbackProvider: LlmProviderEntity
    ): LlmProviderEntity {
        return providers.firstOrNull { it.id == spec.providerId } ?: fallbackProvider
    }


    private fun cacheDebugPrompt(expertId: String, prompt: String) {
        debugPromptCache[expertId] = System.currentTimeMillis() to prompt
    }

    private fun cacheDebugResponse(expertId: String, response: String) {
        debugResponseCache[expertId] = System.currentTimeMillis() to response
    }

    private fun buildDebugPrompt(systemPrompt: String, messages: List<ChatMessage>): String = buildString {
        appendLine("=== System ===")
        appendLine(systemPrompt)
        appendLine("=== Messages (${messages.size}) ===")
        messages.forEachIndexed { index, message ->
            appendLine("[${index + 1}|${message.role}] ${message.content}")
        }
    }

    private fun severity(level: CouncilVoteLevel): Int = when (level) {
        CouncilVoteLevel.Pass -> 0
        CouncilVoteLevel.Watch -> 1
        CouncilVoteLevel.Warn -> 2
        CouncilVoteLevel.Alert -> 3
    }

    private fun isContextEmpty(snapshot: LiveSharedContextSnapshot): Boolean {
        return snapshot.visual.recentVisual.isEmpty() &&
            snapshot.speech.recentSpeech.isEmpty() &&
            snapshot.memory.memoryA.isBlank() &&
            snapshot.memory.memoryB.isBlank()
    }

    private fun lineupIndex(expertId: String): Int {
        return _state.value.console.indexOfFirst { it.expertId == expertId }
            .takeIf { it >= 0 }
            ?: Int.MAX_VALUE
    }

    private fun publishDiscussionTurn(turn: CouncilDiscussionTurn, round: Int) {
        _state.value = _state.value.copy(
            discussionTurns = _state.value.discussionTurns + turn,
            discussionRound = round
        )
    }

    private fun publishOpinion(opinion: CouncilExpertOpinion) {
        _state.value = _state.value.copy(
            experts = (_state.value.experts.filterNot { it.expertId == opinion.expertId } + opinion)
                .sortedBy { expert -> lineupIndex(expert.expertId) },
            console = _state.value.console.map { entry ->
                if (entry.expertId != opinion.expertId) {
                    entry.copy(isLead = false)
                } else {
                    entry.copy(
                        stage = CouncilExpertStage.Voted,
                        headline = opinion.summary.ifBlank { "${opinion.name} ready" },
                        note = opinion.voteReason.ifBlank {
                            opinion.nextActions.firstOrNull()
                                ?: opinion.risks.firstOrNull()
                                ?: opinion.findings.firstOrNull()
                                ?: ""
                        },
                        voteLevel = opinion.voteLevel,
                        confidence = opinion.confidence,
                        isLead = false,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        )
    }

    private fun updateConsoleState(
        spec: CouncilExpertSpec,
        opinion: CouncilExpertOpinion?,
        noteOverride: String? = null
    ) {
        if (opinion == null) {
            updateConsoleState(
                expertId = spec.expertId,
                stage = CouncilExpertStage.Standby,
                headline = spec.name,
                note = noteOverride.orEmpty(),
                isLead = false
            )
            return
        }
        updateConsoleState(
            expertId = spec.expertId,
            stage = CouncilExpertStage.Voted,
            headline = opinion.summary,
            note = noteOverride ?: opinion.voteReason.ifBlank {
                opinion.nextActions.firstOrNull()
                    ?: opinion.risks.firstOrNull()
                    ?: opinion.findings.firstOrNull()
                    ?: ""
            },
            voteLevel = opinion.voteLevel,
            confidence = opinion.confidence,
            isLead = false
        )
    }

    private fun updateConsoleState(
        expertId: String,
        stage: CouncilExpertStage,
        headline: String,
        note: String,
        voteLevel: CouncilVoteLevel? = null,
        confidence: Int? = null,
        isLead: Boolean = false
    ) {
        _state.value = _state.value.copy(
            console = _state.value.console.map { entry ->
                when {
                    entry.expertId == expertId -> entry.copy(
                        stage = stage,
                        headline = headline,
                        note = note,
                        voteLevel = voteLevel ?: entry.voteLevel,
                        confidence = confidence ?: entry.confidence,
                        isLead = isLead,
                        updatedAt = System.currentTimeMillis()
                    )
                    isLead -> entry.copy(isLead = false)
                    else -> entry
                }
            }
        )
    }

    private fun finalizeConsoleStates(
        specs: List<CouncilExpertSpec>,
        opinions: List<CouncilExpertOpinion>,
        synthesis: CouncilSynthesis,
        synthesizer: CouncilExpertSpec?
    ): List<CouncilExpertConsoleState> {
        val byId = opinions.associateBy { it.expertId }
        return defaultConsoleStates(specs).map { entry ->
            if (synthesizer != null && entry.expertId == synthesizer.expertId) {
                entry.copy(
                    stage = CouncilExpertStage.Voted,
                    headline = synthesis.finalAdvice.ifBlank { "Synthesis ready" },
                    note = synthesis.topRisks.firstOrNull()
                        ?: synthesis.topFindings.firstOrNull()
                        ?: synthesis.nextActions.firstOrNull()
                        ?: "",
                    isLead = false,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                val opinion = byId[entry.expertId] ?: return@map entry
                entry.copy(
                    stage = CouncilExpertStage.Voted,
                    headline = opinion.summary,
                    note = opinion.voteReason.ifBlank {
                        opinion.nextActions.firstOrNull()
                            ?: opinion.risks.firstOrNull()
                            ?: opinion.findings.firstOrNull()
                            ?: ""
                    },
                    voteLevel = opinion.voteLevel,
                    confidence = opinion.confidence,
                    isLead = false,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private fun defaultConsoleStates(
        specs: List<CouncilExpertSpec> = emptyList(),
        stage: CouncilExpertStage = CouncilExpertStage.Standby,
        headline: String = "",
        note: String = ""
    ): List<CouncilExpertConsoleState> {
        return specs.map { spec ->
            CouncilExpertConsoleState(
                expertId = spec.expertId,
                name = spec.name,
                expertKind = spec.expertKind,
                legacyRole = spec.legacyRole,
                stage = stage,
                headline = headline,
                note = note
            )
        }
    }

    private fun LlmProviderEntity.toLlm(): OpenAiCompatibleProvider {
        return OpenAiCompatibleProvider(
            id = id,
            displayName = name,
            endpoint = endpoint,
            apiKey = apiKey,
            modelName = modelName
        )
    }
}
