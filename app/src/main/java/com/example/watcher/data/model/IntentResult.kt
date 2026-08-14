package com.example.watcher.data.model

data class IntentResult(
    val taskId: Long? = null,
    val title: String,
    val userInput: String,
    val userRequirement: String,
    val originalSceneDescription: String,
    val checkInterval: Int,
    val promptTemplate: String,
    val baseFrameBase64: String? = null,
    val baselineImagePath: String? = null,
    val monitorMode: MonitorMode = MonitorMode.SceneBaseline,
    val targetTrigger: TargetTrigger = TargetTrigger.OnAppear,
    val baselineSource: BaselineSource = BaselineSource.CapturedFrame,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun normalized(): IntentResult {
        val safeRequirement = userRequirement.ifBlank { userInput.ifBlank { "\u76D1\u770B\u5F53\u524D\u753B\u9762" } }
        val safeTitle = title.ifBlank {
            safeRequirement.take(MAX_TITLE_LENGTH).ifBlank { "\u5FEB\u901F\u76D1\u63A7\u4EFB\u52A1" }
        }.take(MAX_TITLE_LENGTH)
        val safeScene = originalSceneDescription.ifBlank { "\u6682\u65E0\u573A\u666F\u63CF\u8FF0\u3002" }
        val safePrompt = promptTemplate.ifBlank {
            buildFallbackPrompt(safeRequirement, safeScene, monitorMode, targetTrigger)
        }

        return copy(
            title = safeTitle,
            userRequirement = safeRequirement,
            originalSceneDescription = safeScene,
            checkInterval = checkInterval.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
            promptTemplate = safePrompt
        )
    }

    fun toMonitorTask(): MonitorTask {
        val normalized = normalized()
        val now = System.currentTimeMillis()
        return MonitorTask(
            id = taskId ?: 0,
            title = normalized.title,
            userInput = normalized.userInput,
            userRequirement = normalized.userRequirement,
            originalSceneDescription = normalized.originalSceneDescription,
            checkInterval = normalized.checkInterval,
            promptTemplate = normalized.promptTemplate,
            baseFrameBase64 = normalized.baseFrameBase64,
            baselineImagePath = normalized.baselineImagePath,
            monitorMode = normalized.monitorMode,
            targetTrigger = normalized.targetTrigger,
            baselineSource = normalized.baselineSource,
            createdAt = createdAt,
            updatedAt = now
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 15
        const val MIN_INTERVAL_SECONDS = 2
        const val MAX_INTERVAL_SECONDS = 300
        const val MAX_TITLE_LENGTH = 48

        fun fromTask(task: MonitorTask): IntentResult {
            return IntentResult(
                taskId = task.id,
                title = task.title,
                userInput = task.userInput,
                userRequirement = task.userRequirement,
                originalSceneDescription = task.originalSceneDescription,
                checkInterval = task.checkInterval,
                promptTemplate = task.promptTemplate,
                baseFrameBase64 = task.baseFrameBase64,
                baselineImagePath = task.baselineImagePath,
                monitorMode = task.monitorMode,
                targetTrigger = task.targetTrigger,
                baselineSource = task.baselineSource,
                createdAt = task.createdAt
            ).normalized()
        }

        fun buildFallbackPrompt(
            requirement: String,
            sceneDescription: String,
            monitorMode: MonitorMode = MonitorMode.SceneBaseline,
            targetTrigger: TargetTrigger = TargetTrigger.OnAppear
        ): String {
            return when (monitorMode) {
                MonitorMode.SceneBaseline -> buildString {
                    append("\u8BF7\u76D1\u63A7\u5F53\u524D\u753B\u9762\uFF0C\u91CD\u70B9\u5173\u6CE8\uFF1A")
                    append(requirement)
                    append("\u3002\u8BF7\u5C06\u57FA\u51C6\u56FE\u89C6\u4E3A\u6B63\u5E38\u573A\u666F\uFF0C\u5224\u65AD\u5F53\u524D\u753B\u9762\u662F\u5426\u51FA\u73B0\u504F\u79BB\u3002")
                    append("\u53EA\u8FD4\u56DE JSON\uFF0C\u5B57\u6BB5\u4E3A status\u3001summary\u3001reason\u3001confidence\u3001remark\u3002")
                    append(" status \u53EA\u80FD\u662F ALERT\u3001WARNING\u3001NORMAL\u3001UNKNOWN\u3002")
                    append(" remark \u662F\u7ED9\u7528\u6237\u7684\u4E00\u53E5\u7B80\u77ED\u4E2D\u4E8C\u75C5\u8D5B\u535A\u8BD7\u4EBA\u5F0F\u65C1\u767D\uFF0C\u4E0D\u80FD\u4E0E\u5224\u65AD\u51B2\u7A81\uFF0C\u4E0D\u80FD\u7F16\u9020\u753B\u9762\u4E8B\u5B9E\u3002")
                    append(" \u53C2\u8003\u573A\u666F\uFF1A")
                    append(sceneDescription)
                }

                MonitorMode.ReferenceTarget -> buildString {
                    append("\u8BF7\u76D1\u63A7\u5F53\u524D\u753B\u9762\u4E2D\u662F\u5426\u51FA\u73B0\u57FA\u51C6\u56FE\u6240\u793A\u7684\u76EE\u6807\uFF0C\u91CD\u70B9\u5173\u6CE8\uFF1A")
                    append(requirement)
                    append("\u3002\u57FA\u51C6\u56FE\u8868\u793A\u53C2\u8003\u76EE\u6807\u800C\u4E0D\u662F\u6574\u4E2A\u573A\u666F\u3002")
                    append(
                        when (targetTrigger) {
                            TargetTrigger.OnAppear -> "\u5982\u679C\u76EE\u6807\u51FA\u73B0\u6216\u660E\u663E\u5339\u914D\uFF0C\u5E94\u503E\u5411\u8FD4\u56DE ALERT \u6216 WARNING\u3002"
                            TargetTrigger.OnDisappear -> "\u5982\u679C\u76EE\u6807\u672A\u51FA\u73B0\u3001\u79BB\u5F00\u6216\u65E0\u6CD5\u7EF4\u6301\u5E94\u6709\u72B6\u6001\uFF0C\u5E94\u503E\u5411\u8FD4\u56DE ALERT \u6216 WARNING\u3002"
                        }
                    )
                    append("\u53EA\u8FD4\u56DE JSON\uFF0C\u5B57\u6BB5\u4E3A status\u3001summary\u3001reason\u3001confidence\u3001remark\u3002")
                    append(" status \u53EA\u80FD\u662F ALERT\u3001WARNING\u3001NORMAL\u3001UNKNOWN\u3002")
                    append(" remark \u662F\u7ED9\u7528\u6237\u7684\u4E00\u53E5\u7B80\u77ED\u4E2D\u4E8C\u75C5\u8D5B\u535A\u8BD7\u4EBA\u5F0F\u65C1\u767D\uFF0C\u4E0D\u80FD\u4E0E\u5224\u65AD\u51B2\u7A81\uFF0C\u4E0D\u80FD\u7F16\u9020\u753B\u9762\u4E8B\u5B9E\u3002")
                    append(" \u53C2\u8003\u76EE\u6807\uFF1A")
                    append(sceneDescription)
                }
            }
        }
    }
}
