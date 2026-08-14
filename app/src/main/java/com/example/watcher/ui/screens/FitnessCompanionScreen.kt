package com.example.watcher.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.watcher.data.model.FitnessGoalType
import com.example.watcher.data.model.FitnessStrategyGoalEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity
import com.example.watcher.data.model.FitnessWorkoutStatus
import com.example.watcher.data.model.FitnessRepCounterState
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.fitness.agent.feedback.realtime.FitnessVlmCoachDisplay
import com.example.watcher.data.fitness.agent.feedback.realtime.FitnessVlmObservationDisplay
import com.example.watcher.data.fitness.agent.feedback.realtime.FitnessVlmObservability
import com.example.watcher.data.fitness.agent.feedback.realtime.FitnessRealtimeVlmState
import com.example.watcher.data.repository.ExerciseLibraryItem
import com.example.watcher.data.repository.ExerciseLibraryRepository
import com.example.watcher.data.repository.ExerciseLibraryUiState
import com.example.watcher.data.repository.StreamReservation
import com.example.watcher.data.training.fitness.FITNESS_TRAINING_STREAM_OWNER
import com.example.watcher.ui.components.ConnectionStatus
import com.example.watcher.ui.components.PoseOverlay
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.StepProgressRow
import com.example.watcher.ui.components.WatcherCard
import com.example.watcher.ui.components.rememberMjpegStreamState
import com.example.watcher.ui.theme.LocalWatcherExtendedColors
import com.example.watcher.ui.viewmodel.FITNESS_ONBOARDING_LAST_STEP
import com.example.watcher.ui.viewmodel.FitnessCompanionUiState
import com.example.watcher.ui.viewmodel.FitnessCompanionViewModel
import com.example.watcher.ui.viewmodel.FitnessOnboardingDraft
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import java.nio.ByteBuffer

@Composable
fun FitnessCompanionScreen(
    uiState: FitnessCompanionUiState,
    draft: FitnessOnboardingDraft,
    viewModel: FitnessCompanionViewModel,
    onClose: () -> Unit
) {
    val profile = uiState.profile
    if (profile == null) {
        FitnessLoading(onClose = onClose)
        return
    }

    if (!profile.isComplete) {
        FitnessOnboardingScreen(
            draft = draft,
            viewModel = viewModel,
            onClose = onClose
        )
    } else {
        FitnessCompanionHome(
            uiState = uiState,
            draft = draft,
            viewModel = viewModel,
            onClose = onClose
        )
    }
}

@Composable
private fun FitnessLoading(onClose: () -> Unit) {
    FitnessSurface {
        FitnessTopBar(title = "健身陪伴助手", subtitle = "正在准备资料", onClose = onClose)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在加载...")
        }
    }
}

