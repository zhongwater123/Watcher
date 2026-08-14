package com.example.watcher.data.intentrouter

import java.util.Locale

enum class IntentRouteId(val wireId: String) {
    Monitor("monitor"),
    Home("home"),
    Analysis("analysis"),
    History("history"),
    Templates("templates");

    companion object {
        fun fromWireId(value: String): IntentRouteId? {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.wireId == normalized }
        }
    }
}

data class IntentRouteDefinition(
    val id: IntentRouteId,
    val title: String,
    val description: String,
    val guidance: String,
    val examples: List<String>,
    val keywords: List<String>
)

enum class IntentRouterDecisionSource {
    LocalKeyword,
    Llm
}

data class IntentRouterDecision(
    val route: IntentRouteDefinition,
    val confidence: Float,
    val source: IntentRouterDecisionSource = IntentRouterDecisionSource.Llm
)

sealed interface IntentRouterParseResult {
    data class Success(val decision: IntentRouterDecision) : IntentRouterParseResult
    data class Failure(val reason: String) : IntentRouterParseResult
}

object IntentRouteCatalog {
    val routes: List<IntentRouteDefinition> = listOf(
        IntentRouteDefinition(
            id = IntentRouteId.Monitor,
            title = "实时监控",
            description = "用于持续盯着当前视频画面、检测变化、发出提醒。",
            guidance = "你已经来到实时监控。接下来可以确认视频预览，输入要监控的目标，再启动监控任务。",
            examples = listOf(
                "帮我监控门口有没有人",
                "有人靠近时提醒我",
                "我要持续看护当前画面"
            ),
            keywords = listOf(
                "监控",
                "看护",
                "守护",
                "报警",
                "警报",
                "提醒",
                "检测变化",
                "异常",
                "门口",
                "盯着",
                "看家",
                "monitor",
                "alarm"
            )
        ),
        IntentRouteDefinition(
            id = IntentRouteId.Home,
            title = "首页总览",
            description = "用于查看当前连接、任务运行状态和最近进展。",
            guidance = "你已经回到首页总览。这里可以查看当前任务状态、视频连接和最近的运行进展。",
            examples = listOf(
                "回到首页",
                "查看当前运行状态",
                "看看现在连接是否正常"
            ),
            keywords = listOf(
                "首页",
                "主页",
                "总览",
                "状态",
                "当前",
                "回到首页",
                "运行中",
                "连接状态",
                "home",
                "dashboard"
            )
        ),
        IntentRouteDefinition(
            id = IntentRouteId.Analysis,
            title = "视频分析",
            description = "用于分析视频、课堂记录、生成总结、处理录像内容。",
            guidance = "你已经来到视频分析。接下来可以选择视频来源，描述分析目标，然后启动分析或课堂记录。",
            examples = listOf(
                "我想做课堂记录",
                "帮我分析一段视频",
                "生成这段录像的总结"
            ),
            keywords = listOf(
                "视频分析",
                "分析视频",
                "录像",
                "课堂记录",
                "课堂",
                "课程",
                "录课",
                "听课",
                "总结",
                "笔记",
                "知识树",
                "上课",
                "analysis",
                "analyze"
            )
        ),
        IntentRouteDefinition(
            id = IntentRouteId.History,
            title = "历史记录",
            description = "用于查看以前的监控结果、视频分析报告和执行日志。",
            guidance = "你已经来到历史记录。接下来可以筛选历史任务，打开记录详情，查看报告、截图或时间线。",
            examples = listOf(
                "我想看之前的监控结果",
                "打开历史报告",
                "查看以前的执行记录"
            ),
            keywords = listOf(
                "历史",
                "以前",
                "之前",
                "记录",
                "日志",
                "回放",
                "结果",
                "报告",
                "截图",
                "时间线",
                "history",
                "report"
            )
        ),
        IntentRouteDefinition(
            id = IntentRouteId.Templates,
            title = "统一配置",
            description = "用于管理模板、模型接口、专家、观众和相关配置。",
            guidance = "你已经来到统一配置。接下来可以维护监控模板、视频模板、模型钱包和专家配置。",
            examples = listOf(
                "我要配置模型钱包",
                "管理监控模板",
                "设置专家和观众"
            ),
            keywords = listOf(
                "配置",
                "设置",
                "模板",
                "模型",
                "钱包",
                "专家",
                "观众",
                "统一配置",
                "api",
                "llm",
                "provider",
                "doubao",
                "templates"
            )
        )
    )

    fun findByWireId(value: String): IntentRouteDefinition? {
        val routeId = IntentRouteId.fromWireId(value) ?: return null
        return routes.firstOrNull { it.id == routeId }
    }

    fun findByWireIdOrTitle(value: String): IntentRouteDefinition? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        return findByWireId(normalized)
            ?: routes.firstOrNull { it.title == normalized }
    }
}
