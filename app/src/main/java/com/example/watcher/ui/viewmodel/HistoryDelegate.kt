package com.example.watcher.ui.viewmodel

import com.example.watcher.data.model.HistoryRecordDetail
import com.example.watcher.data.model.HistoryRecordSelection
import com.example.watcher.data.model.HistoryRecordType
import com.example.watcher.data.model.MonitorHistoryDetail
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoHistoryDetail
import com.example.watcher.data.repository.HistoryRepository
import com.example.watcher.data.repository.HistoryTemplateConverter
import com.example.watcher.data.repository.VideoProcessRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Handles history record selection, deletion, detail/event observation,
 * and history-to-template conversion.
 * Extracted from IntentViewModel.
 */
internal class HistoryDelegate(
    private val scope: CoroutineScope,
    private val historyRepository: HistoryRepository,
    private val videoRepository: VideoProcessRepository,
    private val historyTemplateConverter: HistoryTemplateConverter,
    private val selectedVideoRunId: MutableStateFlow<Long?>,
    private val selectedVideoRunEvents: MutableStateFlow<List<TimelineEventEntity>>
) {
    private val _selectedRecord = MutableStateFlow<HistoryRecordSelection?>(null)
    private val _selectedDetail = MutableStateFlow<HistoryRecordDetail?>(null)
    private val _activeVideoHistoryReportDetail = MutableStateFlow<VideoHistoryDetail?>(null)

    val selectedHistoryRecord: StateFlow<HistoryRecordSelection?> = _selectedRecord.asStateFlow()
    val selectedHistoryDetail: StateFlow<HistoryRecordDetail?> = _selectedDetail.asStateFlow()
    val activeVideoHistoryReportDetail: StateFlow<VideoHistoryDetail?> =
        _activeVideoHistoryReportDetail.asStateFlow()

    fun startObserving() {
        observeSelectedHistoryDetail()
        observeSelectedVideoRunEvents()
    }

    fun selectHistoryRecord(selection: HistoryRecordSelection?) {
        _selectedRecord.value = selection
    }

    fun openVideoHistoryReport(selection: HistoryRecordSelection) {
        if (selection.type != HistoryRecordType.VideoAnalysis) return
        scope.launch {
            _activeVideoHistoryReportDetail.value =
                historyRepository.getFullVideoHistoryDetail(selection.recordId)
        }
    }

    fun closeVideoHistoryReport() {
        _activeVideoHistoryReportDetail.value = null
    }

    fun loadFullHistoryDetail(
        selection: HistoryRecordSelection,
        onResult: (HistoryRecordDetail?) -> Unit
    ) {
        scope.launch {
            onResult(historyRepository.getFullHistoryDetail(selection))
        }
    }

    fun deleteHistoryRecord(selection: HistoryRecordSelection) {
        scope.launch {
            val detail = _selectedDetail.value
            if (detail?.selection == selection && !detail.canDelete) return@launch

            historyRepository.deleteHistoryRecord(selection)
            if (_selectedRecord.value == selection) {
                _selectedRecord.value = null
                _selectedDetail.value = null
            }
            if (selection.type == HistoryRecordType.VideoAnalysis &&
                selectedVideoRunId.value == selection.recordId
            ) {
                selectedVideoRunId.value = null
                selectedVideoRunEvents.value = emptyList()
            }
            if (_activeVideoHistoryReportDetail.value?.selection == selection) {
                _activeVideoHistoryReportDetail.value = null
            }
        }
    }

    private fun observeSelectedVideoRunEvents() {
        scope.launch {
            selectedVideoRunId
                .flatMapLatest { runId ->
                    if (runId == null) flowOf(emptyList())
                    else videoRepository.observeTimelineForRun(runId)
                }
                .collect { events -> selectedVideoRunEvents.value = events }
        }
    }

    private fun observeSelectedHistoryDetail() {
        scope.launch {
            _selectedRecord
                .flatMapLatest { selection ->
                    if (selection == null) flowOf(null)
                    else historyRepository.observeHistoryDetail(selection)
                }
                .collect { detail -> _selectedDetail.value = detail }
        }
    }

    fun saveAsTemplate(detail: HistoryRecordDetail, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            when (detail) {
                is MonitorHistoryDetail -> {
                    val taskId = detail.run.taskId
                    if (taskId == null) {
                        onResult(false, "源任务关联缺失，无法保存为模板")
                        return@launch
                    }
                    historyTemplateConverter.convertMonitorToTemplate(taskId)
                        .onSuccess { entity ->
                            historyTemplateConverter.saveMonitorTemplate(entity)
                            onResult(true, "已保存为监控模板「${entity.label}」")
                        }
                        .onFailure { e ->
                            onResult(false, e.message ?: "保存失败")
                        }
                }
                is VideoHistoryDetail -> {
                    historyTemplateConverter.convertVideoToTemplate(detail.run.taskId)
                        .onSuccess { entity ->
                            historyTemplateConverter.saveVideoTemplate(entity)
                            onResult(true, "已保存为视频分析模板「${entity.label}」")
                        }
                        .onFailure { e ->
                            onResult(false, e.message ?: "保存失败")
                        }
                }
            }
        }
    }

    fun shareAsTemplate(detail: HistoryRecordDetail, onResult: (String?) -> Unit) {
        scope.launch {
            when (detail) {
                is MonitorHistoryDetail -> {
                    val taskId = detail.run.taskId
                    if (taskId == null) {
                        onResult(null)
                        return@launch
                    }
                    historyTemplateConverter.convertMonitorToTemplate(taskId)
                        .onSuccess { entity ->
                            onResult(historyTemplateConverter.exportMonitorAsShareText(entity))
                        }
                        .onFailure { onResult(null) }
                }
                is VideoHistoryDetail -> {
                    historyTemplateConverter.convertVideoToTemplate(detail.run.taskId)
                        .onSuccess { entity ->
                            onResult(historyTemplateConverter.exportVideoAsShareText(entity))
                        }
                        .onFailure { onResult(null) }
                }
            }
        }
    }
}
