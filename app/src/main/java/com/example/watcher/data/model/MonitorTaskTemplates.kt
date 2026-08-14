package com.example.watcher.data.model

data class MonitorTaskTemplate(
    val id: String,
    val label: String,
    val description: String,
    val userRequirement: String,
    val originalSceneDescription: String,
    val checkIntervalSeconds: Int,
    val promptTemplate: String,
    val monitorMode: MonitorMode = MonitorMode.SceneBaseline,
    val targetTrigger: TargetTrigger = TargetTrigger.OnAppear,
    val baselineSource: BaselineSource = BaselineSource.CapturedFrame
) {
    fun toIntentResult(createdAt: Long = System.currentTimeMillis()): IntentResult {
        return IntentResult(
            title = label,
            userInput = label,
            userRequirement = userRequirement,
            originalSceneDescription = originalSceneDescription,
            checkInterval = checkIntervalSeconds,
            promptTemplate = promptTemplate,
            monitorMode = monitorMode,
            targetTrigger = targetTrigger,
            baselineSource = baselineSource,
            createdAt = createdAt
        ).normalized()
    }

    fun toEntity(): MonitorTemplateEntity {
        return MonitorTemplateEntity(
            templateId = id,
            label = label,
            description = description,
            userRequirement = userRequirement,
            originalSceneDescription = originalSceneDescription,
            checkIntervalSeconds = checkIntervalSeconds,
            promptTemplate = promptTemplate,
            monitorMode = monitorMode.name,
            targetTrigger = targetTrigger.name,
            baselineSource = baselineSource.name,
            isDefault = true
        )
    }
}

fun MonitorTemplateEntity.toMonitorTaskTemplate(): MonitorTaskTemplate {
    return MonitorTaskTemplate(
        id = templateId,
        label = label,
        description = description,
        userRequirement = userRequirement,
        originalSceneDescription = originalSceneDescription,
        checkIntervalSeconds = checkIntervalSeconds,
        promptTemplate = promptTemplate,
        monitorMode = runCatching { MonitorMode.valueOf(monitorMode) }.getOrDefault(MonitorMode.SceneBaseline),
        targetTrigger = runCatching { TargetTrigger.valueOf(targetTrigger) }.getOrDefault(TargetTrigger.OnAppear),
        baselineSource = runCatching { BaselineSource.valueOf(baselineSource) }.getOrDefault(BaselineSource.CapturedFrame)
    )
}

fun MonitorTemplateEntity.toIntentResult(): IntentResult {
    return toMonitorTaskTemplate().toIntentResult()
}

object MonitorTaskTemplates {
    val ChildSafetyWatch = MonitorTaskTemplate(
        id = "child_safety_watch",
        label = "小孩风险监控",
        description = "每 8 秒巡检一次，重点关注儿童跌倒、攀爬、接近危险区域等风险行为。",
        userRequirement = "监控画面中儿童的安全状况，当出现跌倒、攀爬高处、接近危险物品或区域、剧烈哭闹、离开安全范围等风险行为时及时告警。",
        originalSceneDescription = "室内或室外场景，画面中可能有一名或多名儿童活动，需持续关注儿童的姿态、位置和行为是否存在安全隐患。",
        checkIntervalSeconds = 8,
        promptTemplate = buildString {
            append("请监控当前画面中儿童的安全状况。重点关注以下风险行为：")
            append("1）跌倒或摔倒；2）攀爬桌椅、窗台等高处；")
            append("3）接近插座、刀具、热水等危险物品或区域；")
            append("4）剧烈哭闹或表情痛苦；5）离开监控安全范围。")
            append("请将基准图视为正常场景，判断当前画面是否出现偏离。")
            append("只返回 JSON，字段为 status、summary、reason、confidence、remark。")
            append(" remark 是给用户的一句简短中二病赛博诗人式旁白，不参与判断。")
            append(" status 只能是 ALERT、WARNING、NORMAL、UNKNOWN。")
            append(" ALERT：儿童正在发生危险行为（跌倒、攀爬高处、接触危险物品）。")
            append(" WARNING：儿童行为存在潜在风险（靠近危险区域、动作不稳、无人看管）。")
            append(" NORMAL：儿童状态安全，未发现异常。")
        },
        monitorMode = MonitorMode.SceneBaseline,
        baselineSource = BaselineSource.CapturedFrame
    )

    val PetMischiefWatch = MonitorTaskTemplate(
        id = "pet_mischief_watch",
        label = "宠物捣乱监控",
        description = "每 10 秒巡检一次，检测宠物翻垃圾桶、抓挠家具、跳上桌台等捣乱行为。",
        userRequirement = "监控画面中宠物的行为，当出现翻垃圾桶、抓挠沙发或家具、跳上餐桌或灶台、撕咬物品、偷吃食物等捣乱行为时及时告警。",
        originalSceneDescription = "室内家庭场景，画面中可能有猫、狗等宠物活动，需持续关注宠物是否在破坏物品或进入禁止区域。",
        checkIntervalSeconds = 10,
        promptTemplate = buildString {
            append("请监控当前画面中宠物的行为。重点关注以下捣乱行为：")
            append("1）翻垃圾桶或拖拽垃圾；2）抓挠沙发、窗帘等家具；")
            append("3）跳上餐桌、灶台、厨房台面等禁止区域；")
            append("4）撕咬鞋子、纸巾、电线等物品；5）偷吃桌面或台面上的食物。")
            append("请将基准图视为正常场景，判断当前画面是否出现偏离。")
            append("只返回 JSON，字段为 status、summary、reason、confidence、remark。")
            append(" remark 是给用户的一句简短中二病赛博诗人式旁白，不参与判断。")
            append(" status 只能是 ALERT、WARNING、NORMAL、UNKNOWN。")
            append(" ALERT：宠物正在进行破坏性行为（撕咬物品、翻垃圾桶、抓挠家具）。")
            append(" WARNING：宠物接近禁止区域或表现出捣乱倾向（跳上桌面、靠近垃圾桶）。")
            append(" NORMAL：宠物状态正常，未发现捣乱行为。")
        }
    )

