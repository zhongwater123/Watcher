package com.example.watcher.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.watcher.data.local.ClassroomNoteFollowupDao
import com.example.watcher.data.local.ClassroomTranscriptConsumptionDao
import com.example.watcher.data.local.TimelineEventDao
import com.example.watcher.data.local.VideoAiTraceDao
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.local.VideoProcessRunDao
import com.example.watcher.data.local.VideoProcessTaskDao
import com.example.watcher.data.local.VideoRemoteFileBindingDao
import com.example.watcher.data.local.VideoSegmentRunDao
import com.example.watcher.data.local.VideoSpeechTranscriptDao
import com.example.watcher.data.model.ClassroomInlineQuestionType
import com.example.watcher.data.model.ClassroomNoteFollowupEntity
import com.example.watcher.data.model.ClassroomNoteFollowupStatus
import com.example.watcher.data.model.ClassroomRecordingInput
import com.example.watcher.data.model.ClassroomSpeechRecognitionConfig
import com.example.watcher.data.model.ClassroomTranscriptConsumptionEntity
import com.example.watcher.data.model.ClassroomTranscriptUiItem
import com.example.watcher.data.model.ClassroomTranscriptWeightLevel
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import com.example.watcher.data.model.VideoTaskPlan
import com.example.watcher.data.remote.ArkStreamingClient
import com.example.watcher.data.remote.DoubaoApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val CLASSROOM_VISUAL_TAG = "Watcher.Classroom.Visual"