@Composable
private fun FitnessOnboardingScreen(
    draft: FitnessOnboardingDraft,
    viewModel: FitnessCompanionViewModel,
    onClose: () -> Unit
) {
    val step = draft.onboardingStep.coerceIn(0, FITNESS_ONBOARDING_LAST_STEP)
    val progress = (step + 1).toFloat() / (FITNESS_ONBOARDING_LAST_STEP + 1).toFloat()
    val canContinue = viewModel.canAdvance(step)

    FitnessSurface {
        FitnessTopBar(
            title = "定制你的陪练",
            subtitle = "一步一个选择，不用填写表格",
            onClose = onClose
        )
        FitnessOnboardingProgress(step = step, progress = progress)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            FitnessOnboardingPage(
                step = step,
                draft = draft,
                viewModel = viewModel
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = viewModel::goBack,
                enabled = step > 0,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Button(
                onClick = {
                    if (step >= FITNESS_ONBOARDING_LAST_STEP) viewModel.completeOnboarding() else viewModel.goNext()
                },
                enabled = canContinue,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (step >= FITNESS_ONBOARDING_LAST_STEP) "生成我的陪练计划" else continueLabel(step))
                Spacer(Modifier.width(6.dp))
                Icon(if (step >= FITNESS_ONBOARDING_LAST_STEP) Icons.Default.CheckCircle else Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun FitnessOnboardingProgress(step: Int, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = onboardingStageLabel(step),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${step + 1}/${FITNESS_ONBOARDING_LAST_STEP + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun FitnessOnboardingPage(
    step: Int,
    draft: FitnessOnboardingDraft,
    viewModel: FitnessCompanionViewModel
) {
    when (step) {
        0 -> SingleChoiceQuestion(
            eyebrow = "先定方向",
            title = "这次你最想改变什么？",
            subtitle = "不用想得太完美，先选最贴近你的那个。",
            options = listOf(
                FitnessGoalType.WeightLoss.name to "减脂减重",
                FitnessGoalType.MuscleTone.name to "增肌塑形",
                FitnessGoalType.HealthyHabit.name to "保持健康"
            ),
            selected = draft.goalType,
            onSelect = viewModel::selectGoalType
        )
        1 -> SingleChoiceQuestion(
            eyebrow = "了解过去",
            title = "之前这件事，对你来说更像哪一种？",
            subtitle = "我会根据你的经历，给你不同的开始方式。",
            options = listOf(
                "从来没尝试过" to "从来没尝试过",
                "成功又反弹" to "成功又反弹",
                "现在想做更好" to "现在想做更好",
                "之前尝试但失败" to "之前尝试但失败"
            ),
            selected = draft.previousAttempt,
            onSelect = viewModel::selectPreviousAttempt
        )
        2 -> CoachFeedbackPage(
            title = "记下来了",
            message = previousAttemptFeedback(draft.previousAttempt),
            formula = "先稳住，再加量"
        )
        3 -> MultiChoiceQuestion(
            eyebrow = "目标部位",
            title = "你最想先看到哪里有变化？",
            subtitle = "可以多选。选“全身”时，我会优先做均衡计划。",
            options = listOf("全身", "腰腹", "手臂", "背部", "胸肩", "臀腿", "体态"),
            selected = draft.targetParts,
            onToggle = viewModel::toggleTargetPart
        )
        4 -> SliderQuestion(
            eyebrow = "基础资料",
            title = "现在体重大概是多少？",
            subtitle = "先确认现在的起点，后面再设置你想减到哪里。",
            valueText = "${"%.1f".format(draft.currentWeightKg)} kg",
            value = draft.currentWeightKg,
            range = 40f..160f,
            onValueChange = viewModel::setCurrentWeight
        )
        5 -> if (draft.requiresTargetWeight) {
            val maxTargetWeight = (draft.currentWeightKg - 0.5f).coerceIn(35f, 120f)
            SliderQuestion(
                eyebrow = "目标体重",
                title = "你希望先到多少 kg？",
                subtitle = "目标体重要低于当前体重，这样我才能计算健康减重节奏。",
                valueText = "${"%.1f".format(draft.targetWeightKg.coerceIn(35f, maxTargetWeight))} kg",
                value = draft.targetWeightKg.coerceIn(35f, maxTargetWeight),
                range = 35f..maxTargetWeight,
                onValueChange = viewModel::setTargetWeight
            )
        } else {
            CoachFeedbackPage(
                title = "不必盯体重",
                message = "这类目标主要看线条、力量、精神状态和完成次数。",
                formula = "体态 + 力量 + 规律"
            )
        }
        6 -> SingleChoiceQuestion(
            eyebrow = "基础资料",
            title = "怎么称呼你的身体数据？",
            subtitle = "用于估算训练强度，不会影响你被怎样对待。",
            options = listOf("男" to "男", "女" to "女", "不想说明" to "不想说明"),
            selected = draft.gender,
            onSelect = viewModel::selectGender
        )
        7 -> IntegerSliderQuestion("基础资料", "你的年龄是？", "年龄会影响恢复和训练节奏。", draft.age, 12f..80f, "岁", viewModel::setAge)
        8 -> IntegerSliderQuestion("基础资料", "你的身高是？", "用于和体重一起判断起步强度。", draft.heightCm, 140f..210f, "cm", viewModel::setHeight)
        9 -> SingleChoiceQuestion(
            eyebrow = "当前状态",
            title = "你觉得自己现在更像哪种体型？",
            subtitle = "先用文字占位，后面可以换成更直观的图。",
            options = bodyTypes().map { it to it },
            selected = draft.currentBodyType,
            onSelect = viewModel::selectCurrentBodyType
        )
        10 -> SingleChoiceQuestion(
            eyebrow = "理想状态",
            title = "你想先靠近哪种体型？",
            subtitle = "我会把它翻译成训练节奏，而不是让你硬扛。",
            options = bodyTypes().map { it to it },
            selected = draft.targetBodyType,
            onSelect = viewModel::selectTargetBodyType
        )
        11 -> CoachFeedbackPage(
            title = "节奏出来了",
            message = viewModel.paceSummary(),
            formula = paceFormula(draft.goalType)
        )
        12 -> MultiChoiceQuestion("安全边界", "哪些地方需要我避开或照顾？", "疼痛和旧伤比动作数量更重要。", listOf("无", "颈肩", "腰背", "膝盖", "脚踝", "手腕", "其他"), draft.injuryParts, viewModel::toggleInjuryPart)
        13 -> SingleChoiceQuestion("日常状态", "你一天大概坐多久？", "久坐会影响髋、腰背和训练安排。", listOf("很少" to "很少", "3-6小时" to "3-6小时", "6-9小时" to "6-9小时", "9小时以上" to "9小时以上"), draft.sedentaryLevel, viewModel::selectSedentary)
        14 -> SingleChoiceQuestion("恢复能力", "最近睡眠更像哪种？", "睡不好时，计划会更重视恢复。", listOf("很好" to "很好", "一般" to "一般", "经常熬夜" to "经常熬夜", "睡眠不足" to "睡眠不足"), draft.sleepQuality, viewModel::selectSleep)
        15 -> MultiChoiceQuestion("饮食习惯", "平时吃饭更像哪些情况？", "这里只用于判断起步建议，不做饮食审判。", listOf("外卖/外食", "常吃宵夜", "三餐不固定", "饮食规律"), draft.dietHabits, viewModel::toggleDietHabit)
        16 -> CoachFeedbackPage(
            title = "强度先收住",
            message = buildSelfFeedback(draft),
            formula = "练得完，比练得狠更重要"
        )
        17 -> SingleChoiceQuestion("运动基础", "你现在运动频次如何？", "这决定第一周是适应，还是直接进入训练节奏。", listOf("几乎不运动" to "几乎不运动", "偶尔" to "偶尔", "每周1-2次" to "每周1-2次", "每周3次以上" to "每周3次以上"), draft.exerciseFrequency, viewModel::selectExerciseFrequency)
        18 -> MultiChoiceQuestion("训练场景", "你愿意在哪里运动？", "我会优先安排你真的能去的地方。", listOf("健身房", "家里", "户外", "都可以"), draft.preferredPlaces, viewModel::togglePreferredPlace)
        19 -> IntegerSliderQuestion("训练场景", "目前一周去几次健身房？", "如果不常去，也可以先从家里和户外开始。", draft.gymVisitsPerWeek, 0f..7f, "次", viewModel::setGymVisits)
        20 -> SingleChoiceQuestion("器械熟悉度", "你对器械了解多少？", "不了解也没关系，计划会从简单动作开始。", listOf("完全不了解" to "完全不了解", "会用一点" to "会用一点", "比较熟悉" to "比较熟悉", "很熟悉" to "很熟悉"), draft.equipmentKnowledge, viewModel::selectEquipmentKnowledge)
        21 -> IntegerSliderQuestion("小测试", "平板支撑大概能坚持多久？", "这只是估算核心耐力，不是考试。", draft.plankSeconds, 0f..180f, "秒", viewModel::setPlankSeconds)
        22 -> SingleChoiceQuestion("小测试", "爬楼梯时身体怎么反馈？", "这个会帮我判断有氧和腿部训练起点。", listOf("轻松" to "轻松", "有点累" to "有点累", "很喘" to "很喘", "膝盖不舒服" to "膝盖不舒服"), draft.stairFeeling, viewModel::selectStairFeeling)
        23 -> SingleChoiceQuestion(
            eyebrow = "给未来一点奖励",
            title = "达到理想状态后，想怎么奖励自己？",
            subtitle = "奖励不是诱惑，是给坚持留一个终点。",
            options = listOf("买件新东西" to "买件新东西", "去旅行" to "去旅行", "和朋友聚会" to "和朋友聚会", "给自己放一天假" to "给自己放一天假", "其他" to "其他"),
            selected = draft.rewardPreference,
            onSelect = viewModel::selectReward
        )
        else -> FitnessProfileSummaryPage(draft, viewModel)
    }
}

@Composable
private fun SingleChoiceQuestion(
    eyebrow: String,
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    OnboardingQuestionCard(eyebrow = eyebrow, title = title, subtitle = subtitle) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (value, label) ->
                LargeChoiceOption(
                    text = label,
                    selected = selected == value,
                    onClick = { onSelect(value) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiChoiceQuestion(
    eyebrow: String,
    title: String,
    subtitle: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    OnboardingQuestionCard(eyebrow = eyebrow, title = title, subtitle = subtitle) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.forEach { option ->
                LargeChoiceOption(
                    text = option,
                    selected = option in selected,
                    compact = true,
                    onClick = { onToggle(option) }
                )
            }
        }
    }
}

@Composable
private fun SliderQuestion(
    eyebrow: String,
    title: String,
    subtitle: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    OnboardingQuestionCard(eyebrow = eyebrow, title = title, subtitle = subtitle) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${range.start.roundToInt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${range.endInclusive.roundToInt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IntegerSliderQuestion(
    eyebrow: String,
    title: String,
    subtitle: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChange: (Int) -> Unit
) {
    SliderQuestion(
        eyebrow = eyebrow,
        title = title,
        subtitle = subtitle,
        valueText = "$value $suffix",
        value = value.toFloat(),
        range = range,
        onValueChange = { onValueChange(it.roundToInt()) }
    )
}

@Composable
private fun CoachFeedbackPage(
    title: String,
    message: String,
    formula: String
) {
    OnboardingQuestionCard(
        eyebrow = "已记录",
        title = title,
        subtitle = message
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    modifier = Modifier.size(42.dp),
                    contentColor = MaterialTheme.colorScheme.secondary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("节奏参考", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(formula, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun FitnessProfileSummaryPage(
    draft: FitnessOnboardingDraft,
    viewModel: FitnessCompanionViewModel
) {
    OnboardingQuestionCard(
        eyebrow = "确认一下",
        title = "我会按这些信息生成你的第一份陪练计划",
        subtitle = "下一步会进入助手主页，并在后台准备战略目标和本次训练。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryLine("目标", goalLabel(draft.goalType))
            SummaryLine("重点部位", draft.targetParts.joinToString("、").ifBlank { "全身均衡" })
            SummaryLine(
                "体重",
                "${"%.1f".format(draft.currentWeightKg)} kg" +
                    if (draft.requiresTargetWeight) " -> ${"%.1f".format(draft.targetWeightKg)} kg" else ""
            )
            SummaryLine("体型", "${draft.currentBodyType.ifBlank { "未选择" }} -> ${draft.targetBodyType.ifBlank { "未选择" }}")
            SummaryLine("训练场景", draft.preferredPlaces.joinToString("、").ifBlank { "未选择" })
            SummaryLine("运动基础", draft.exerciseFrequency.ifBlank { "未选择" })
            SummaryLine("预计节奏", viewModel.paceSummary())
            PositiveCard("资料够用了。第一轮先稳一点，完成后再调准。")
        }
    }
}

@Composable
private fun OnboardingQuestionCard(
    eyebrow: String,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    WatcherCard(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun LargeChoiceOption(
    text: String,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(if (compact) 18.dp else 22.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Text(
                text = text,
                style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun continueLabel(step: Int): String = when (step) {
    2, 5, 11, 16 -> "继续"
    23 -> "查看总结"
    else -> "下一步"
}

private fun onboardingStageLabel(step: Int): String = when (step) {
    in 0..3 -> "目标"
    in 4..11 -> "身体数据"
    in 12..16 -> "自身情况"
    in 17..23 -> "运动情况"
    else -> "确认"
}

private fun previousAttemptFeedback(value: String): String = when (value) {
    "从来没尝试过" -> "从轻量开始，先把节奏跑起来。"
    "成功又反弹" -> "这次不冲太猛，重点放在留住成果。"
    "现在想做更好" -> "基础不错，后面会更看重训练质量。"
    "之前尝试但失败" -> "先降门槛，别一开始就把难度拉满。"
    else -> "先完成第一周，状态会更清楚。"
}

private fun paceFormula(goalType: String): String = when (goalType) {
    FitnessGoalType.WeightLoss.name -> "0.5%-1.0%体重 / 周"
    FitnessGoalType.MuscleTone.name -> "8-12周打底，看训练连续性"
    FitnessGoalType.HealthyHabit.name -> "8-12周建立固定运动节奏"
    else -> "先做一周，再校准"
}

private fun buildSelfFeedback(draft: FitnessOnboardingDraft): String {
    val injury = if ("无" in draft.injuryParts) "没有明显伤病限制" else "${draft.injuryParts.joinToString("、")}先做保护"
    val sleep = when (draft.sleepQuality) {
        "很好" -> "恢复状态不错"
        "一般" -> "强度留一点余量"
        "经常熬夜", "睡眠不足" -> "先避开高疲劳训练"
        else -> "恢复状态后续再看"
    }
    return "$injury，$sleep。"
}

@Composable
private fun GoalStep(draft: FitnessOnboardingDraft, viewModel: FitnessCompanionViewModel) {
    FitnessSection("第一阶段", "目标") {
        OptionGroup(
            title = "健身动力",
            options = listOf(
                FitnessGoalType.WeightLoss.name to "减脂减重",
                FitnessGoalType.MuscleTone.name to "增肌塑形",
                FitnessGoalType.HealthyHabit.name to "保持健康"
            ),
            selected = draft.goalType,
            onSelect = viewModel::selectGoalType
        )
        OptionGroup(
            title = "之前尝试过吗",
            options = listOf(
                "从来没尝试过" to "从来没尝试过",
                "成功又反弹" to "成功又反弹",
                "现在想做更好" to "现在想做更好",
                "之前尝试但失败" to "之前尝试但失败"
            ),
            selected = draft.previousAttempt,
            onSelect = viewModel::selectPreviousAttempt
        )
        PositiveCard("你已经把目标说清楚了。接下来我们会把它拆成能完成的小步。")
        MultiOptionGroup(
            title = "想重点改善哪里",
            options = listOf("全身", "腰腹", "手臂", "背部", "胸肩", "臀腿", "体态"),
            selected = draft.targetParts,
            onToggle = viewModel::toggleTargetPart
        )
        if (draft.requiresTargetWeight) {
            NumberSlider(
                title = "目标体重",
                value = draft.targetWeightKg,
                range = 40f..120f,
                suffix = "kg",
                onValueChange = viewModel::setTargetWeight
            )
        }
    }
}

@Composable
private fun BodyStep(draft: FitnessOnboardingDraft, viewModel: FitnessCompanionViewModel) {
    FitnessSection("第二阶段", "身体数据") {
        OptionGroup(
            title = "性别",
            options = listOf("男" to "男", "女" to "女", "不想说明" to "不想说明"),
            selected = draft.gender,
            onSelect = viewModel::selectGender
        )
        IntegerSlider("年龄", draft.age, 12f..80f, "岁") { viewModel.setAge(it) }
        IntegerSlider("身高", draft.heightCm, 140f..210f, "cm") { viewModel.setHeight(it) }
        NumberSlider("当前体重", draft.currentWeightKg, 35f..160f, "kg", viewModel::setCurrentWeight)
        OptionGroup(
            title = "当前体型",
            options = bodyTypes().map { it to it },
            selected = draft.currentBodyType,
            onSelect = viewModel::selectCurrentBodyType
        )
        OptionGroup(
            title = "目标体型",
            options = bodyTypes().map { it to it },
            selected = draft.targetBodyType,
            onSelect = viewModel::selectTargetBodyType
        )
        PositiveCard(
            text = viewModel.paceSummary(),
            formula = when (draft.goalType) {
                FitnessGoalType.WeightLoss.name -> "健康节奏 = 当前体重的 0.5%-1.0% / 周"
                FitnessGoalType.MuscleTone.name -> "体型跨度 × 8-12 周 ÷ 训练一致性"
                FitnessGoalType.HealthyHabit.name -> "8-12 周 → 每周150分钟活动 + 2次力量"
                else -> "完成目标选择后生成预计节奏"
            }
        )
    }
}

@Composable
private fun SelfConditionStep(draft: FitnessOnboardingDraft, viewModel: FitnessCompanionViewModel) {
    FitnessSection("第三阶段", "自身情况") {
        MultiOptionGroup(
            title = "哪些部位有受伤或不舒服",
            options = listOf("无", "颈肩", "腰背", "膝盖", "脚踝", "手腕", "其他"),
            selected = draft.injuryParts,
            onToggle = viewModel::toggleInjuryPart
        )
        OptionGroup(
            title = "久坐情况",
            options = listOf("很少" to "很少", "3-6小时" to "3-6小时", "6-9小时" to "6-9小时", "9小时以上" to "9小时以上"),
            selected = draft.sedentaryLevel,
            onSelect = viewModel::selectSedentary
        )
        OptionGroup(
            title = "睡眠情况",
            options = listOf("很好" to "很好", "一般" to "一般", "经常熬夜" to "经常熬夜", "睡眠不足" to "睡眠不足"),
            selected = draft.sleepQuality,
            onSelect = viewModel::selectSleep
        )
        MultiOptionGroup(
            title = "日常饮食习惯",
            options = listOf("外卖/外食", "常吃宵夜", "三餐不固定", "饮食规律"),
            selected = draft.dietHabits,
            onToggle = viewModel::toggleDietHabit
        )
        SummaryAdviceCard(draft)
    }
}

@Composable
private fun ExerciseStep(draft: FitnessOnboardingDraft, viewModel: FitnessCompanionViewModel) {
    FitnessSection("第四阶段", "运动情况") {
        OptionGroup(
            title = "平时运动频次",
            options = listOf("几乎不运动" to "几乎不运动", "偶尔" to "偶尔", "每周1-2次" to "每周1-2次", "每周3次以上" to "每周3次以上"),
            selected = draft.exerciseFrequency,
            onSelect = viewModel::selectExerciseFrequency
        )
        MultiOptionGroup(
            title = "愿意运动的场所",
            options = listOf("健身房", "家里", "户外", "都可以"),
            selected = draft.preferredPlaces,
            onToggle = viewModel::togglePreferredPlace
        )
        PositiveCard("频率不用一开始就很高。先稳定出现，再慢慢变强。")
        IntegerSlider("目前一周去几次健身房", draft.gymVisitsPerWeek, 0f..7f, "次") { viewModel.setGymVisits(it) }
        OptionGroup(
            title = "对器械使用的了解",
            options = listOf("完全不了解" to "完全不了解", "会用一点" to "会用一点", "比较熟悉" to "比较熟悉", "很熟悉" to "很熟悉"),
            selected = draft.equipmentKnowledge,
            onSelect = viewModel::selectEquipmentKnowledge
        )
        PositiveCard("知道自己熟不熟，比硬装熟练更重要。计划会从你的熟悉程度开始。")
        IntegerSlider("平板支撑坚持时间", draft.plankSeconds, 0f..180f, "秒") { viewModel.setPlankSeconds(it) }
        OptionGroup(
            title = "爬楼梯的自我感觉",
            options = listOf("轻松" to "轻松", "有点累" to "有点累", "很喘" to "很喘", "膝盖不舒服" to "膝盖不舒服"),
            selected = draft.stairFeeling,
            onSelect = viewModel::selectStairFeeling
        )
        PositiveCard("这些小测试会帮我控制第一轮强度，让训练可持续。")
    }
}

@Composable
private fun SummaryStep(draft: FitnessOnboardingDraft, viewModel: FitnessCompanionViewModel) {
    FitnessSection("第五阶段", "小结确认") {
        WatcherCard {
            Text("核心信息", style = MaterialTheme.typography.titleMedium)
            SummaryLine("目标", goalLabel(draft.goalType))
            SummaryLine("部位", draft.targetParts.joinToString("、").ifBlank { "未选择" })
            SummaryLine("体重", "${"%.1f".format(draft.currentWeightKg)} kg" + if (draft.requiresTargetWeight) " → ${"%.1f".format(draft.targetWeightKg)} kg" else "")
            SummaryLine("运动基础", draft.exerciseFrequency.ifBlank { "未选择" })
            SummaryLine("器械熟悉度", draft.equipmentKnowledge.ifBlank { "未选择" })
            SummaryLine("预计节奏", viewModel.paceSummary())
        }
        OptionGroup(
            title = "达到理想状态后，想怎么奖励自己",
            options = listOf(
                "买件新东西" to "买件新东西",
                "去旅行" to "去旅行",
                "和朋友聚会" to "和朋友聚会",
                "给自己放一天假" to "给自己放一天假",
                "其他" to "其他"
            ),
            selected = draft.rewardPreference,
            onSelect = viewModel::selectReward
        )
        PositiveCard("很好。奖励不是诱惑，是给长期坚持留一个温柔的终点。")
    }
}

@Composable
private fun FitnessCompanionHome(
    uiState: FitnessCompanionUiState,
    draft: FitnessOnboardingDraft,
    viewModel: FitnessCompanionViewModel,
    onClose: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf("train") }
    var showTrainDetail by rememberSaveable { mutableStateOf(false) }
    var selectedLibraryExerciseId by rememberSaveable { mutableStateOf<String?>(null) }
    var inTrainingSession by rememberSaveable { mutableStateOf(false) }
    val exerciseLibraryState by viewModel.exerciseLibraryState.collectAsStateWithLifecycle()
    LaunchedEffect(selectedTab) {
        if (selectedTab == "library") {
            viewModel.loadExerciseLibrary()
        }
    }
    fun navigateBack() {
        when {
            inTrainingSession -> inTrainingSession = false
            selectedTab == "library" && selectedLibraryExerciseId != null -> selectedLibraryExerciseId = null
            showTrainDetail -> showTrainDetail = false
            selectedTab != "train" -> {
                selectedTab = "train"
                showTrainDetail = false
                selectedLibraryExerciseId = null
            }
            else -> onClose()
        }
    }
    BackHandler(onBack = ::navigateBack)

    FitnessSurface {
        if (inTrainingSession) {
            TrainingInProgressPage(
                uiState = uiState,
                viewModel = viewModel,
                onEndTraining = { inTrainingSession = false },
                modifier = Modifier.weight(1f)
            )
        } else {
            if (selectedTab == "library") {
                ExerciseLibraryPage(
                    libraryState = exerciseLibraryState,
                    selectedExerciseId = selectedLibraryExerciseId,
                    onSelectExercise = { selectedLibraryExerciseId = it.id },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (selectedTab) {
                        "profile" -> FitnessProfilePage(
                            uiState = uiState,
                            draft = draft,
                            viewModel = viewModel
                        )
                        else -> if (showTrainDetail) {
                            TrainPage(
                                uiState = uiState,
                                viewModel = viewModel,
                                onStartTraining = { inTrainingSession = true }
                            )
                        } else {
                            FitnessAssistantMainPage(
                                uiState = uiState,
                                onOpenTrain = { showTrainDetail = true },
                                onRegenerate = viewModel::regeneratePlan
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            FitnessBottomNavigation(
                selectedTab = selectedTab,
                onSelect = { tab ->
                    selectedTab = tab
                    showTrainDetail = false
                    selectedLibraryExerciseId = null
                }
            )
        }
    }
}

@Composable
private fun FitnessAssistantMainPage(
    uiState: FitnessCompanionUiState,
    onOpenTrain: () -> Unit,
    onRegenerate: () -> Unit
) {
    CompanionModeCard(
        title = "陪你练",
        subtitle = uiState.activePlan?.title ?: "正在准备你的训练计划",
        icon = Icons.Default.Accessibility,
        enabled = true,
        onClick = onOpenTrain
    )
    CompanionModeCard(
        title = "陪你吃",
        subtitle = "饮食记录和餐食建议稍后开放",
        icon = Icons.Default.Restaurant,
        enabled = false,
        onClick = {}
    )
    CompanionModeCard(
        title = "陪你睡",
        subtitle = "睡眠和恢复陪伴稍后开放",
        icon = Icons.Default.SelfImprovement,
        enabled = false,
        onClick = {}
    )
    LatestPlanPreview(
        plan = uiState.activePlan,
        exercises = uiState.exercises,
        onOpen = onOpenTrain,
        onRegenerate = onRegenerate
    )
}

@Composable
private fun ExerciseLibraryPage(
    libraryState: ExerciseLibraryUiState,
    selectedExerciseId: String?,
    onSelectExercise: (ExerciseLibraryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedExercise = remember(libraryState.exercises, selectedExerciseId) {
        libraryState.exercises.firstOrNull { it.id == selectedExerciseId }
    }
    if (selectedExercise != null) {
        ExerciseLibraryDetailPage(
            exercise = selectedExercise,
            modifier = modifier
        )
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var selectedBodyPart by rememberSaveable { mutableStateOf("") }
    var selectedTarget by rememberSaveable { mutableStateOf("") }
    var selectedEquipment by rememberSaveable { mutableStateOf("") }
    var visibleCount by rememberSaveable { mutableStateOf(60) }
    val defaultBodyPart = remember(libraryState.facets.bodyParts) {
        libraryState.facets.bodyParts.firstOrNull().orEmpty()
    }
    val activeBodyPart = selectedBodyPart.ifBlank { defaultBodyPart }
    LaunchedEffect(defaultBodyPart, libraryState.facets.bodyParts) {
        if (defaultBodyPart.isNotBlank() && selectedBodyPart !in libraryState.facets.bodyParts) {
            selectedBodyPart = defaultBodyPart
        }
    }
    val relatedTargets = remember(libraryState.facets.targetsByBodyPart, activeBodyPart) {
        libraryState.facets.targetsByBodyPart[activeBodyPart].orEmpty()
    }
    LaunchedEffect(activeBodyPart, relatedTargets) {
        if (selectedTarget.isNotBlank() && selectedTarget !in relatedTargets) {
            selectedTarget = ""
        }
    }
    val bodyPartCounts = remember(libraryState.exercises) {
        libraryState.exercises
            .map { it.bodyPart }
            .filter(String::isNotBlank)
            .groupingBy { it }
            .eachCount()
    }
    val filteredExercises = remember(libraryState.exercises, query, activeBodyPart, selectedEquipment, selectedTarget) {
        ExerciseLibraryRepository.filterExercises(
            exercises = libraryState.exercises,
            query = com.example.watcher.data.repository.ExerciseLibraryQuery(
                query = query,
                bodyParts = activeBodyPart.asSingleValueSet(),
                equipment = selectedEquipment.asSingleValueSet(),
                targets = selectedTarget.asSingleValueSet(),
                pageSize = Int.MAX_VALUE
            )
        )
    }
    LaunchedEffect(query, activeBodyPart, selectedEquipment, selectedTarget) {
        visibleCount = 60
    }
    val visibleExercises = filteredExercises.take(visibleCount)
    val hasActiveFilters = query.isNotBlank() ||
        selectedTarget.isNotBlank() ||
        selectedEquipment.isNotBlank() ||
        (defaultBodyPart.isNotBlank() && activeBodyPart != defaultBodyPart)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "清空搜索")
                    }
                }
            },
            placeholder = { Text("搜索动作、部位、器械或肌群") }
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ExerciseLibrarySidebar(
                bodyParts = libraryState.facets.bodyParts,
                selectedBodyPart = activeBodyPart,
                onSelectBodyPart = {
                    selectedBodyPart = it
                    selectedTarget = ""
                },
                targets = relatedTargets,
                selectedTarget = selectedTarget,
                bodyPartCounts = bodyPartCounts,
                onSelectTarget = { target ->
                    selectedTarget = if (selectedTarget == target) "" else target
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            Column(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExerciseLibraryResultsHeader(
                    loading = libraryState.loading,
                    totalCount = libraryState.totalCount,
                    resultCount = filteredExercises.size,
                    bodyPart = activeBodyPart,
                    target = selectedTarget,
                    equipment = selectedEquipment,
                    query = query,
                    errorMessage = libraryState.errorMessage,
                    hasActiveFilters = hasActiveFilters,
                    onClearAll = {
                        query = ""
                        selectedBodyPart = defaultBodyPart
                        selectedTarget = ""
                        selectedEquipment = ""
                    }
                )
                ExerciseEquipmentFilterRow(
                    equipment = libraryState.facets.equipment,
                    selectedEquipment = selectedEquipment,
                    equipmentCounts = libraryState.facets.equipmentCounts,
                    onSelectEquipment = { value ->
                        selectedEquipment = if (selectedEquipment == value) "" else value
                    }
                )
                when {
                    !libraryState.loading && visibleExercises.isEmpty() -> {
                        WatcherCard {
                            Text("没有找到匹配动作", style = MaterialTheme.typography.titleMedium)
                            Text("换一个关键词，或清空筛选后再试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (hasActiveFilters) {
                                TextButton(
                                    onClick = {
                                        query = ""
                                        selectedBodyPart = defaultBodyPart
                                        selectedTarget = ""
                                        selectedEquipment = ""
                                    }
                                ) {
                                    Text("清空筛选")
                                }
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleExercises, key = { it.id }) { exercise ->
                                ExerciseLibraryGridCard(
                                    exercise = exercise,
                                    onClick = { onSelectExercise(exercise) }
                                )
                            }
                            if (visibleCount < filteredExercises.size) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    OutlinedButton(
                                        onClick = { visibleCount += 60 },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(18.dp)
                                    ) {
                                        Text("加载更多 ${filteredExercises.size - visibleCount} 个动作")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseLibrarySidebar(
    bodyParts: List<String>,
    selectedBodyPart: String,
    onSelectBodyPart: (String) -> Unit,
    targets: List<String>,
    selectedTarget: String,
    bodyPartCounts: Map<String, Int>,
    onSelectTarget: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 10.dp)
    ) {
        bodyParts.forEach { bodyPart ->
            item(key = bodyPart) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExerciseBodyPartNavItem(
                        label = bodyPartLabel(bodyPart),
                        count = bodyPartCounts[bodyPart] ?: 0,
                        selected = bodyPart == selectedBodyPart,
                        onClick = { onSelectBodyPart(bodyPart) }
                    )
                    if (bodyPart == selectedBodyPart) {
                        targets.forEach { target ->
                            ExerciseTargetNavItem(
                                label = targetLabel(target),
                                selected = target == selectedTarget,
                                onClick = { onSelectTarget(target) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseBodyPartNavItem(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExerciseTargetNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExerciseLibraryResultsHeader(
    loading: Boolean,
    totalCount: Int,
    resultCount: Int,
    bodyPart: String,
    target: String,
    equipment: String,
    query: String,
    errorMessage: String,
    hasActiveFilters: Boolean,
    onClearAll: () -> Unit
) {
    val title = listOf(
        bodyPartLabel(bodyPart).takeIf { bodyPart.isNotBlank() },
        targetLabel(target).takeIf { target.isNotBlank() },
        equipmentLabel(equipment).takeIf { equipment.isNotBlank() }
    ).filterNotNull().joinToString(" · ").ifBlank { "动作库" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasActiveFilters) {
                    TextButton(onClick = onClearAll) { Text("清空") }
                }
            }
            Text(
                text = if (loading) {
                    "正在准备离线动作库"
                } else {
                    "$resultCount / $totalCount 个动作"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (query.isNotBlank()) {
                Text(
                    "搜索：$query",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (errorMessage.isNotBlank()) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ExerciseEquipmentFilterRow(
    equipment: List<String>,
    selectedEquipment: String,
    equipmentCounts: Map<String, Int>,
    onSelectEquipment: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        equipment.forEach { value ->
            OptionChip(
                label = "${equipmentLabel(value)} ${equipmentCounts[value] ?: 0}",
                selected = selectedEquipment == value,
                onClick = { onSelectEquipment(value) }
            )
        }
    }
}

private fun String.asSingleValueSet(): Set<String> = if (isNotBlank()) setOf(this) else emptySet()

@Composable
private fun ExerciseLibraryGridCard(
    exercise: ExerciseLibraryItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ExerciseAssetImage(
                assetPath = exercise.image,
                contentDescription = exercise.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.08f),
                contentScale = ContentScale.Crop
            )
            Text(exercise.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(exercise.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${equipmentLabel(exercise.equipment)} · ${targetLabel(exercise.target)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseLibraryDetailPage(
    exercise: ExerciseLibraryItem,
    modifier: Modifier = Modifier
) {
    val languageOrder = listOf("zh", "en", "es", "it", "tr", "ru", "hi", "pl", "ko", "fr")
    val availableLanguages = languageOrder.filter { exercise.stepsFor(it).isNotEmpty() }
    var selectedLanguage by rememberSaveable(exercise.id) { mutableStateOf("zh") }
    LaunchedEffect(exercise.id, availableLanguages) {
        if (selectedLanguage !in availableLanguages) {
            selectedLanguage = availableLanguages.firstOrNull() ?: "zh"
        }
    }
    val steps = exercise.stepsFor(selectedLanguage)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        WatcherCard {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                ExerciseAssetImage(
                    assetPath = exercise.gifUrl.ifBlank { exercise.image },
                    fallbackAssetPath = exercise.image,
                    contentDescription = exercise.title,
                    modifier = Modifier.size(132.dp),
                    contentScale = ContentScale.Crop,
                    animated = exercise.gifUrl.isNotBlank()
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(exercise.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(exercise.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusPill(bodyPartLabel(exercise.bodyPart), MaterialTheme.colorScheme.primary)
                    Text("器械：${equipmentLabel(exercise.equipment)}", style = MaterialTheme.typography.bodyMedium)
                    Text("目标：${targetLabel(exercise.target)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        WatcherCard {
            Text("参与肌群", style = MaterialTheme.typography.titleMedium)
            SummaryLine("主要协同", targetLabel(exercise.muscleGroup).ifBlank { exercise.muscleGroup.ifBlank { "-" } })
            SummaryLine("辅助肌群", exercise.secondaryMuscles.joinToString("、") { targetLabel(it) }.ifBlank { "-" })
        }
        WatcherCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("动作步骤", style = MaterialTheme.typography.titleMedium)
                Text(languageLabel(selectedLanguage), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (availableLanguages.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableLanguages.forEach { language ->
                        OptionChip(
                            label = languageLabel(language),
                            selected = selectedLanguage == language,
                            onClick = { selectedLanguage = language }
                        )
                    }
                }
            }
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                        Text(
                            text = (index + 1).toString(),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(step, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(
            text = exercise.attribution.ifBlank { "© Gym visual — https://gymvisual.com/" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ExerciseAssetImage(
    assetPath: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animated: Boolean = false,
    fallbackAssetPath: String = ""
) {
    val context = LocalContext.current
    val resolvedPath = assetPath.ifBlank { fallbackAssetPath }
    val bitmap = remember(resolvedPath, fallbackAssetPath, animated) {
        if (animated) null else decodeExerciseBitmap(context, resolvedPath.ifBlank { fallbackAssetPath })
    }
    val animatedDrawable = remember(resolvedPath, animated) {
        if (!animated || resolvedPath.isBlank()) {
            null
        } else {
            runCatching {
                val bytes = context.assets.open("${ExerciseLibraryRepository.EXERCISE_LIBRARY_ASSET_ROOT}/$resolvedPath").use { it.readBytes() }
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))).also { drawable ->
                    (drawable as? AnimatedImageDrawable)?.start()
                }
            }.getOrNull()
        }
    }
    val fallbackBitmap = remember(fallbackAssetPath, animatedDrawable) {
        if (animatedDrawable == null && fallbackAssetPath.isNotBlank()) {
            decodeExerciseBitmap(context, fallbackAssetPath)
        } else {
            null
        }
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        when {
            animatedDrawable != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        ImageView(viewContext).apply {
                            scaleType = if (contentScale == ContentScale.Crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(animatedDrawable)
                            (animatedDrawable as? AnimatedImageDrawable)?.start()
                        }
                    },
                    update = { imageView ->
                        imageView.setImageDrawable(animatedDrawable)
                        (animatedDrawable as? AnimatedImageDrawable)?.start()
                    }
                )
            }
            bitmap != null || fallbackBitmap != null -> {
                Image(
                    bitmap = (bitmap ?: fallbackBitmap)!!.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale
                )
            }
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Accessibility, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun decodeExerciseBitmap(context: android.content.Context, path: String) = runCatching {
    context.assets.open("${ExerciseLibraryRepository.EXERCISE_LIBRARY_ASSET_ROOT}/$path").use { input ->
        BitmapFactory.decodeStream(input)
    }
}.getOrNull()

private fun languageLabel(language: String): String {
    return when (language) {
        "zh" -> "中文"
        "en" -> "English"
        "es" -> "Español"
        "it" -> "Italiano"
        "tr" -> "Türkçe"
        "ru" -> "Русский"
        "hi" -> "हिन्दी"
        "pl" -> "Polski"
        "ko" -> "한국어"
        "fr" -> "Français"
        else -> language
    }
}

@Composable
private fun FitnessProfilePage(
    uiState: FitnessCompanionUiState,
    draft: FitnessOnboardingDraft,
    viewModel: FitnessCompanionViewModel
) {
    WatcherCard {
        Text("个人信息", style = MaterialTheme.typography.titleMedium)
        SummaryLine("目标", goalLabel(draft.goalType))
        SummaryLine("重点部位", draft.targetParts.joinToString("、").ifBlank { "全身均衡" })
        SummaryLine(
            "体重",
            "${"%.1f".format(draft.currentWeightKg)} kg" +
                if (draft.requiresTargetWeight) " -> ${"%.1f".format(draft.targetWeightKg)} kg" else ""
        )
        SummaryLine("体型", "${draft.currentBodyType.ifBlank { "未选择" }} -> ${draft.targetBodyType.ifBlank { "未选择" }}")
        SummaryLine("运动频次", draft.exerciseFrequency.ifBlank { "未选择" })
        SummaryLine("训练场所", draft.preferredPlaces.joinToString("、").ifBlank { "未选择" })
    }

    WatcherCard {
        Text("计划设置", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (uiState.generating) "正在生成中，完成后会自动刷新。" else "资料或目标变化后，可以在这里重新生成。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = viewModel::startProfileEditing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.AccountCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重新填写个人信息")
        }
        FilledTonalButton(
            onClick = viewModel::regeneratePlan,
            enabled = !uiState.generating,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重新生成训练计划")
        }
        OutlinedButton(
            onClick = viewModel::regenerateStrategyGoals,
            enabled = !uiState.generating,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.Analytics, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重新生成战略目标")
        }
    }
}

@Composable
private fun FitnessBottomNavigation(
    selectedTab: String,
    onSelect: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FitnessNavItem(
                label = "动作库",
                icon = Icons.Default.Analytics,
                selected = selectedTab == "library",
                onClick = { onSelect("library") },
                modifier = Modifier.weight(1f)
            )
            FitnessNavItem(
                label = "训练",
                icon = Icons.Default.Accessibility,
                selected = selectedTab == "train",
                onClick = { onSelect("train") },
                modifier = Modifier.weight(1f)
            )
            FitnessNavItem(
                label = "我的",
                icon = Icons.Default.AccountCircle,
                selected = selectedTab == "profile",
                onClick = { onSelect("profile") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FitnessNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TrainPage(
    uiState: FitnessCompanionUiState,
    viewModel: FitnessCompanionViewModel,
    onStartTraining: () -> Unit
) {
    StrategyGoalSection(uiState.strategyGoals)
    WorkoutPlanSection(
        plan = uiState.activePlan,
        exercises = uiState.exercises,
        viewModel = viewModel,
        onStartTraining = onStartTraining
    )
}

@Composable
private fun StrategyGoalSection(goals: List<FitnessStrategyGoalEntity>) {
    WatcherCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("阶段战略目标", style = MaterialTheme.typography.titleMedium)
        }
        if (goals.isEmpty()) {
            Text("正在生成阶段目标...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            goals.take(3).forEach { goal ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(goal.phaseLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(goal.title, style = MaterialTheme.typography.titleSmall)
                        Text(goal.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutPlanSection(
    plan: FitnessWorkoutPlanEntity?,
    exercises: List<FitnessWorkoutExerciseEntity>,
    viewModel: FitnessCompanionViewModel,
    onStartTraining: () -> Unit
) {
    WatcherCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("本次训练计划", style = MaterialTheme.typography.titleMedium)
        }
        if (plan == null) {
            Text("计划正在生成。若长时间没有结果，可以稍后重新生成。")
            FilledTonalButton(onClick = viewModel::regeneratePlan, shape = RoundedCornerShape(18.dp)) {
                Text("重新生成")
            }
        } else {
            Text(plan.title, style = MaterialTheme.typography.headlineSmall)
            Text(plan.objective, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("${plan.estimatedMinutes} 分钟", MaterialTheme.colorScheme.primary)
                StatusPill(plan.intensityLabel, Color(0xFF0E8B65))
            }
            Text("热身：${plan.warmup.displayPlanText("按计划完成热身步骤")}", style = MaterialTheme.typography.bodyMedium)
            exercises.forEach { exercise ->
                ExerciseRow(exercise = exercise)
            }
            Text("放松：${plan.cooldown.displayPlanText("按计划完成放松恢复")}", style = MaterialTheme.typography.bodyMedium)
            if (plan.coachNotes.isNotBlank()) {
                Text(plan.coachNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (plan.status == FitnessWorkoutStatus.Planned.name) {
                Button(
                    onClick = onStartTraining,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Default.Accessibility, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始训练")
                }
            } else {
                StatusPill("已完成", Color(0xFF0E8B65))
            }
        }
    }
}

@Composable
private fun FeedbackBlock(
    completionLevel: String,
    onCompletionLevel: (String) -> Unit,
    fatigueLevel: String,
    onFatigueLevel: (String) -> Unit,
    painSignal: String,
    onPainSignal: (String) -> Unit,
    nextIntensity: String,
    onNextIntensity: (String) -> Unit,
    noteOption: String,
    onNoteOption: (String) -> Unit
) {
    OptionGroup("完成程度", listOf("全部完成" to "全部完成", "基本完成" to "基本完成", "完成一半" to "完成一半"), completionLevel, onCompletionLevel)
    OptionGroup("疲劳感", listOf("很轻松" to "很轻松", "刚刚好" to "刚刚好", "有点累" to "有点累", "太累了" to "太累了"), fatigueLevel, onFatigueLevel)
    OptionGroup("疼痛评分", listOf("0分" to "0分", "2分" to "2分", "4分" to "4分", "6分" to "6分"), painSignal, onPainSignal)
    OptionGroup("下次强度", listOf("降低强度" to "降低强度", "保持强度" to "保持强度", "提高一点" to "提高一点"), nextIntensity, onNextIntensity)
    OptionGroup("备注选项", listOf("状态正常" to "状态正常", "时间不够" to "时间不够", "器械排队" to "器械排队", "动作不熟" to "动作不熟", "替换了动作" to "替换了动作"), noteOption, onNoteOption)
}

@Composable
private fun TrainingInProgressPage(
    uiState: FitnessCompanionUiState,
    viewModel: FitnessCompanionViewModel,
    onEndTraining: () -> Unit,
    modifier: Modifier = Modifier
) {
    val plan = uiState.activePlan
    val currentExercise = uiState.exercises.firstOrNull()
    val liveState by viewModel.realtimeVlmState.collectAsStateWithLifecycle()
    val repCounterState by viewModel.repCounterState.collectAsStateWithLifecycle()
    val streamSettings by viewModel.videoStreamSettings.collectAsStateWithLifecycle(initialValue = null)
    val settings = (streamSettings ?: VideoStreamSettings()).normalized()
    val hasStreamSettings = VideoStreamSettings.shouldAutoConnect(streamSettings)

    DisposableEffect(Unit) {
        StreamReservation.reserve(FITNESS_TRAINING_STREAM_OWNER)
        onDispose {
            viewModel.stopTrainingAnalyzers()
            StreamReservation.release(FITNESS_TRAINING_STREAM_OWNER)
        }
    }

    LaunchedEffect(plan?.id, currentExercise?.id) {
        if (plan != null) {
            viewModel.startRealtimeVlmFeedback(plan, currentExercise)
            viewModel.startRepCounter(plan, currentExercise)
        }
    }

    val streamState = rememberMjpegStreamState(
        settings = settings,
        isPlaying = hasStreamSettings,
        previewActive = true,
        reservationOwner = FITNESS_TRAINING_STREAM_OWNER,
        autoSelectFallbackOnUnavailable = true,
        onFrameUpdate = viewModel::updateTrainingFrame
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FitnessTrainingVideoCard(
            streamFrame = streamState.currentFrame,
            connectionStatus = streamState.connectionStatus,
            fps = streamState.fps,
            hasStreamSettings = hasStreamSettings,
            repCounterState = repCounterState
        )
        FitnessRealtimeFeedbackV2Card(
            exercise = currentExercise,
            liveState = liveState
        )
        FitnessRepCounterDebugCard(repCounterState = repCounterState)
        OutlinedButton(
            onClick = onEndTraining,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("结束训练")
        }
    }
}

@Composable
private fun FitnessTrainingVideoCard(
    streamFrame: android.graphics.Bitmap?,
    connectionStatus: ConnectionStatus,
    fps: Int,
    hasStreamSettings: Boolean,
    repCounterState: FitnessRepCounterState
) {
    WatcherCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("训练画面", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            StatusPill(if (fps > 0) "${fps}fps" else "等待画面", MaterialTheme.colorScheme.primary)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            color = Color.Black,
            shape = RoundedCornerShape(18.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (streamFrame != null) {
                    Image(
                        bitmap = streamFrame.asImageBitmap(),
                        contentDescription = "训练实时画面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    PoseOverlay(
                        result = repCounterState.poseResult,
                        modifier = Modifier.fillMaxSize(),
                        imageAspectRatio = streamFrame.width.toFloat() / streamFrame.height.toFloat(),
                        scaleToFill = true,
                        highlightedLandmarkLabels = repCounterState.activeLandmarks.toSet(),
                        visibilityThreshold = 0.55f
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White.copy(alpha = 0.42f), modifier = Modifier.size(44.dp))
                        Text(
                            text = when {
                                !hasStreamSettings -> "还没有视频流设置"
                                connectionStatus is ConnectionStatus.Error -> connectionStatus.message
                                connectionStatus == ConnectionStatus.Connecting -> "正在接入训练画面"
                                else -> "等待训练画面"
                            },
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FitnessRepCounterDebugCard(
    repCounterState: FitnessRepCounterState
) {
    val reason = repCounterState.notCountingReason.ifBlank {
        repCounterState.rejectionReason.ifBlank { "暂无阻塞原因" }
    }
    WatcherCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("计数调试", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            StatusPill(repCounterState.counterDebugStatusText(), MaterialTheme.colorScheme.primary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = repCounterState.officialRepCount.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "正式次数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "MediaPipe 正式计数 ${repCounterState.officialRepCount} · 候选诊断 ${repCounterState.candidatePendingCount}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RepDebugPill("active", repCounterState.active.toString())
            RepDebugPill("phase", repCounterState.phase.name)
            RepDebugPill("calibration", repCounterState.calibrationPhase.ifBlank { "-" })
            RepDebugPill("fps", repCounterState.fps.toString())
            RepDebugPill("inference", "${repCounterState.inferenceTimeMs}ms")
            RepDebugPill("confidence", repCounterState.confidence.debugPercent())
            RepDebugPill("score", repCounterState.signalScore.debugPercent())
            RepDebugPill("amplitude", repCounterState.signalAmplitude.debugNumber())
            RepDebugPill("angle", repCounterState.primaryAngleDeg.debugNumber())
            RepDebugPill("low/high", "${repCounterState.lowThreshold.debugNumber()}/${repCounterState.highThreshold.debugNumber()}")
            RepDebugPill("signal", repCounterState.primarySignalId.ifBlank { repCounterState.selectedSignalId.ifBlank { "-" } })
            RepDebugPill("family", repCounterState.primarySignalFamily.ifBlank { repCounterState.candidateFamily.ifBlank { "-" } })
            RepDebugPill("locked", repCounterState.lockedFamily.ifBlank { "-" })
            RepDebugPill("reject", repCounterState.rejectionReason.ifBlank { "-" })
        }
    }
}

@Composable
private fun RepDebugPill(
    label: String,
    value: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = "$label: $value",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun FitnessRepCounterState.counterDebugStatusText(): String {
    if (!active) return "未启动"
    return status.name
}

private fun Float.debugPercent(): String = "${(this * 100f).roundToInt()}%"

private fun Float.debugNumber(): String = ((this * 10f).roundToInt() / 10f).toString()

@Composable
private fun FitnessRealtimeFeedbackV2Card(
    exercise: FitnessWorkoutExerciseEntity?,
    liveState: FitnessRealtimeVlmState
) {
    val supported = exercise != null
    val displayCoach = liveState.latestCoachCandidate
    val displayObservations = liveState.latestObservations
    WatcherCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("实时观察", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            StatusPill(
                text = liveState.realtimeDisplayStatus(supported),
                accent = if (displayCoach?.acceptedAsFeedback == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        }
        Text(
            text = exercise?.name ?: "暂无当前动作",
            style = MaterialTheme.typography.headlineSmall
        )
        if (!supported) {
            Text(
                text = "实时反馈暂未支持该动作。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前观察",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (displayObservations.isEmpty()) {
                        Text(
                            text = liveState.realtimeObservationPlaceholder(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        displayObservations.forEach { observation ->
                            FitnessVlmObservationItem(observation)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "教练反馈",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = displayCoach?.message ?: liveState.realtimeFeedbackPlaceholder(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (displayCoach != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    displayCoach?.let { coach ->
                        Text(
                            text = coach.displayStatusText(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (coach.acceptedAsFeedback) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
            Text(
                text = liveState.realtimeSubStatus(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        liveState.lastError?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun FitnessRealtimeVlmState.realtimeDisplayStatus(supported: Boolean): String {
    if (!supported) return "未支持"
    if (!active) return "未启动"
    if (latestCoachCandidate?.acceptedAsFeedback == true) return "反馈已验证"
    if (latestCoachCandidate != null) return "反馈未验证"
    return "观察中"
}

@Composable
private fun FitnessVlmObservationItem(observation: FitnessVlmObservationDisplay) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = observation.observation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${observation.displayStatusText()} · ${(observation.confidence * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = if (observation.acceptedAsEvidence) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}

private fun FitnessVlmObservationDisplay.displayStatusText(): String {
    return when (observability) {
        FitnessVlmObservability.CLEAR -> "已纳入证据"
        FitnessVlmObservability.PARTIAL -> "部分可见 · 已纳入证据"
        FitnessVlmObservability.NOT_OBSERVABLE -> "不可观察 · 未纳入证据"
        FitnessVlmObservability.INSUFFICIENT_EVIDENCE -> "证据不足 · 未纳入证据"
    }
}

private fun FitnessVlmCoachDisplay.displayStatusText(): String {
    if (acceptedAsFeedback) return "已通过证据闭环 · ${(confidence * 100f).roundToInt()}%"
    val reason = when {
        blockReasons.any { it.startsWith("coach_probe_not_supported") } -> "Probe 尚未闭环"
        blockReasons.any { it.startsWith("coach_missing_finding_evidence") } -> "缺少 Finding 证据"
        blockReasons.any { it == "direct_coach_low_confidence" } -> "置信度不足"
        blockReasons.any { it == "direct_coach_unclear_evidence" } -> "视觉事实不够清晰"
        blockReasons.any { it == "direct_coach_weak_fact" } -> "事实证据较弱"
        blockReasons.any { it == "coach_unknown_fact_reference" } -> "引用事实不存在"
        blockReasons.any { it == "coach_requires_current_fact" } -> "缺少当前画面事实"
        else -> "证据尚未闭环"
    }
    return "未采纳：$reason · ${(confidence * 100f).roundToInt()}%"
}

private fun FitnessRealtimeVlmState.realtimeObservationPlaceholder(): String {
    return when {
        !active -> "等待开始观察"
        analyzing -> "正在识别当前画面"
        else -> "等待形成有效视觉事实"
    }
}

private fun FitnessRealtimeVlmState.realtimeFeedbackPlaceholder(): String {
    return when {
        !active -> "等待开始观察"
        else -> "尚未形成可确认的教练反馈"
    }
}

private fun FitnessRealtimeVlmState.realtimeSubStatus(): String {
    return statusText
        .takeIf { it.isNotBlank() }
        ?: "VLM 工作池持续追踪最新画面"
}

@Composable
private fun ExerciseRow(
    exercise: FitnessWorkoutExerciseEntity
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                Text(
                    text = (exercise.sortOrder + 1).toString(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                Text("${exercise.equipment} · ${exercise.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val restText = if (exercise.restSecondsMin > 0 && exercise.restSecondsMax > 0) {
                    "${exercise.restSecondsMin}-${exercise.restSecondsMax} 秒"
                } else {
                    "${exercise.restSeconds} 秒"
                }
                val rirText = if (exercise.targetRir > 0f) " · RIR ${"%.1f".format(exercise.targetRir)}" else ""
                val dosage = if (exercise.durationSeconds > 0) "${exercise.durationSeconds / 60} 分钟" else "${exercise.sets} 组 · ${exercise.reps}"
                Text("$dosage · 休息 $restText · ${exercise.intensity}$rirText", style = MaterialTheme.typography.bodyMedium)
                if (exercise.loadSelectionRule.isNotBlank()) {
                    Text(exercise.loadSelectionRule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (exercise.stopCondition.isNotBlank()) {
                    Text("停止条件：${exercise.stopCondition}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (exercise.notes.isNotBlank()) {
                    Text(exercise.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun String.displayPlanText(defaultValue: String): String {
    val trimmed = trim()
    return when {
        trimmed.isBlank() -> defaultValue
        trimmed == "[]" || trimmed == "{}" -> defaultValue
        trimmed.startsWith("[") || trimmed.startsWith("{") -> defaultValue
        else -> trimmed
    }
}

@Composable
private fun LatestPlanPreview(
    plan: FitnessWorkoutPlanEntity?,
    exercises: List<FitnessWorkoutExerciseEntity>,
    onOpen: () -> Unit,
    onRegenerate: () -> Unit
) {
    WatcherCard {
        Text("今天的陪练", style = MaterialTheme.typography.titleMedium)
        if (plan == null) {
            Text("训练计划正在准备中。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onRegenerate, shape = RoundedCornerShape(18.dp)) {
                Text("生成训练计划")
            }
        } else {
            Text(plan.title, style = MaterialTheme.typography.headlineSmall)
            Text(plan.objective, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${exercises.size} 个动作 · 预计 ${plan.estimatedMinutes} 分钟")
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Text("查看训练")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun CompanionModeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    WatcherCard(onClick = if (enabled) onClick else null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = color.copy(alpha = 0.13f)) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(14.dp), tint = color)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled) Icon(Icons.Default.ArrowForward, contentDescription = null, tint = color)
        }
    }
}

@Composable
private fun FitnessSurface(content: @Composable ColumnScope.() -> Unit) {
    val extendedColors = LocalWatcherExtendedColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        extendedColors.surfaceContainerLow,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun FitnessTopBar(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    closeIcon: ImageVector = Icons.Default.Close
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClose) {
            Icon(closeIcon, contentDescription = "返回")
        }
    }
}

@Composable
private fun FitnessSection(eyebrow: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionGroup(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                OptionChip(label = label, selected = selected == value, onClick = { onSelect(value) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiOptionGroup(
    title: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { label ->
                OptionChip(label = label, selected = label in selected, onClick = { onToggle(label) })
            }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, color.copy(alpha = if (selected) 0.75f else 0.24f)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NumberSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChange: (Float) -> Unit
) {
    WatcherCard {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("${"%.1f".format(value)} $suffix", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange((it * 10).roundToInt() / 10f) },
            valueRange = range
        )
    }
}

@Composable
private fun IntegerSlider(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChange: (Int) -> Unit
) {
    WatcherCard {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("$value $suffix", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range,
            steps = (range.endInclusive - range.start).roundToInt().coerceAtLeast(0)
        )
    }
}

@Composable
private fun PositiveCard(text: String, formula: String? = null) {
    WatcherCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                if (formula != null) {
                    Text(formula, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SummaryAdviceCard(draft: FitnessOnboardingDraft) {
    PositiveCard(
        text = buildString {
            append("建议先从低冲击有氧和基础力量开始。")
            if ("膝盖" in draft.injuryParts || draft.stairFeeling == "膝盖不舒服") append(" 膝盖反馈会被优先保护。")
            if (draft.sleepQuality == "睡眠不足" || draft.sleepQuality == "经常熬夜") append(" 睡眠不足时会降低训练强度。")
        }
    )
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun bodyTypes(): List<String> = listOf("偏瘦", "普通", "微胖", "偏胖", "强壮", "线条感")

private fun goalLabel(goalType: String): String {
    return when (goalType) {
        FitnessGoalType.WeightLoss.name -> "减脂减重"
        FitnessGoalType.MuscleTone.name -> "增肌塑形"
        FitnessGoalType.HealthyHabit.name -> "保持健康"
        else -> "未选择"
    }
}

private fun bodyPartLabel(value: String): String {
    return when (value) {
        "back" -> "背部"
        "cardio" -> "有氧"
        "chest" -> "胸部"
        "lower arms" -> "前臂"
        "lower legs" -> "小腿"
        "neck" -> "颈部"
        "shoulders" -> "肩部"
        "upper arms" -> "上臂"
        "upper legs" -> "大腿"
        "waist" -> "腰腹"
        else -> value.ifBlank { "未分类" }
    }
}

private fun equipmentLabel(value: String): String {
    return when (value) {
        "assisted" -> "辅助器械"
        "band" -> "弹力带"
        "barbell" -> "杠铃"
        "body weight" -> "自重"
        "bosu ball" -> "波速球"
        "cable" -> "绳索"
        "dumbbell" -> "哑铃"
        "ez barbell" -> "EZ 杠"
        "kettlebell" -> "壶铃"
        "leverage machine" -> "固定器械"
        "medicine ball" -> "药球"
        "olympic barbell" -> "奥杆"
        "resistance band" -> "阻力带"
        "roller" -> "泡沫轴"
        "rope" -> "绳"
        "sled machine" -> "雪橇机"
        "smith machine" -> "史密斯机"
        "stability ball" -> "瑜伽球"
        "weighted" -> "负重"
        "wheel roller" -> "健腹轮"
        else -> value.ifBlank { "未知器械" }
    }
}

private fun targetLabel(value: String): String {
    return when (value) {
        "abs" -> "腹肌"
        "abductors" -> "外展肌"
        "adductors" -> "内收肌"
        "biceps" -> "肱二头肌"
        "brachialis" -> "肱肌"
        "calves" -> "小腿"
        "cardiovascular system" -> "心肺"
        "delts" -> "三角肌"
        "forearms" -> "前臂"
        "glutes" -> "臀肌"
        "hamstrings" -> "腘绳肌"
        "hip flexors" -> "髋屈肌"
        "lats" -> "背阔肌"
        "levator scapulae" -> "肩胛提肌"
        "pectorals" -> "胸肌"
        "quads" -> "股四头肌"
        "serratus anterior" -> "前锯肌"
        "spine" -> "竖脊肌"
        "traps" -> "斜方肌"
        "triceps" -> "肱三头肌"
        "upper back" -> "上背"
        else -> value.ifBlank { "未知肌群" }
    }
}
