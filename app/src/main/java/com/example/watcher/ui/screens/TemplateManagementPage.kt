package com.example.watcher.ui.screens

import androidx.compose.runtime.Composable
import com.example.watcher.data.model.AgentAudienceDebugSnapshot
import com.example.watcher.data.model.AiAudienceEntity
import com.example.watcher.data.model.CouncilExpertEntity
import com.example.watcher.data.model.CouncilTemplateEntity
import com.example.watcher.data.model.LiveCommentaryState
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.model.MemorySnapshot
import com.example.watcher.data.model.MonitorTemplateEntity
import com.example.watcher.data.model.VideoTemplateEntity
import com.example.watcher.ui.components.MotionDepth
import com.example.watcher.ui.components.MotionStageSection
import com.example.watcher.ui.components.PageScaffold
import com.example.watcher.ui.components.WatcherTopBar

@Composable
internal fun TemplateManagementPage(
    monitorTemplates: List<MonitorTemplateEntity>,
    videoTemplates: List<VideoTemplateEntity>,
    councilTemplates: List<CouncilTemplateEntity>,
    councilExperts: List<CouncilExpertEntity>,
    providers: List<LlmProviderEntity>,
    audiences: List<AiAudienceEntity>,
    onUpdateMonitorTemplate: (MonitorTemplateEntity) -> Unit,
    onUpdateVideoTemplate: (VideoTemplateEntity) -> Unit,
    onUpdateCouncilTemplate: (CouncilTemplateEntity) -> Unit,
    onResetMonitorTemplate: (String) -> Unit,
    onResetVideoTemplate: (String) -> Unit,
    onResetCouncilTemplate: (String) -> Unit,
    onCreateCouncilExpert: () -> Unit,
    onSaveCouncilExpert: (CouncilExpertEntity) -> Unit,
    onDuplicateCouncilExpert: (CouncilExpertEntity) -> Unit,
    onResetCouncilExpert: (String) -> Unit,
    onDeleteCouncilExpert: (String) -> Unit,
    onRestoreMissingCouncilExperts: () -> Unit,
    onDeleteMonitorTemplate: (String) -> Unit,
    onDeleteVideoTemplate: (String) -> Unit,
    onDeleteCouncilTemplate: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgentConfig: () -> Unit,
    onOpenWalletConfig: () -> Unit,
    isConciseMode: Boolean,
    onConciseModeChange: (Boolean) -> Unit,
    rotaryRotationDegrees: Float,
    onRotaryRotationChange: (Float) -> Unit,
    onSaveProvider: (LlmProviderEntity) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSaveAudience: (AiAudienceEntity) -> Unit,
    onDeleteAudience: (Long) -> Unit,
    getLastPost: (Long) -> String?,
    getLastResponse: (Long) -> String?,
    getAgentDebugSnapshot: (AiAudienceEntity) -> AgentAudienceDebugSnapshot?,
    getWallet: (Long) -> Int,
    setWallet: (Long, Int) -> Unit,
    getCouncilExpertLastPrompt: (String) -> String?,
    getCouncilExpertLastResponse: (String) -> String?,
    getExpertSessionMemory: (String) -> List<String>,
    getExpertKnowledge: suspend (String) -> List<com.example.watcher.data.model.CouncilKnowledgeEntity>,
    onDeleteKnowledge: (Long) -> Unit,
    getMemorySnapshot: () -> MemorySnapshot,
    commentaryState: LiveCommentaryState,
    onExportMonitor: (MonitorTemplateEntity) -> String,
    onExportVideo: (VideoTemplateEntity) -> String,
    onExportCouncil: (CouncilTemplateEntity) -> String,
    onExportCouncilExpert: (CouncilExpertEntity) -> String,
    onImportTemplate: (String, (String) -> Unit) -> Unit,
    currentPage: HubPage,
    pageOffset: Float
) {
    val header = workspaceHeaderFor(currentPage)

    PageScaffold(page = currentPage, pageOffset = pageOffset) {
        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Header) {
            WatcherTopBar(
                eyebrow = header.eyebrow,
                title = header.title,
                subtitle = header.subtitle,
                currentPage = currentPage,
                pageOffset = pageOffset,
                showConciseModeToggle = true,
                isConciseMode = isConciseMode,
                onConciseModeChange = onConciseModeChange,
                rotaryRotationDegrees = rotaryRotationDegrees,
                onRotaryRotationChange = onRotaryRotationChange,
                onOpenSettings = onOpenSettings,
                onOpenAgentConfig = onOpenAgentConfig,
                onOpenWalletConfig = onOpenWalletConfig
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            TemplateImportCard(onImport = onImportTemplate)
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            AiAudienceManagementCard(
                providers = providers,
                audiences = audiences,
                onSaveProvider = onSaveProvider,
                onDeleteProvider = onDeleteProvider,
                onSaveAudience = onSaveAudience,
                onDeleteAudience = onDeleteAudience,
                getLastPost = getLastPost,
                getLastResponse = getLastResponse,
                getAgentDebugSnapshot = getAgentDebugSnapshot,
                getWallet = getWallet,
                setWallet = setWallet
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            CouncilExpertManagementCard(
                experts = councilExperts,
                providers = providers,
                onCreateExpert = onCreateCouncilExpert,
                onSaveExpert = onSaveCouncilExpert,
                onDuplicateExpert = onDuplicateCouncilExpert,
                onResetExpert = onResetCouncilExpert,
                onDeleteExpert = onDeleteCouncilExpert,
                onRestoreMissingExperts = onRestoreMissingCouncilExperts,
                getLastPrompt = getCouncilExpertLastPrompt,
                getLastResponse = getCouncilExpertLastResponse,
                getSessionMemory = getExpertSessionMemory,
                getExpertKnowledge = getExpertKnowledge,
                onDeleteKnowledge = onDeleteKnowledge,
                onExportExpert = onExportCouncilExpert
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            MemorySystemCard(getMemorySnapshot = getMemorySnapshot)
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            SceneMemoryCard(commentaryState = commentaryState)
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            MonitorTemplateListCard(
                templates = monitorTemplates,
                onUpdate = onUpdateMonitorTemplate,
                onReset = onResetMonitorTemplate,
                onDelete = onDeleteMonitorTemplate,
                onExport = onExportMonitor
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            VideoTemplateListCard(
                templates = videoTemplates,
                onUpdate = onUpdateVideoTemplate,
                onReset = onResetVideoTemplate,
                onDelete = onDeleteVideoTemplate,
                onExport = onExportVideo
            )
        }

        MotionStageSection(pageOffset = pageOffset, depth = MotionDepth.Support) {
            CouncilTemplateListCard(
                templates = councilTemplates,
                onUpdate = onUpdateCouncilTemplate,
                onReset = onResetCouncilTemplate,
                onDelete = onDeleteCouncilTemplate,
                onExport = onExportCouncil
            )
        }
    }
}