class VideoProcessRepository(
    private val appContext: Context,
    private val apiService: DoubaoApiService,
    private val taskDao: VideoProcessTaskDao,
    private val runDao: VideoProcessRunDao,
    private val segmentRunDao: VideoSegmentRunDao,
    private val audioAssetDao: VideoAudioAssetDao,
    private val remoteFileBindingDao: VideoRemoteFileBindingDao,
    private val speechTranscriptDao: VideoSpeechTranscriptDao,
    private val classroomTranscriptConsumptionDao: ClassroomTranscriptConsumptionDao,
    private val classroomNoteFollowupDao: ClassroomNoteFollowupDao,
    private val timelineEventDao: TimelineEventDao,
    private val aiTraceDao: VideoAiTraceDao,
    private val llmWalletRepository: LlmWalletRepository,
    private val recorder: MjpegVideoRecorder = MjpegVideoRecorder(),
    private val segmentMerger: VideoSegmentMerger = VideoSegmentMerger(),
    private val audioAssetBuilder: VideoAudioAssetBuilder = VideoAudioAssetBuilder(),
    private val streamingClient: ArkStreamingClient = ArkStreamingClient()
) {
    private val aiTraceLogger = VideoAiTraceLogger(aiTraceDao)
    private val classroomFrameEvidenceCache = ClassroomFrameEvidenceCache(appContext)
    private val classroomAstSourceSubtitleStore = ClassroomAstSourceSubtitleStore(File(appContext.filesDir, "video_runs"))
    private val inlineQuestionMutex = Mutex()
    private val noteFollowupMutex = Mutex()

    private val planner = VideoTaskPlanner(
        apiService = apiService,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val remoteFileResolver = VideoRemoteFileResolver(
        apiService = apiService,
        bindingDao = remoteFileBindingDao,
        apiKey = ArkConfig.apiKey
    )

    private val segmentAnalyzer = VideoSegmentAnalyzer(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val segmentProcessor = VideoSegmentProcessor(
        apiService = apiService,
        segmentRunDao = segmentRunDao,
        remoteFileResolver = remoteFileResolver,
        segmentAnalyzer = segmentAnalyzer,
        apiKey = ArkConfig.apiKey
    )

    private val segmentRecorder = VideoSegmentRecorder(
        recorder = recorder,
        audioAssetBuilder = audioAssetBuilder,
        remoteFileResolver = remoteFileResolver,
        segmentRunDao = segmentRunDao,
        audioAssetDao = audioAssetDao
    )

    private val mediaAssembler = VideoMediaAssembler(
        segmentMerger = segmentMerger,
        remoteFileResolver = remoteFileResolver
    )

    private val reportSummarizer = VideoReportSummarizer(
        apiService = apiService,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val chunkAnalyzer = VideoEvidenceChunkAnalyzer(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val reportRefiner = VideoReportRefiner(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val audioOutlineProcessor = AudioOutlineProcessor(
        apiService = apiService,
        audioAssetDao = audioAssetDao,
        remoteFileResolver = remoteFileResolver,
        audioAssetBuilder = audioAssetBuilder,
        audioModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val classroomSegmentAnalyzer = ClassroomSegmentAnalyzer(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val classroomSegmentProcessor = ClassroomSegmentProcessor(
        apiService = apiService,
        segmentRunDao = segmentRunDao,
        remoteFileResolver = remoteFileResolver,
        segmentAnalyzer = classroomSegmentAnalyzer,
        apiKey = ArkConfig.apiKey
    )

    private val classroomAudioOutlineProcessor = ClassroomAudioOutlineProcessor(
        apiService = apiService,
        audioAssetDao = audioAssetDao,
        remoteFileResolver = remoteFileResolver,
        audioAssetBuilder = audioAssetBuilder,
        audioModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val classroomNoteSynthesizer = ClassroomNoteSynthesizer(
        apiService = apiService,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val classroomVisualEvidenceAnalyzer = ClassroomVisualEvidenceAnalyzer(
        apiService = apiService,
        videoModel = ArkConfig.videoAnalysisModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val classroomInlineQuestionProcessor = ClassroomInlineQuestionProcessor(
        apiService = apiService,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val classroomNoteFollowupProcessor = ClassroomNoteFollowupProcessor(
        apiService = apiService,
        planningModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger
    )

    private val executionOrchestrator = VideoExecutionOrchestrator(
        taskDao = taskDao,
        runDao = runDao,
        timelineEventDao = timelineEventDao,
        saveTask = ::saveTask,
        segmentProcessor = segmentProcessor,
        segmentRecorder = segmentRecorder,
        mediaAssembler = mediaAssembler,
        reportSummarizer = reportSummarizer,
        chunkAnalyzer = chunkAnalyzer,
        reportRefiner = reportRefiner,
        audioOutlineProcessor = audioOutlineProcessor,
        remoteFileResolver = remoteFileResolver,
        traceLogger = aiTraceLogger
    )

    private val classroomRecordingOrchestrator = ClassroomRecordingOrchestrator(
        taskDao = taskDao,
        runDao = runDao,
        timelineEventDao = timelineEventDao,
        saveTask = ::saveTask,
        segmentProcessor = classroomSegmentProcessor,
        segmentRecorder = segmentRecorder,
        mediaAssembler = mediaAssembler,
        audioOutlineProcessor = classroomAudioOutlineProcessor,
        noteSynthesizer = classroomNoteSynthesizer,
        visualEvidenceAnalyzer = classroomVisualEvidenceAnalyzer,
        appContext = appContext,
        speechTranscriptDao = speechTranscriptDao,
        apiService = apiService,
        realtimeInsightModel = ArkConfig.videoPlanningModel,
        apiKey = ArkConfig.apiKey,
        traceLogger = aiTraceLogger,
        frameEvidenceCache = classroomFrameEvidenceCache,
        astSourceSubtitleStore = classroomAstSourceSubtitleStore
    )

    fun observeTasks(): Flow<List<VideoProcessTask>> = taskDao.observeTasks()

    fun observeRecentRuns(): Flow<List<VideoProcessRun>> = runDao.observeRecentRuns()

    fun observeRecentClassroomRuns(limit: Int = 20): Flow<List<VideoProcessRun>> =
        runDao.observeRecentRunsForScenario(RecordingScenario.ClassLecture.value, limit)

    suspend fun getRunById(runId: Long): VideoProcessRun? = runDao.getRunById(runId)

    fun observeTimelineForRun(runId: Long) = timelineEventDao.observeEventsForRun(runId)

    fun observeSpeechForRun(runId: Long) = speechTranscriptDao.observeForRun(runId)

    fun observeAiTraceForRun(runId: Long) = aiTraceDao.observeForRun(runId)

    fun observeClassroomNoteFollowups(runId: Long): Flow<List<ClassroomNoteFollowupEntity>> =
        classroomNoteFollowupDao.observeForRun(runId)

    fun observeClassroomNoteMaterials(runId: Long): Flow<List<VideoRemoteFileBindingEntity>> =
        remoteFileBindingDao.observeForRunAndAssetKind(runId, VideoRemoteAssetKind.ClassroomNoteMaterial.value)

    suspend fun attachClassroomNoteMaterial(runId: Long, uri: Uri): VideoRemoteFileBindingEntity =
        withContext(Dispatchers.IO) {
            runDao.getRunById(runId) ?: error("Classroom run not found.")
            val materialFile = importClassroomNoteMaterial(runId, uri)
            val mediaType = appContext.contentResolver.getType(uri)
                ?: materialFile.guessMediaType()
            remoteFileResolver.resolveUserDataFile(
                file = materialFile,
                runId = runId,
                assetKind = VideoRemoteAssetKind.ClassroomNoteMaterial,
                mediaType = mediaType
            ).binding
        }

    fun observeClassroomTranscriptItems(runId: Long): Flow<List<ClassroomTranscriptUiItem>> {
        return combine(
            speechTranscriptDao.observeForRun(runId),
            classroomTranscriptConsumptionDao.observeForRun(runId),
            classroomAstSourceSubtitleStore.observeVersion(runId)
        ) { transcripts, consumptions, _ ->
            val consumptionByTranscript = consumptions.associateBy { it.transcriptId }
            val sourceSubtitles = if (transcripts.any { it.source == "live_ast" }) {
                classroomAstSourceSubtitleStore.load(runId)
            } else {
                emptyList()
            }
            transcripts
                .filter { it.isFinal || it.definite }
                .map { transcript ->
                    val consumption = consumptionByTranscript[transcript.id]
                    val sourceText = if (transcript.source == "live_ast") {
                        classroomAstSourceSubtitleStore.findSourceTextFor(
                            subtitles = sourceSubtitles,
                            startMs = transcript.globalStartMs,
                            endMs = transcript.globalEndMs
                        ).orEmpty()
                    } else {
                        ""
                    }
                    ClassroomTranscriptUiItem(
                        key = transcript.id.takeIf { it > 0L }?.toString()
                            ?: "${transcript.runId}-${transcript.globalStartMs}-${transcript.text.hashCode()}",
                        runId = transcript.runId,
                        transcriptId = transcript.id.takeIf { it > 0L },
                        timestampLabel = transcript.displayTimestamp,
                        globalStartMs = transcript.globalStartMs,
                        globalEndMs = transcript.globalEndMs,
                        text = transcript.text,
                        sourceText = sourceText,
                        selectionOrder = consumption?.selectionOrder?.takeIf { it > 0 },
                        weightLevel = ClassroomTranscriptWeightLevel.fromValue(consumption?.weightLevel),
                        isSelected = consumption?.isSelected == true,
                        isAnswered = consumption?.isAnswered == true,
                        answerText = consumption?.answerText.orEmpty()
                    )
                }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun toggleClassroomTranscriptSelection(runId: Long, transcriptId: Long) {
        val existing = classroomTranscriptConsumptionDao.getForRun(runId)
        if (existing.firstOrNull { it.transcriptId == transcriptId }?.isAnswered == true) return
        val currentSelections = existing
            .filter { it.isSelected && !it.isAnswered && it.selectionOrder > 0 }
            .sortedBy { it.selectionOrder }
            .map { entity ->
                ClassroomTranscriptSelection(
                    transcriptId = entity.transcriptId,
                    selectionOrder = entity.selectionOrder,
                    weightLevel = ClassroomTranscriptWeightLevel.fromValue(entity.weightLevel)
                        ?: ClassroomTranscriptSelectionPolicy.weightForOrder(entity.selectionOrder)
                )
            }
        val updatedSelections = ClassroomTranscriptSelectionPolicy.toggleSelection(currentSelections, transcriptId)
        val updatedById = updatedSelections.associateBy { it.transcriptId }
        val existingById = existing.associateBy { it.transcriptId }
        val affectedIds = (currentSelections.map { it.transcriptId } + transcriptId).toSet()
        val now = System.currentTimeMillis()
        classroomTranscriptConsumptionDao.upsertAll(
            affectedIds.map { id ->
                val previous = existingById[id]
                val selection = updatedById[id]
                (previous ?: ClassroomTranscriptConsumptionEntity(
                    runId = runId,
                    transcriptId = id,
                    createdAt = now
                )).copy(
                    selectionOrder = selection?.selectionOrder ?: 0,
                    weightLevel = selection?.weightLevel?.value.orEmpty(),
                    isSelected = selection != null,
                    updatedAt = now
                )
            }
        )
    }

    suspend fun answerClassroomInlineQuestion(
        runId: Long,
        questionType: ClassroomInlineQuestionType,
        realtimeInsights: List<String>
    ): ClassroomInlineQuestionResult = inlineQuestionMutex.withLock {
        val run = runDao.getRunById(runId) ?: error("Classroom run not found.")
        val taskEntity = taskDao.getTaskById(run.taskId)
        val task = taskEntity?.let(VideoProcessTaskDraft::fromEntity)
            ?: VideoProcessTaskDraft(title = run.taskTitle, userRequirement = run.taskRequirement, taskId = run.taskId)
        val transcripts = speechTranscriptDao.getForRun(runId)
        val consumptions = classroomTranscriptConsumptionDao.getForRun(runId)
        val selections = consumptions
            .filter { it.isSelected && !it.isAnswered && it.selectionOrder > 0 }
            .sortedBy { it.selectionOrder }
            .map { ClassroomTranscriptSelection(it.transcriptId, it.selectionOrder) }
        check(selections.size >= ClassroomTranscriptSelectionPolicy.MIN_SELECTIONS_TO_ASK) {
            "至少选择 3 条字幕后才能快速提问。"
        }
        val questionContext = ClassroomTranscriptSelectionPolicy.buildQuestionContext(transcripts, selections)
        val allContextTranscripts = buildClassroomInlineAllAsrContext(transcripts)
            .ifEmpty { questionContext.contextTranscripts }
        val contextStartMs = allContextTranscripts.minOfOrNull { it.globalStartMs } ?: questionContext.contextStartMs
        val contextEndMs = allContextTranscripts.maxOfOrNull { it.globalEndMs.coerceAtLeast(it.globalStartMs) }
            ?: questionContext.contextEndMs
        val frameEvidence = resolveClassroomInlineFrameEvidence(
            runId = runId,
            transcripts = transcripts,
            selections = selections
        )
        val result = classroomInlineQuestionProcessor.answer(
            runId = runId,
            traceId = run.aiTraceId,
            task = task,
            questionType = questionType,
            selectedTranscripts = questionContext.selectedTranscripts,
            contextTranscripts = allContextTranscripts,
            realtimeInsights = realtimeInsights,
            contextStartMs = contextStartMs,
            contextEndMs = contextEndMs,
            frameEvidence = frameEvidence
        )
        val now = System.currentTimeMillis()
        val selectedIds = selections.map { it.transcriptId }.toSet()
        val coreQuestionText = questionContext.selectedTranscripts.firstOrNull()?.text.orEmpty()
        classroomTranscriptConsumptionDao.upsertAll(
            consumptions
                .filter { it.transcriptId in selectedIds }
                .map { entity ->
                    entity.copy(
                        isSelected = false,
                        isAnswered = true,
                        questionType = questionType.value,
                        questionText = coreQuestionText,
                        answerText = result.answerText,
                        contextStartMs = contextStartMs,
                        contextEndMs = contextEndMs,
                        visualFrameTimestampMs = frameEvidence?.frameTimestampMs ?: 0L,
                        visualFramePath = frameEvidence?.framePath.orEmpty(),
                        visualFrameStatus = frameEvidence?.status ?: "unavailable",
                        updatedAt = now
                    )
                }
        )
        result
    }

    private fun buildClassroomInlineAllAsrContext(
        transcripts: List<VideoSpeechTranscriptEntity>
    ): List<VideoSpeechTranscriptEntity> {
        return transcripts
            .filter { transcript -> transcript.text.isNotBlank() && (transcript.isFinal || transcript.definite) }
            .sortedWith(compareBy<VideoSpeechTranscriptEntity> { it.globalStartMs }.thenBy { it.id })
    }

    private suspend fun resolveClassroomInlineFrameEvidence(
        runId: Long,
        transcripts: List<VideoSpeechTranscriptEntity>,
        selections: List<ClassroomTranscriptSelection>
    ): ClassroomInlineFrameEvidence? {
        val targetMs = ClassroomTranscriptSelectionPolicy.coreTranscriptTargetMs(transcripts, selections)
            ?: return null
        val evidence = classroomFrameEvidenceCache.extractTestVideoFrame(runId, targetMs)
            ?: classroomFrameEvidenceCache.findNearest(runId, targetMs)
        if (evidence != null) {
            Log.i(
                CLASSROOM_VISUAL_TAG,
                "inline question frame run=$runId targetMs=$targetMs source=${evidence.source} " +
                    "frameMs=${evidence.frameTimestampMs} size=${evidence.width}x${evidence.height} hash=${evidence.sha256.take(12)}"
            )
        } else {
            Log.w(CLASSROOM_VISUAL_TAG, "inline question frame unavailable run=$runId targetMs=$targetMs")
        }
        return evidence
    }

    suspend fun askClassroomNoteFollowup(
        runId: Long,
        question: String,
        streamingBuffer: String = ""
    ): ClassroomNoteFollowupEntity = noteFollowupMutex.withLock {
        val trimmedQuestion = question.trim()
        check(trimmedQuestion.isNotBlank()) { "请输入要追问的问题。" }
        val now = System.currentTimeMillis()
        val run = runDao.getRunById(runId) ?: error("Classroom run not found.")
        val context = buildClassroomNoteFollowupContext(
            run = run,
            streamingBuffer = streamingBuffer,
            excludeFollowupId = null
        )
        val running = ClassroomNoteFollowupEntity(
            runId = runId,
            question = trimmedQuestion,
            status = ClassroomNoteFollowupStatus.Running.value,
            contextStage = context.stage.value,
            conversationContextIdsJson = encodeFollowupConversationIds(context.conversationTurns.map { it.id }),
            createdAt = now,
            updatedAt = now
        ).let { entity -> entity.copy(id = classroomNoteFollowupDao.insert(entity)) }
        executeClassroomNoteFollowup(running, run, context)
    }

    suspend fun retryClassroomNoteFollowup(
        followupId: Long,
        streamingBuffer: String = ""
    ): ClassroomNoteFollowupEntity = noteFollowupMutex.withLock {
        val existing = classroomNoteFollowupDao.getById(followupId) ?: error("Follow-up question not found.")
        val run = runDao.getRunById(existing.runId) ?: error("Classroom run not found.")
        val context = buildClassroomNoteFollowupContext(
            run = run,
            streamingBuffer = streamingBuffer,
            excludeFollowupId = followupId
        )
        val running = existing.copy(
            status = ClassroomNoteFollowupStatus.Running.value,
            contextStage = context.stage.value,
            conversationContextIdsJson = encodeFollowupConversationIds(context.conversationTurns.map { it.id }),
            errorMessage = "",
            updatedAt = System.currentTimeMillis()
        )
        classroomNoteFollowupDao.update(running)
        executeClassroomNoteFollowup(running, run, context)
    }

    suspend fun regenerateClassroomNoteFollowupWithFinalNote(followupId: Long): ClassroomNoteFollowupEntity =
        retryClassroomNoteFollowup(followupId, streamingBuffer = "")

    suspend fun deleteClassroomNoteFollowupsForRun(runId: Long) {
        classroomNoteFollowupDao.deleteByRunId(runId)
    }

    suspend fun deleteClassroomNoteFollowup(followupId: Long) {
        classroomNoteFollowupDao.deleteById(followupId)
    }

    private fun importClassroomNoteMaterial(runId: Long, uri: Uri): File {
        val resolver = appContext.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { "material_${System.currentTimeMillis()}" }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetDir = File(appContext.filesDir, "classroom_materials/run_$runId").apply { mkdirs() }
        val target = File(targetDir, "${System.currentTimeMillis()}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
            }
        } ?: error("Unable to read selected material.")
        return target
    }

    private fun File.guessMediaType(): String {
        val extension = extension.lowercase()
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "md", "txt" -> "text/plain"
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "application/octet-stream"
            }
    }

    private suspend fun executeClassroomNoteFollowup(
        entity: ClassroomNoteFollowupEntity,
        run: VideoProcessRun,
        context: ClassroomNoteFollowupContext
    ): ClassroomNoteFollowupEntity {
        val task = resolveTaskDraftForRun(run)
        val completed = runCatching {
            classroomNoteFollowupProcessor.answer(
                runId = run.id,
                traceId = run.aiTraceId,
                task = task,
                question = entity.question,
                context = context
            )
        }.fold(
            onSuccess = { result ->
                entity.copy(
                    answer = result.answer,
                    status = ClassroomNoteFollowupStatus.Completed.value,
                    sourceRefsJson = ClassroomNoteFollowupResultParser.sourceRefsToJson(result.sourceRefs),
                    rawResponse = result.rawResponse,
                    errorMessage = "",
                    updatedAt = System.currentTimeMillis()
                )
            },
            onFailure = { error ->
                entity.copy(
                    status = ClassroomNoteFollowupStatus.Failed.value,
                    errorMessage = error.message ?: "课后追问失败",
                    updatedAt = System.currentTimeMillis()
                )
            }
        )
        classroomNoteFollowupDao.update(completed)
        return completed
    }

    private suspend fun buildClassroomNoteFollowupContext(
        run: VideoProcessRun,
        streamingBuffer: String,
        excludeFollowupId: Long?
    ): ClassroomNoteFollowupContext {
        val previousTurns = classroomNoteFollowupDao
            .getRecentCompletedForRun(run.id, FOLLOWUP_CONVERSATION_LIMIT)
            .filterNot { it.id == excludeFollowupId }
        return ClassroomNoteFollowupContextFactory.build(
            run = run,
            streamingBuffer = streamingBuffer,
            transcripts = speechTranscriptDao.getForRun(run.id),
            timelineEvents = timelineEventDao.getEventsForRun(run.id),
            segments = segmentRunDao.getSegmentsForRun(run.id),
            previousTurns = previousTurns
        )
    }

    private suspend fun resolveTaskDraftForRun(run: VideoProcessRun): VideoProcessTaskDraft {
        return taskDao.getTaskById(run.taskId)?.let(VideoProcessTaskDraft::fromEntity)
            ?: VideoProcessTaskDraft(title = run.taskTitle, userRequirement = run.taskRequirement, taskId = run.taskId)
    }

    private fun encodeFollowupConversationIds(ids: List<Long>): String {
        return ids.joinToString(prefix = "[", postfix = "]")
    }

    suspend fun saveTask(draft: VideoProcessTaskDraft): VideoProcessTaskDraft {
        val normalized = draft.normalized()
        val existing = normalized.taskId?.let { taskDao.getTaskById(it) }
        val entity = normalized.toEntity(existing)
        val taskId = taskDao.upsert(entity)
        return VideoProcessTaskDraft.fromEntity(
            entity.copy(id = if (entity.id == 0L) taskId else entity.id)
        )
    }

    suspend fun deleteTask(id: Long) {
        taskDao.deleteById(id)
    }

    suspend fun planVideoTask(userInput: String, frame: Bitmap?): Result<VideoTaskPlan> {
        return planner.planVideoTask(userInput, frame)
    }

    suspend fun executeTask(
        draft: VideoProcessTaskDraft,
        streamingOutputEnabled: Boolean,
        latestFrameProvider: () -> Bitmap?,
        outputRoot: File,
        recordingInput: ClassroomRecordingInput = ClassroomRecordingInput.LiveCamera,
        shouldStopRequested: () -> Boolean = { false },
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): VideoExecutionResult {
        return executionOrchestrator.executeTask(
            draft = draft,
            streamingOutputEnabled = streamingOutputEnabled,
            latestFrameProvider = latestFrameProvider,
            outputRoot = outputRoot,
            recordingInput = recordingInput,
            shouldStopRequested = shouldStopRequested,
            onStatus = onStatus
        )
    }

    suspend fun executeClassroomRecording(
        draft: VideoProcessTaskDraft,
        streamingOutputEnabled: Boolean,
        latestFrameProvider: () -> Bitmap?,
        latestFrameSourceProvider: () -> String = { "" },
        outputRoot: File,
        recordingInput: ClassroomRecordingInput = ClassroomRecordingInput.LiveCamera,
        speechRecognitionConfig: ClassroomSpeechRecognitionConfig = ClassroomSpeechRecognitionConfig.Default,
        shouldStopRequested: () -> Boolean = { false },
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ): VideoExecutionResult {
        return classroomRecordingOrchestrator.executeClassroomRecording(
            draft = draft,
            streamingOutputEnabled = streamingOutputEnabled,
            latestFrameProvider = latestFrameProvider,
            latestFrameSourceProvider = latestFrameSourceProvider,
            outputRoot = outputRoot,
            recordingInput = recordingInput,
            speechRecognitionConfig = speechRecognitionConfig,
            shouldStopRequested = shouldStopRequested,
            onStatus = onStatus
        )
    }

    suspend fun markRunFailed(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        error: Throwable,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        executionOrchestrator.markRunFailed(
            runId = runId,
            segmentIndex = segmentIndex,
            segmentCount = segmentCount,
            streamingEnabled = streamingEnabled,
            error = error,
            onStatus = onStatus
        )
    }

    suspend fun markClassroomRunFailed(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        error: Throwable,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        classroomRecordingOrchestrator.markRunFailed(
            runId = runId,
            segmentIndex = segmentIndex,
            segmentCount = segmentCount,
            streamingEnabled = streamingEnabled,
            error = error,
            onStatus = onStatus
        )
    }

    suspend fun markRunCancelled(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        executionOrchestrator.markRunCancelled(
            runId = runId,
            segmentIndex = segmentIndex,
            segmentCount = segmentCount,
            streamingEnabled = streamingEnabled,
            onStatus = onStatus
        )
    }

    suspend fun markClassroomRunCancelled(
        runId: Long,
        segmentIndex: Int,
        segmentCount: Int,
        streamingEnabled: Boolean,
        onStatus: suspend (VideoExecutionStatusUpdate) -> Unit
    ) {
        classroomRecordingOrchestrator.markRunCancelled(
            runId = runId,
            segmentIndex = segmentIndex,
            segmentCount = segmentCount,
            streamingEnabled = streamingEnabled,
            onStatus = onStatus
        )
    }

    private companion object {
        const val FOLLOWUP_CONVERSATION_LIMIT = 6
    }
}
