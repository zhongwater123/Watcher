package com.example.watcher.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.watcher.data.local.AiAudienceDao
import com.example.watcher.data.local.AiAudienceMessageDao
import com.example.watcher.data.model.AiAudienceEntity
import com.example.watcher.data.model.AiAudienceLiveState
import com.example.watcher.data.model.AiAudienceMessageEntity
import com.example.watcher.data.model.AudienceEngineType
import com.example.watcher.data.model.AudienceAction
import com.example.watcher.data.model.AudienceDebugInfo
import com.example.watcher.data.model.MemorySnapshot
import com.example.watcher.data.model.DanmakuItem
import com.example.watcher.data.model.GiftEvent
import com.example.watcher.data.model.GiftRankEntry
import com.example.watcher.data.model.GiftType
import com.example.watcher.data.model.LikeRankEntry
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.remote.ChatMessage
import com.example.watcher.data.remote.OpenAiCompatibleProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AiAudienceManager(
    private val llmWalletRepository: LlmWalletRepository,
    private val audienceDao: AiAudienceDao,
    private val messageDao: AiAudienceMessageDao,
    private val memoryManager: CommentaryMemoryManager,
    private val sceneMemoryManager: SceneMemoryManager
) {
    private val audienceType = AudienceEngineType.Classic

    companion object {
        private const val TAG = "AiAudience"
        private const val MAX_LIVE_MESSAGES = 80
        private const val MAX_AI_CHAT_HISTORY = 10
        private const val SILENCE_TOKEN = "[SILENCE]"
        private const val INITIAL_BUDGET = 100
        private const val LIKE_COOLDOWN_MS = 60_000L
    }

    private val _liveState = MutableStateFlow(AiAudienceLiveState())
    val liveState: StateFlow<AiAudienceLiveState> = _liveState.asStateFlow()

    private val _danmakuFlow = MutableSharedFlow<DanmakuItem>(extraBufferCapacity = 16)
    val danmakuFlow: SharedFlow<DanmakuItem> = _danmakuFlow.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val heartbeatJobs = mutableMapOf<Long, Job>()
    private val resetChannels = mutableMapOf<Long, Channel<String>>() // audienceId → trigger channel
    private val busyAudiences = mutableSetOf<Long>()
    private val providerMutexes = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>() // providerId → mutex
    private val audienceNames = mutableMapOf<Long, String>()
    private val emotionStates = mutableMapOf<Long, String>()
    private val hasEnteredChat = mutableSetOf<Long>()
    private val wallets = mutableMapOf<Long, Int>()
    private val lastLikeTime = mutableMapOf<Long, Long>()
    private val likeCounts = mutableMapOf<String, Int>()     // audienceName → like count
    private val giftSpending = mutableMapOf<String, Int>()   // audienceName → total spent
    private var lastLikerName: String? = null
    private val recentGifts = mutableListOf<GiftEvent>()
    private val lastDanmaku = mutableMapOf<Long, Pair<Long, String>>()
    private val lastPostContent = mutableMapOf<Long, Pair<Long, String>>() // audienceId → (time, full prompt)
    private val lastResponse = mutableMapOf<Long, Pair<Long, String>>()    // audienceId → (time, full raw response)

    private var observeJob: Job? = null
    private var speechListenJob: Job? = null
    private var latestFrameProvider: (() -> Bitmap?)? = null
    private var speechTranscriptProvider: (() -> List<Pair<Long, String>>)? = null
    private var lastMentionCheckTime = mutableMapOf<Long, Long>()

    // Speech event flow — emitted by LiveSpeechRecognitionManager when a sentence is recognized
    private val _speechEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Called externally when a speech sentence is finalized */
    fun onSpeechEvent(text: String) {
        _speechEvent.tryEmit(text)
    }


    fun start(
        frameProvider: () -> Bitmap?,
        speechProvider: (() -> List<Pair<Long, String>>)? = null
    ) {
        latestFrameProvider = frameProvider
        speechTranscriptProvider = speechProvider
        observeJob?.cancel()
        observeJob = scope.launch {
            audienceDao.observeAll().collect { allAudiences ->
                val enabled = allAudiences.filter { it.enabled && it.audienceType == audienceType }
                val providers = llmWalletRepository.listProviders()
                syncHeartbeats(enabled, providers)
            }
        }
        // Listen for speech events → trigger named + random audience(s)
        speechListenJob?.cancel()
        speechListenJob = scope.launch {
            _speechEvent.collect { text ->
                Log.d(TAG, "Speech event: '${text.take(30)}', triggering audience(s)")
                triggerBySpeech(text)
            }
        }
    }

    private fun syncHeartbeats(
        enabledAudiences: List<AiAudienceEntity>,
        providers: List<LlmProviderEntity>
    ) {
        val enabledIds = enabledAudiences.map { it.id }.toSet()
        val runningIds = heartbeatJobs.keys.toSet()
        audienceNames.keys
            .filter { it !in enabledIds }
            .forEach(audienceNames::remove)
        enabledAudiences.forEach { audienceNames[it.id] = it.name }

        // Stop removed / disabled audiences
        for (id in runningIds - enabledIds) {
            Log.d(TAG, "Stopping heartbeat for audience id=$id (removed or disabled)")
            heartbeatJobs.remove(id)?.cancel()
            lastMentionCheckTime.remove(id)
        }

        // Start new audiences
        for (audience in enabledAudiences) {
            if (audience.id in runningIds) continue // already running
            val provider = providers.find { it.id == audience.providerId }
            if (provider == null) {
                Log.w(TAG, "No provider for audience '${audience.name}' (providerId=${audience.providerId})")
                continue
            }
            Log.d(TAG, "Starting heartbeat for new audience '${audience.name}'")
            launchHeartbeat(audience, provider, enabledAudiences)
        }

        _liveState.value = _liveState.value.copy(activeAudienceCount = enabledAudiences.size)
    }

    fun stop() {
        // Stop running jobs
        observeJob?.cancel()
        observeJob = null
        speechListenJob?.cancel()
        speechListenJob = null
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
        resetChannels.values.forEach { it.close() }
        resetChannels.clear()
        providerMutexes.clear()
        latestFrameProvider = null
        speechTranscriptProvider = null

        // Clear runtime-only state (not debug/session data)
        lastMentionCheckTime.clear()
        lastLikeTime.clear()
        busyAudiences.clear()
        lastDanmaku.clear()
        audienceNames.clear()

        _liveState.value = AiAudienceLiveState()
    }

    /** End-of-session: observer compresses this session into personal memory for each audience */
    fun compressMemories() {
        scope.launch {
            val allMessages = messageDao.getRecent(200) // get all recent messages
            if (allMessages.isEmpty()) {
                Log.d(TAG, "No messages to compress into memory")
                return@launch
            }

            val audiences = audienceDao.getEnabled().filter { it.audienceType == audienceType }
            val providers = llmWalletRepository.listProviders()
            val memoryA = memoryManager.memoryA
            val memoryB = memoryManager.latestMemoryB

            for (audience in audiences) {
                val provider = providers.find { it.id == audience.providerId } ?: continue
                val llm = OpenAiCompatibleProvider(
                    id = provider.id, displayName = provider.name,
                    endpoint = provider.endpoint, apiKey = provider.apiKey,
                    modelName = provider.modelName
                )

                // Build session summary from this audience's perspective
                val sessionData = buildString {
                    if (memoryA.isNotBlank()) appendLine("本场直播核心内容：$memoryA")
                    if (memoryB.isNotBlank()) appendLine("最近摘要：$memoryB")
                    appendLine()
                    appendLine("本场全部弹幕（从旧到新）：")
                    allMessages.reversed().forEach { msg ->
                        val who = if (msg.audienceId == audience.id) "你" else msg.audienceName
                        appendLine("- $who: ${msg.content}")
                    }
                }

                val prompt = buildString {
                    appendLine("你是「${audience.name}」，以下是你的人设：")
                    appendLine(audience.persona)
                    appendLine()
                    if (audience.personalMemory.isNotBlank()) {
                        appendLine("你之前的记忆：")
                        appendLine(audience.personalMemory)
                        appendLine()
                    }
                    appendLine("以下是这场直播的完整经历：")
                    appendLine(sessionData)
                    appendLine()
                    appendLine("请用第一人称，结合你的人设和已有记忆，更新你的个人记忆。")
                    appendLine("保留最重要的：对主播的印象、和其他观众的互动、让你印象深刻的事、你的感受。")
                    appendLine("300字以内。只返回记忆文本，不要加标题或格式。")
                }

                try {
                    Log.d(TAG, "Compressing memory for '${audience.name}'...")
                    val memory = llm.chat(
                        systemPrompt = "你是一个记忆压缩助手。",
                        messages = listOf(ChatMessage(role = "user", content = prompt))
                    ).trim()

                    if (memory.isNotBlank() && memory.length < 1000) {
                        audienceDao.updateMemory(audience.id, memory)
                        Log.d(TAG, "'${audience.name}' memory saved (${memory.length} chars)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Memory compression failed for '${audience.name}'", e)
                }
            }

            // Clear messages after compression
            messageDao.deleteAll()
            Log.d(TAG, "Session messages cleared after memory compression")
        }
    }

    /** Full reset — clears all session data including debug info (called by reset button) */
    fun fullReset() {
        stop()
        emotionStates.clear()
        hasEnteredChat.clear()
        wallets.clear()
        likeCounts.clear()
        giftSpending.clear()
        lastLikerName = null
        recentGifts.clear()
        lastPostContent.clear()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun launchHeartbeat(
        audience: AiAudienceEntity,
        provider: LlmProviderEntity,
        allAudiences: List<AiAudienceEntity>
    ) {
        heartbeatJobs[audience.id]?.cancel()
        resetChannels[audience.id]?.close()

        val llm = OpenAiCompatibleProvider(
            id = provider.id,
            displayName = provider.name,
            endpoint = provider.endpoint,
            apiKey = provider.apiKey,
            modelName = provider.modelName
        )
        val otherNames = allAudiences.filter { it.id != audience.id }.map { it.name }
        // Per-provider mutex: serialize requests to same API to avoid identical context
        val mutex = providerMutexes.getOrPut(provider.id) { Mutex() }
        val resetCh = Channel<String>(Channel.CONFLATED)
        resetChannels[audience.id] = resetCh
        wallets.putIfAbsent(audience.id, INITIAL_BUDGET)

        heartbeatJobs[audience.id] = scope.launch {
            val baseIntervalMs = (audience.heartbeatIntervalSeconds * 1000L).coerceAtLeast(5000L)
            val jitterRange = 2000L // ±2 seconds
            val initialDelay = (audience.id * 3000L) % baseIntervalMs + (1000L..4000L).random()
            Log.d(TAG, "Heartbeat for '${audience.name}': base=${baseIntervalMs}ms, initial=${initialDelay}ms")

            val minCooldownMs = 5000L // minimum 5s between ticks for same audience
            var lastTickTime = 0L

            delay(initialDelay)
            while (isActive) {
                val jitter = (-jitterRange..jitterRange).random()
                val waitMs = (baseIntervalMs + jitter).coerceAtLeast(3000L)

                // Wait for either: heartbeat timeout OR reset signal (speech/mention trigger)
                val triggerType = select<String?> {
                    resetCh.onReceiveCatching { result -> result.getOrNull() }
                    onTimeout(waitMs) { "heartbeat" }
                }
                if (triggerType == null) {
                    Log.d(TAG, "Heartbeat channel closed for '${audience.name}', stopping loop")
                    break
                }

                // Enforce cooldown: skip if last tick was too recent
                val sinceLastTick = System.currentTimeMillis() - lastTickTime
                if (sinceLastTick < minCooldownMs) {
                    Log.d(TAG, "'${audience.name}' cooldown (${sinceLastTick}ms < ${minCooldownMs}ms), skipping $triggerType")
                    continue
                }

                try {
                    busyAudiences.add(audience.id)
                    lastTickTime = System.currentTimeMillis()
                    // Serialize requests per provider so audience B sees A's response in context
                    val result = mutex.withLock {
                        tick(audience, llm, otherNames, triggerType)
                    }
                    busyAudiences.remove(audience.id)
                    if (result != null) {
                        if (result.mentionedId != null) {
                            triggerAudience(result.mentionedId, "mention:${audience.name}")
                        }
                        if (result.isHighlight) {
                            triggerAllAudiences(except = audience.id, triggerType = "highlight")
                        }
                    }
                } catch (e: CancellationException) {
                    busyAudiences.remove(audience.id)
                    throw e
                } catch (e: Exception) {
                    busyAudiences.remove(audience.id)
                    Log.e(TAG, "Tick failed for '${audience.name}': ${e.message}", e)
                    delay(5000L)
                }
            }
        }
    }

    /** Detect named audiences in speech, trigger them + random others */
    private suspend fun triggerBySpeech(text: String) {
        val alreadyTriggered = mutableSetOf<Long>()

        // Check if speech contains any audience name → trigger them specifically
        val allAudiences = audienceDao.getEnabled()
        for (audience in allAudiences) {
            if (text.contains(audience.name)) {
                val ch = resetChannels[audience.id]
                if (ch != null && audience.id !in busyAudiences) {
                    Log.d(TAG, "Speech mentions '${audience.name}', triggering directly")
                    ch.trySend("speech_named")
                    alreadyTriggered.add(audience.id)
                }
            }
        }

        // Also trigger 1-2 random audiences (excluding already triggered + busy)
        triggerRandomAudiences("speech", exclude = alreadyTriggered)
    }

    /** Trigger 1-2 random non-busy audiences (skipping exclude set) */
    private fun triggerRandomAudiences(triggerType: String, exclude: Set<Long> = emptySet()) {
        val available = resetChannels.entries.filter { it.key !in busyAudiences && it.key !in exclude }
        if (available.isEmpty()) {
            Log.d(TAG, "No available audiences for random trigger")
            return
        }
        val count = if (available.size == 1) 1 else (1..2).random()
        val picked = available.shuffled().take(count)
        for ((id, ch) in picked) {
            Log.d(TAG, "Triggering audience id=$id via $triggerType (${available.size} available)")
            ch.trySend(triggerType)
        }
    }

    /** Trigger ALL other audiences (for 醒目留言 @all) */
    private fun triggerAllAudiences(except: Long, triggerType: String) {
        val targets = resetChannels.entries.filter { it.key != except && it.key !in busyAudiences }
        Log.d(TAG, "Highlight @all: triggering ${targets.size} audiences")
        for ((id, ch) in targets) {
            ch.trySend(triggerType)
        }
    }

    /** Trigger a specific audience immediately (for @mention), skip if busy */
    private fun triggerAudience(audienceId: Long, triggerType: String) {
        if (audienceId in busyAudiences) {
            Log.d(TAG, "Audience id=$audienceId busy, queueing $triggerType")
        }
        val ch = resetChannels[audienceId] ?: return
        ch.trySend(triggerType)
    }

    private data class TickResult(val mentionedId: Long?, val isHighlight: Boolean)

    private suspend fun tick(
        audience: AiAudienceEntity,
        llm: OpenAiCompatibleProvider,
        otherNames: List<String>,
        triggerType: String = "heartbeat"
    ): TickResult? {
        val isFirstEntry = audience.id !in hasEnteredChat
        Log.d(TAG, "tick '${audience.name}' [${triggerType}] firstEntry=$isFirstEntry emotion=${emotionStates[audience.id]}")

        val now = System.currentTimeMillis()
        val systemPrompt = buildSystemPrompt(audience, otherNames, isFirstEntry, now, triggerType)
        val messages = buildMessages(triggerType)

        val imageUri = if (audience.includeFrame) {
            latestFrameProvider?.invoke()?.let { BitmapEncoding.toDataUri(it) }
        } else null

        // Save full prompt for debug
        val fullPrompt = buildString {
            appendLine("=== System ===")
            appendLine(systemPrompt)
            appendLine("=== Messages (${messages.size}) ===")
            messages.forEach { appendLine("[${it.role}] ${it.content.take(200)}") }
            if (imageUri != null) appendLine("[image attached]")
        }
        lastPostContent[audience.id] = System.currentTimeMillis() to fullPrompt

        val response = llm.chat(
            systemPrompt = systemPrompt,
            messages = messages,
            imageDataUri = imageUri
        ).trim()

        // Save raw response for debug
        lastResponse[audience.id] = System.currentTimeMillis() to response

        if (response.isBlank() || response.contains(SILENCE_TOKEN, ignoreCase = true)) {
            Log.d(TAG, "'${audience.name}' chose silence [${triggerType}]")
            return null
        }

        // Parse two-line format: line1=danmaku, line2=emotion
        // Clean lines: strip "第X行：" / "第X行:" / numbering prefixes
        val lineCleanRegex = Regex("^(第[一二三1-3]行[：:]\\s*|[1-3][.、：:]\\s*)")
        val lines = response.lines()
            .filter { it.isNotBlank() }
            .map { it.trim().replace(lineCleanRegex, "") }
        val danmakuText = lines.firstOrNull()?.trim() ?: return null
        val newEmotion = lines.getOrNull(1)?.trim()

        if (danmakuText.contains(SILENCE_TOKEN, ignoreCase = true)) return null

        // Update emotion state
        if (!newEmotion.isNullOrBlank() && newEmotion.length <= 10) {
            emotionStates[audience.id] = newEmotion
            Log.d(TAG, "'${audience.name}' emotion: $newEmotion")
        }
        hasEnteredChat.add(audience.id)

        // Parse action (line 3)
        val actionLine = lines.getOrNull(2)?.trim() ?: "无"
        val action = parseAction(audience.id, audience.name, actionLine)
        Log.d(TAG, "'${audience.name}' says: ${danmakuText.take(40)} action=$actionLine [${triggerType}]")

        // If only action (silence + like/gift), still emit danmaku for the action
        val hasDanmaku = !danmakuText.contains(SILENCE_TOKEN, ignoreCase = true)

        // Parse @mention from danmaku text
        val mentionRegex = Regex("@(\\S+)")
        val mentionMatch = mentionRegex.find(danmakuText)
        var mentionedId: Long? = null
        var mentionedName: String? = null
        if (mentionMatch != null) {
            val targetName = mentionMatch.groupValues[1]
            val target = audienceDao.getEnabled().find {
                it.name.equals(targetName, ignoreCase = true)
            }
            if (target != null) {
                mentionedId = target.id
                mentionedName = target.name
            }
        }

        val displayContent = if (hasDanmaku) danmakuText else when (action) {
            is AudienceAction.Like -> "❤️"
            is AudienceAction.Gift -> "${action.gift.emoji} ${action.gift.displayName}"
            else -> return null // silence + no action = nothing
        }

        val message = AiAudienceMessageEntity(
            audienceId = audience.id,
            audienceName = audience.name,
            content = displayContent,
            mentionedAudienceId = mentionedId,
            mentionedAudienceName = mentionedName,
            triggerType = triggerType,
            timestamp = System.currentTimeMillis()
        )
        val msgId = messageDao.insert(message)

        _danmakuFlow.emit(DanmakuItem(
            id = msgId,
            audienceName = audience.name,
            content = displayContent,
            timestamp = message.timestamp,
            action = action
        ))

        refreshLiveState()
        val isHighlight = action is AudienceAction.Gift && action.gift == GiftType.HIGHLIGHT
        return TickResult(mentionedId, isHighlight)
    }

    private suspend fun buildSystemPrompt(
        audience: AiAudienceEntity,
        otherNames: List<String>,
        isFirstEntry: Boolean,
        now: Long,
        triggerType: String
    ): String = buildString {
        appendLine(audience.persona)
        appendLine()
        appendLine("你是直播间的一位观众，名字叫「${audience.name}」。")
        if (otherNames.isNotEmpty()) {
            appendLine("直播间里还有其他观众：${otherNames.joinToString("、")}。你可以用 @名字 的格式与他们对话。")
        }

        // Personal memory from past sessions
        if (audience.personalMemory.isNotBlank()) {
            appendLine()
            appendLine("你的过往记忆（这是你之前积累的印象和经历）：")
            appendLine(audience.personalMemory)
        }

        // Live context: three-layer scene memory + general memory + 1 latest visual
        val sceneCtx = sceneMemoryManager.buildSceneContext()
        val a = memoryManager.memoryA
        val b = memoryManager.latestMemoryB
        val latestVisual = memoryManager.recentVisual.lastOrNull()

        if (sceneCtx.isNotBlank() || a.isNotBlank() || b.isNotBlank()) {
            appendLine()
            appendLine("当前直播实况：")

            // Three-layer scene memory (structured)
            if (sceneCtx.isNotBlank()) {
                appendLine(sceneCtx)
            }

            // General long-term memory (includes speech compression)
            if (a.isNotBlank()) appendLine("核心记忆：$a")
            if (b.isNotBlank()) appendLine("稍早前摘要：$b")

            // Latest 1 raw visual for freshness
            if (latestVisual != null) {
                appendLine("最新画面：${latestVisual.second}")
            }
        }

        // Emotion continuity
        val emotion = emotionStates[audience.id]
        if (emotion != null) {
            appendLine("你现在的情绪状态是：$emotion。让这个情绪自然地影响你的发言风格和用词。")
        }
        appendLine()

        // Entry mode
        if (isFirstEntry) {
            appendLine("【你刚进入直播间】请先发一条自然的入场打招呼弹幕，比如：来了来了、刚到聊啥呢、终于蹲到直播了、之类的，要符合你的人设性格。")
            appendLine()
        }

        appendLine("规则：")
        appendLine("- 【最高优先级】如果主播刚说了话，必须优先回应主播的语音内容，像真实观众和主播互动一样")
        appendLine("- 如果主播没说话，可以根据画面内容或其他观众的发言来评论")
        appendLine("- 弹幕长度要自然多变：可以是纯表情、一个字、几个字、一句话、偶尔两句话，也可以是一段话")
        appendLine("- 可以使用emoji表情，也可以发纯表情弹幕")
        appendLine("- 尽量避免重复自己之前说过的内容，换个角度或说点新的，但偶尔的附和、起哄是可以的")
        appendLine("- 如果当前确实没什么可说的，返回 $SILENCE_TOKEN 保持沉默")
        appendLine("- 使用简体中文")
        appendLine()
        // Likes & gifts economy
        val budget = wallets[audience.id] ?: INITIAL_BUDGET
        appendLine()
        appendLine("互动系统（你有 ${budget} 币）：")
        appendLine("- 点赞（免费）：觉得精彩时可以点，但要真心认可，不要无脑刷")
        appendLine("- 小花（1币）：随手打赏，表示支持")
        appendLine("- 火箭（10币）：比较认可主播的表现")
        appendLine("- 醒目留言（30币）：你的弹幕会在直播间正中央悬挂30秒！主播一定能看到。适合想引起主播注意、提要求、表白、整活的时候用")
        appendLine("- 皇冠（50币）：很高的认可，只在真正震撼的时刻才给")
        appendLine("- 超级火箭（100币）：倾家荡产级别，只有一次机会，代表最高敬意")
        appendLine()
        appendLine("【直播间潜规则】")
        appendLine("- 刷礼物能让主播更关注你，主播可能会点名感谢你、回应你的要求")
        appendLine("- 你可以用礼物来\"要求\"主播做一些事情（比如\"主播唱首歌呗\"配上火箭，主播更可能答应）")
        appendLine("- 醒目留言是性价比最高的曝光方式——30币就能让全直播间看到你的话")
        appendLine("- 但不要当冤大头，主播表现不好的时候别送贵重礼物，可以适当表达不满")
        appendLine("- 预算花完就真的没了，要精打细算")
        appendLine()
        appendLine("【重要】回复格式分为三行：")
        appendLine("第一行：你的弹幕内容（不要加引号或前缀，如果只想点赞/送礼不说话写 $SILENCE_TOKEN。注意：如果送醒目留言，第一行就是会在直播间正中央悬挂30秒的内容，一定要写有分量的话！）")
        appendLine("第二行：你现在的情绪（一个词：开心、无聊、激动、感动、好奇、震惊、平静、搞笑等）")
        appendLine("第三行：动作（写 无 / 点赞 / 送礼:小花 / 送礼:火箭 / 送礼:醒目留言 / 送礼:皇冠 / 送礼:超级火箭）")

        // --- Dynamic context: chat history, @mentions, speech ---
        appendLine()

        // Recent danmaku
        val recentMessages = messageDao.getRecent(MAX_AI_CHAT_HISTORY).reversed()
        if (recentMessages.isNotEmpty()) {
            appendLine("最近弹幕（越靠后越新）：")
            for (msg in recentMessages) {
                val ago = (now - msg.timestamp) / 1000
                val label = when {
                    ago < 10 -> "刚刚"
                    ago < 60 -> "${ago}秒前"
                    else -> "${ago / 60}分钟前"
                }
                val who = if (msg.audienceId == audience.id) "你" else msg.audienceName
                appendLine("- [$label] $who: ${msg.content}")
            }
            appendLine()
        }

        // Pending @mentions
        val sinceTime = lastMentionCheckTime.getOrDefault(audience.id, 0L)
        val mentions = messageDao.getPendingMentions(audience.id, sinceTime)
        if (mentions.isNotEmpty()) {
            lastMentionCheckTime[audience.id] = mentions.maxOf { it.timestamp }
            for (mention in mentions) {
                if (recentMessages.none { it.id == mention.id }) {
                    appendLine("【有人@你】${mention.audienceName}: ${mention.content}")
                }
            }
            appendLine()
        }

        // Speech transcripts
        val twoMinAgo = now - 2 * 60 * 1000
        val speechEntries = speechTranscriptProvider?.invoke()?.filter { it.first >= twoMinAgo }
        val recentSpeech = speechEntries?.take(8) // newest first
        Log.d(TAG, "buildSystemPrompt '${audience.name}': speech=${speechEntries?.size ?: 0}")
        if (!recentSpeech.isNullOrEmpty()) {
            appendLine("【主播语音】（越靠前越新，优先回应最新的）：")
            for ((ts, text) in recentSpeech) {
                val ago = (now - ts) / 1000
                val label = when {
                    ago < 10 -> "刚刚"
                    ago < 60 -> "${ago}秒前"
                    else -> "${ago / 60}分钟前"
                }
                appendLine("- [$label] $text")
            }
        }
    }

    private fun buildMessages(triggerType: String = "heartbeat"): List<ChatMessage> {
        val prompt = when {
            triggerType == "highlight" ->
                "直播间有人刷了醒目留言！请对这条醒目留言做出反应（可以起哄、附和、吐槽、或发表你的看法）："
            triggerType.startsWith("mention:") -> {
                val who = triggerType.removePrefix("mention:")
                "「${who}」@了你，请回应「${who}」（或返回 $SILENCE_TOKEN 保持沉默）："
            }
            triggerType == "mention" ->
                "有人@了你，请回应对方（或返回 $SILENCE_TOKEN 保持沉默）："
            triggerType == "speech_named" ->
                "主播刚提到了你的名字！请回应主播："
            triggerType == "speech" ->
                "主播刚说了话，请像真实观众一样回应主播（或返回 $SILENCE_TOKEN 保持沉默）："
            else ->
                "请根据直播内容发表评论（或返回 $SILENCE_TOKEN 保持沉默）："
        }
        return listOf(ChatMessage(role = "user", content = prompt))
    }

    private fun parseAction(audienceId: Long, audienceName: String, actionLine: String): AudienceAction {
        val line = actionLine.lowercase()
        return when {
            line.contains("送礼") || line.contains("gift") -> {
                val gift = GiftType.fromName(actionLine)
                if (gift != null) {
                    val budget = wallets[audienceId] ?: 0
                    if (budget >= gift.cost) {
                        wallets[audienceId] = budget - gift.cost
                        giftSpending[audienceName] = (giftSpending[audienceName] ?: 0) + gift.cost
                        Log.d(TAG, "'$audienceName' sent ${gift.displayName}, remaining=${budget - gift.cost}")
                        recentGifts.add(GiftEvent(
                            audienceName = audienceName,
                            gift = gift,
                            timestamp = System.currentTimeMillis()
                        ))
                        AudienceAction.Gift(gift)
                    } else {
                        Log.d(TAG, "Audience $audienceId can't afford ${gift.displayName}, budget=$budget")
                        AudienceAction.None
                    }
                } else AudienceAction.None
            }
            line.contains("点赞") || line.contains("like") -> {
                val now = System.currentTimeMillis()
                val last = lastLikeTime[audienceId] ?: 0L
                if (now - last >= LIKE_COOLDOWN_MS) {
                    lastLikeTime[audienceId] = now
                    likeCounts[audienceName] = (likeCounts[audienceName] ?: 0) + 1
                    lastLikerName = audienceName
                    Log.d(TAG, "'$audienceName' liked, count=${likeCounts[audienceName]}")
                    AudienceAction.Like
                } else {
                    Log.d(TAG, "Audience $audienceId like on cooldown")
                    AudienceAction.None
                }
            }
            else -> AudienceAction.None
        }
    }

    /** Get debug info for all active audiences */
    fun getAudienceDebugInfos(): List<AudienceDebugInfo> {
        return resetChannels.keys.mapNotNull { id ->
            val audienceName = audienceNames[id] ?: "audience-$id"
            val post = lastPostContent[id]
            AudienceDebugInfo(
                audienceId = id,
                audienceName = audienceName,
                emotion = emotionStates[id],
                wallet = wallets[id] ?: INITIAL_BUDGET,
                likeCount = likeCounts[audienceName] ?: 0,
                lastPostContent = post?.second,
                lastPostTime = post?.first,
                hasEntered = id in hasEnteredChat
            )
        }
    }

    /** Get audience wallet balance */
    fun getWallet(audienceId: Long): Int = wallets[audienceId] ?: INITIAL_BUDGET

    /** Set initial budget (called from management UI) */
    fun setInitialBudget(audienceId: Long, budget: Int) {
        wallets[audienceId] = budget
    }

    /** Get full last post for a specific audience */
    fun getLastPost(audienceId: Long): String? = lastPostContent[audienceId]?.second

    /** Get full last raw response for a specific audience */
    fun getLastResponse(audienceId: Long): String? = lastResponse[audienceId]?.second

    /** Get memory system snapshot */
    fun getMemorySnapshot(): MemorySnapshot = MemorySnapshot(
        memoryA = memoryManager.memoryA,
        memoryB = memoryManager.latestMemoryB,
        recentVisual = memoryManager.recentVisual.toList(),
        rawBufferSize = memoryManager.rawSinceLastB.size
    )

    private suspend fun refreshLiveState() {
        val messages = messageDao.getRecent(MAX_LIVE_MESSAGES)
        while (recentGifts.size > 10) recentGifts.removeAt(0)

        val likeBoard = likeCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { LikeRankEntry(it.key, it.value) }

        val giftBoard = giftSpending.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { GiftRankEntry(it.key, it.value) }

        _liveState.value = _liveState.value.copy(
            messages = messages,
            likeBoard = likeBoard,
            lastLiker = lastLikerName,
            giftBoard = giftBoard,
            recentGifts = recentGifts.takeLast(3)
        )
    }
}
