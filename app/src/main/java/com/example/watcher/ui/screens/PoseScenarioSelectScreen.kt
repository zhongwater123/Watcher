package com.example.watcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.theme.LocalWatcherExtendedColors

enum class PoseScenario(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val enabled: Boolean
) {
    REALTIME(
        label = "实时识别",
        description = "摄像头实时骨架追踪，验证设备性能与精度",
        icon = Icons.Default.Accessibility,
        enabled = true
    ),
    DANCE_LEARNING(
        label = "舞蹈学习",
        description = "上传舞蹈视频，提取全程运动数据用于学习对比",
        icon = Icons.Default.MusicNote,
        enabled = true
    ),
    ROCK_CLIMBING(
        label = "攀岩学习",
        description = "分析攀岩动作路线与重心控制",
        icon = Icons.Default.Terrain,
        enabled = false
    ),
    MOTION_GAMING(
        label = "体感游戏",
        description = "基于实时姿态的体感交互游戏",
        icon = Icons.Default.SportsEsports,
        enabled = false
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoseScenarioSelectScreen(
    onNavigateRealtime: () -> Unit,
    onNavigateDanceLearning: () -> Unit,
    onClose: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("姿态识别") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "选择使用场景",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            ScenarioCard(
                scenario = PoseScenario.REALTIME,
                accentColor = MaterialTheme.colorScheme.tertiary,
                onClick = onNavigateRealtime
            )

            ScenarioCard(
                scenario = PoseScenario.DANCE_LEARNING,
                accentColor = MaterialTheme.colorScheme.primary,
                onClick = onNavigateDanceLearning
            )

            ScenarioCard(
                scenario = PoseScenario.ROCK_CLIMBING,
                accentColor = Color(0xFF9A5B00),
                onClick = {}
            )

            ScenarioCard(
                scenario = PoseScenario.MOTION_GAMING,
                accentColor = Color(0xFF6A4CB0),
                onClick = {}
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: PoseScenario,
    accentColor: Color,
    onClick: () -> Unit
) {
    val extendedColors = LocalWatcherExtendedColors.current
    val alpha = if (scenario.enabled) 1f else 0.5f

    WatcherCard(onClick = if (scenario.enabled) onClick else null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = scenario.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
                Text(
                    text = scenario.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = extendedColors.surfaceContainer
            ) {
                Icon(
                    imageVector = scenario.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp),
                    tint = accentColor.copy(alpha = alpha)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!scenario.enabled) {
                StatusPill(text = "即将推出", accent = MaterialTheme.colorScheme.outline)
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
            if (scenario.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "进入",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
