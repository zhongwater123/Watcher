package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.model.MonitorTemplateEntity
import com.example.watcher.ui.components.EmptyHint
import com.example.watcher.ui.components.HistoryTile
import com.example.watcher.ui.components.InfiniteTemplateTicker
import com.example.watcher.ui.components.TemplateTickerItem
import com.example.watcher.ui.components.WatcherCard

@Composable
internal fun MonitorTemplateCard(
    templates: List<MonitorTemplateEntity>,
    onApplyTemplate: (String) -> Unit
) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("模板任务", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "点选模板快速填充监控参数，后续仍可手动微调。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            InfiniteTemplateTicker(
                items = templates.map { template ->
                    TemplateTickerItem(
                        id = template.templateId,
                        label = template.label
                    )
                },
                onItemClick = onApplyTemplate,
                modifier = Modifier.height(96.dp)
            )
        }
    }
}

@Composable
internal fun MonitorHistoryCard(
    tasks: List<MonitorTask>,
    currentTaskId: Long?,
    onLoadTask: (MonitorTask) -> Unit,
    onDeleteTask: (Long) -> Unit
) {
    WatcherCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("历史监控任务", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "在卡片内部上下滚动，直接复用已有监控配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tasks.isEmpty()) {
                EmptyHint(text = "还没有历史监控任务，先生成一条新的监控需求。")
            } else {
                LazyColumn(
                    modifier = Modifier.height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        HistoryTile(
                            title = task.title,
                            subtitle = task.userRequirement,
                            supporting = task.lastSummary ?: "每 ${task.checkInterval} 秒巡检一次",
                            selected = task.id == currentTaskId,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { onLoadTask(task) },
                            onDelete = { onDeleteTask(task.id) }
                        )
                    }
                }
            }
        }
    }
}