    val IntruderDetection = MonitorTaskTemplate(
        id = "intruder_detection",
        label = "物体闯入监控",
        description = "每 5 秒巡检一次，检测画面中出现非预期的人、动物或物体闯入。",
        userRequirement = "监控画面中是否有非预期的人员、动物或物体闯入，当检测到场景中出现新的入侵物体时及时告警。",
        originalSceneDescription = "固定视角监控场景，基准画面为无人/无异物的正常状态，需检测任何新出现的人、动物或可疑物体。",
        checkIntervalSeconds = 5,
        promptTemplate = buildString {
            append("请监控当前画面，检测是否有非预期的物体闯入。重点关注：")
            append("1）出现基准画面中不存在的人员；2）出现动物（猫、狗、鸟等）；")
            append("3）出现新的可疑物体或包裹；4）车辆驶入画面；")
            append("5）任何破坏围栏、门窗等入侵行为。")
            append("请将基准图视为正常场景，判断当前画面是否出现偏离。")
            append("只返回 JSON，字段为 status、summary、reason、confidence、remark。")
            append(" remark 是给用户的一句简短中二病赛博诗人式旁白，不参与判断。")
            append(" status 只能是 ALERT、WARNING、NORMAL、UNKNOWN。")
            append(" ALERT：检测到明确的入侵者或闯入物体。")
            append(" WARNING：画面出现可疑变化，可能有物体正在接近或部分进入。")
            append(" NORMAL：画面与基准一致，未检测到闯入。")
        }
    )

    val CounterCustomerAlert = MonitorTaskTemplate(
        id = "counter_customer_alert",
        label = "柜台来客人提醒",
        description = "每 8 秒巡检一次，检测柜台/前台区域是否有顾客出现并等待服务。",
        userRequirement = "监控柜台或前台区域，当有顾客走近柜台并停留等待时及时提醒，避免顾客长时间无人接待。",
        originalSceneDescription = "柜台或前台场景，基准画面为无顾客等待的空闲状态，需检测是否有人走近并驻足等待服务。",
        checkIntervalSeconds = 8,
        promptTemplate = buildString {
            append("请监控柜台/前台区域，检测是否有顾客到来。重点关注：")
            append("1）有人走近柜台并面朝柜台方向；2）有人在柜台前驻足等待；")
            append("3）有人在柜台前张望或招手示意；4）排队等候的人数增加。")
            append("请将基准图视为无人等待的空闲状态，判断当前画面是否出现变化。")
            append("只返回 JSON，字段为 status、summary、reason、confidence、remark。")
            append(" remark 是给用户的一句简短中二病赛博诗人式旁白，不参与判断。")
            append(" status 只能是 ALERT、WARNING、NORMAL、UNKNOWN。")
            append(" ALERT：有顾客在柜台前等待且可能已等候较久。")
            append(" WARNING：有人正在走近柜台，可能需要服务。")
            append(" NORMAL：柜台前无人等待，处于空闲状态。")
        }
    )

    val ClassroomWatch = MonitorTaskTemplate(
        id = "classroom_watch",
        label = "班级学生看管",
        description = "每 10 秒巡检一次，监控教室内学生是否出现打闹、离座、异常聚集等情况。",
        userRequirement = "监控教室内学生的课堂行为，当出现打闹推搡、擅自离座走动、异常聚集、课堂秩序混乱等情况时及时告警。",
        originalSceneDescription = "教室场景，画面中有多名学生在座位上，基准画面为正常上课或自习的有序状态。",
        checkIntervalSeconds = 10,
        promptTemplate = buildString {
            append("请监控教室内学生的行为状态。重点关注：")
            append("1）学生之间打闹、推搡或肢体冲突；2）多名学生擅自离座走动；")
            append("3）学生异常聚集在某处；4）课堂秩序明显混乱（大面积交头接耳、站立）；")
            append("5）有学生趴桌睡觉或明显不在学习状态。")
            append("请将基准图视为正常课堂秩序，判断当前画面是否出现偏离。")
            append("只返回 JSON，字段为 status、summary、reason、confidence、remark。")
            append(" remark 是给用户的一句简短中二病赛博诗人式旁白，不参与判断。")
            append(" status 只能是 ALERT、WARNING、NORMAL、UNKNOWN。")
            append(" ALERT：学生发生打闹冲突或课堂秩序严重失控。")
            append(" WARNING：部分学生离座或课堂秩序有下滑趋势。")
            append(" NORMAL：学生状态正常，课堂秩序良好。")
        }
    )

    val all: List<MonitorTaskTemplate> = listOf(
        ChildSafetyWatch,
        PetMischiefWatch,
        IntruderDetection,
        CounterCustomerAlert,
        ClassroomWatch
    )

    fun findById(id: String?): MonitorTaskTemplate? = all.firstOrNull { it.id == id }

    fun defaultEntities(): List<MonitorTemplateEntity> = all.map { it.toEntity() }
}
