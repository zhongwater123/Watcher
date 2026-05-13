package com.example.watcher.agentframework.autonomy

import com.example.watcher.agentframework.core.AgentDefinition
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutonomousAgentRuntime(
    private val definition: AgentDefinition,
    private val config: AutonomousAgentConfig,
    private val modules: AutonomousAgentModules,
    parentScope: CoroutineScope,
    sessionId: String = UUID.randomUUID().toString()
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()
    private val completion = CompletableDeferred<AutonomousAgentSnapshot>()
    private val _events = MutableSharedFlow<AutonomousAgentEvent>(
        replay = 32,
        extraBufferCapacity = 128
    )
    private val _snapshot = MutableStateFlow(
        AutonomousAgentSnapshot(
            sessionId = sessionId,
            definition = definition
        )
    )

    private var job: Job? = null
    @Volatile
    private var stopRequested = false

    val snapshot: StateFlow<AutonomousAgentSnapshot> = _snapshot.asStateFlow()
    val events: SharedFlow<AutonomousAgentEvent> = _events.asSharedFlow()

    suspend fun initialize() {
        if (snapshot.value.lifecycleState != AutonomousLifecycleState.Created) return
        setLifecycle(AutonomousLifecycleState.Initialized)
    }

    suspend fun start() {
        mutex.withLock {
            if (job?.isActive == true) return
            if (snapshot.value.lifecycleState.isTerminal) return
            stopRequested = false
            job = scope.launch(Dispatchers.Default) {
                runLoop()
            }
        }
    }

    suspend fun submitSignal(signal: AgentSignal) {
        modules.communicationHub.submit(snapshot.value.sessionId, signal)
    }

    fun suspendRuntime() {
        scope.launch {
            if (snapshot.value.lifecycleState.isTerminal) return@launch
            setLifecycle(AutonomousLifecycleState.Suspended)
        }
    }

    fun resumeRuntime() {
        scope.launch {
            if (snapshot.value.lifecycleState != AutonomousLifecycleState.Suspended) return@launch
            setLifecycle(AutonomousLifecycleState.Running)
        }
    }

    fun stop() {
        stopRequested = true
    }

    suspend fun awaitCompletion(): AutonomousAgentSnapshot {
        job?.join()
        return if (completion.isCompleted) completion.await() else snapshot.value
    }

    suspend fun destroy() {
        val currentJob = mutex.withLock {
            stopRequested = true
            job
        }
        currentJob?.cancel()
        currentJob?.join()
        val sessionId = snapshot.value.sessionId
        modules.memoryManager.clear(sessionId)
        modules.communicationHub.clear(sessionId)
        setLifecycle(
            state = AutonomousLifecycleState.Destroyed,
            stopReason = AutonomousStopReason.Cancelled
        )
        completeIfNeeded(snapshot.value)
    }

    private suspend fun runLoop() {
        if (snapshot.value.lifecycleState == AutonomousLifecycleState.Created) {
            initialize()
        }
        setLifecycle(AutonomousLifecycleState.Running)
        val startedAt = System.currentTimeMillis()

        try {
            while (!stopRequested) {
                val current = snapshot.value
                if (current.lifecycleState == AutonomousLifecycleState.Suspended) {
                    delay(config.loopDelayMillis)
                    continue
                }
                if (current.cycle >= config.maxCycles) {
                    finish(
                        state = AutonomousLifecycleState.Stopped,
                        stopReason = AutonomousStopReason.StepLimitReached
                    )
                    return
                }
                if (System.currentTimeMillis() - startedAt >= config.maxRuntimeMillis) {
                    finish(
                        state = AutonomousLifecycleState.Stopped,
                        stopReason = AutonomousStopReason.RuntimeLimitReached
                    )
                    return
                }

                val cycle = current.cycle + 1
                val cycleStartedAt = System.currentTimeMillis()
                try {
                    val adapterSignals = modules.signalAdapters.flatMap { it.collect(current) }
                    val inboundSignals = modules.communicationHub.drain(current.sessionId)
                    val allSignals = inboundSignals + adapterSignals
                    val perception = modules.perceptionPipeline.process(current, allSignals)
                    modules.memoryManager.onPerception(current.sessionId, perception)
                    val memory = modules.memoryManager.snapshot(current.sessionId)
                    val goal = modules.goalParser.resolve(definition, perception, memory)
                    val plan = modules.taskPlanner.plan(definition, goal, perception, memory)
                    val reasoning = modules.reasoningEngine.reason(
                        definition = definition,
                        snapshot = current,
                        perception = perception,
                        memory = memory,
                        goal = goal,
                        plan = plan
                    )
                    val decision = modules.decisionSelector.select(definition, reasoning, memory)
                    modules.memoryManager.onDecision(current.sessionId, decision)
                    val guardedDecision = modules.ruleConstraintEngine.apply(definition, decision, plan, current)
                    val firstResult = executeAndValidate(current, goal, guardedDecision)
                    val correctionResult = correctIfNeeded(
                        cycle = cycle,
                        runtimeSnapshot = current,
                        goal = goal,
                        guardedDecision = guardedDecision,
                        outcome = firstResult.outcome,
                        validation = firstResult.validation
                    )
                    val outcome = correctionResult.outcome
                    val validation = correctionResult.validation
                    val corrections = correctionResult.corrections
                    val record = AutonomousCycleRecord(
                        cycle = cycle,
                        perception = perception,
                        goal = goal,
                        plan = plan,
                        reasoning = reasoning,
                        guardedDecision = guardedDecision,
                        outcome = outcome,
                        validation = validation,
                        corrections = corrections,
                        startedAt = cycleStartedAt
                    )
                    val metrics = modules.evaluationEngine.evaluate(record)
                    modules.learningEngine.learn(current.sessionId, record, metrics)
                    val outputs = modules.communicationHub.outputs(current.sessionId)
                    mutateSnapshot { state ->
                        val updatedRecords = (state.records + record).let { allRecords ->
                            val limit = config.maxRecords ?: config.maxCycles
                            if (allRecords.size > limit) allRecords.takeLast(limit) else allRecords
                        }
                        state.copy(
                            cycle = cycle,
                            idleCount = if (perception.cleanedSignals.isEmpty() && outcome.outputs.isEmpty()) {
                                state.idleCount + 1
                            } else {
                                0
                            },
                            lastPerception = perception,
                            lastGoal = goal,
                            lastPlan = plan,
                            lastReasoning = reasoning,
                            lastDecision = decision,
                            lastOutcome = outcome,
                            lastValidation = validation,
                            outputs = outputs,
                            records = updatedRecords,
                            correctionRecords = appendCorrections(
                                state.correctionRecords,
                                corrections
                            ),
                            failureCount = if (corrections.isNotEmpty() &&
                                validation.status == ValidationStatus.Completed
                            ) {
                                0
                            } else {
                                state.failureCount
                            },
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    _events.emit(
                        AutonomousAgentEvent.CycleCompleted(
                            sessionId = current.sessionId,
                            cycle = cycle,
                            validationStatus = validation.status
                        )
                    )

                    val afterCycle = snapshot.value
                    if (afterCycle.idleCount >= config.maxIdleCycles) {
                        finish(
                            state = AutonomousLifecycleState.Stopped,
                            stopReason = AutonomousStopReason.IdleLimitReached
                        )
                        return
                    }
                    if (validation.status == ValidationStatus.Completed) {
                        finish(
                            state = AutonomousLifecycleState.Stopped,
                            stopReason = AutonomousStopReason.GoalAchieved
                        )
                        return
                    }
                    if (!validation.shouldContinue && !validation.shouldRetry) {
                        finish(
                            state = if (validation.status == ValidationStatus.Failed) {
                                AutonomousLifecycleState.Failed
                            } else {
                                AutonomousLifecycleState.Stopped
                            },
                            stopReason = if (validation.status == ValidationStatus.Failed) {
                                AutonomousStopReason.Error
                            } else {
                                AutonomousStopReason.StoppedByRequest
                            }
                        )
                        return
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    recordCycleExceptionCorrection(cycle, error)
                    val updated = mutateSnapshotAndGet { state ->
                        state.copy(
                            failureCount = state.failureCount + 1,
                            errorMessage = error.message ?: "Autonomous cycle failed",
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    _events.emit(
                        AutonomousAgentEvent.FailureRecorded(
                            sessionId = updated.sessionId,
                            cycle = cycle,
                            message = updated.errorMessage.orEmpty()
                        )
                    )
                    if (updated.failureCount >= config.maxFailures) {
                        finish(
                            state = AutonomousLifecycleState.Failed,
                            stopReason = AutonomousStopReason.Error
                        )
                        return
                    }
                }

                delay(config.loopDelayMillis)
            }
            finish(
                state = AutonomousLifecycleState.Stopped,
                stopReason = AutonomousStopReason.StoppedByRequest
            )
        } catch (_: CancellationException) {
            finish(
                state = AutonomousLifecycleState.Destroyed,
                stopReason = AutonomousStopReason.Cancelled
            )
        }
    }

    private data class ExecutionValidation(
        val outcome: ExecutionOutcome,
        val validation: ValidationOutcome
    )

    private data class CorrectionLoopResult(
        val outcome: ExecutionOutcome,
        val validation: ValidationOutcome,
        val corrections: List<CorrectionRecord>
    )

    private suspend fun executeAndValidate(
        runtimeSnapshot: AutonomousAgentSnapshot,
        goal: ResolvedGoal,
        guardedDecision: GuardedDecision
    ): ExecutionValidation {
        val outcome = modules.executionCoordinator.execute(definition, runtimeSnapshot, guardedDecision)
        outcome.outputs.forEach { output ->
            modules.communicationHub.publish(runtimeSnapshot.sessionId, output)
            _events.emit(AutonomousAgentEvent.OutputPublished(runtimeSnapshot.sessionId, output))
        }
        val validation = modules.resultValidator.validate(goal, guardedDecision, outcome)
        modules.feedbackProcessor.process(runtimeSnapshot.sessionId, outcome, validation)
        return ExecutionValidation(outcome, validation)
    }

    private suspend fun correctIfNeeded(
        cycle: Int,
        runtimeSnapshot: AutonomousAgentSnapshot,
        goal: ResolvedGoal,
        guardedDecision: GuardedDecision,
        outcome: ExecutionOutcome,
        validation: ValidationOutcome
    ): CorrectionLoopResult {
        if (!config.enableReflectionCorrection) {
            return CorrectionLoopResult(outcome, validation, emptyList())
        }
        val corrections = mutableListOf<CorrectionRecord>()
        var currentOutcome = outcome
        var currentValidation = validation

        while (true) {
            val trigger = correctionTrigger(guardedDecision, currentOutcome, currentValidation)
                ?: break
            val attempt = corrections.size + 1
            val runtimeAttempts = snapshot.value.correctionRecords.size + corrections.size
            val diagnosis = modules.reflectionEngine.reflect(
                cycle = cycle,
                attempt = attempt,
                trigger = trigger,
                decision = guardedDecision,
                outcome = currentOutcome,
                validation = currentValidation,
                error = null
            )
            val correctionDecision = modules.correctionPolicy.decide(
                diagnosis = diagnosis,
                attemptInCycle = attempt,
                runtimeAttempts = runtimeAttempts,
                previousCorrections = snapshot.value.correctionRecords + corrections,
                config = config
            )
            val record = CorrectionRecord(
                cycle = cycle,
                attempt = attempt,
                trigger = trigger,
                action = correctionDecision.action,
                reason = correctionDecision.reason,
                failureSignature = diagnosis.failureSignature,
                validationStatus = currentValidation.status,
                error = currentOutcome.error
            )
            corrections += record
            modules.memoryManager.onCorrection(runtimeSnapshot.sessionId, record)

            if (correctionDecision.action == CorrectionAction.AbortFailure) {
                currentValidation = ValidationOutcome(
                    status = ValidationStatus.Failed,
                    shouldContinue = false,
                    shouldRetry = false,
                    feedback = correctionDecision.reason
                )
                break
            }
            if (correctionDecision.action != CorrectionAction.RetryOriginalDecision) {
                break
            }
            if (attempt >= config.maxCorrectionAttemptsPerCycle ||
                runtimeAttempts + 1 >= config.maxCorrectionAttemptsPerRuntime
            ) {
                break
            }
            val retried = executeAndValidate(runtimeSnapshot, goal, guardedDecision)
            currentOutcome = retried.outcome
            currentValidation = retried.validation
            if (currentValidation.status == ValidationStatus.Completed) {
                break
            }
        }

        return CorrectionLoopResult(currentOutcome, currentValidation, corrections)
    }

    private fun correctionTrigger(
        guardedDecision: GuardedDecision,
        outcome: ExecutionOutcome,
        validation: ValidationOutcome
    ): CorrectionTrigger? {
        return when {
            !guardedDecision.allowed -> CorrectionTrigger.RuleBlocked
            outcome.toolResults.any { !it.success } -> CorrectionTrigger.ToolFailure
            validation.status == ValidationStatus.Partial -> CorrectionTrigger.ValidationPartial
            validation.status == ValidationStatus.Failed -> CorrectionTrigger.ValidationFailed
            else -> null
        }
    }

    private suspend fun recordCycleExceptionCorrection(cycle: Int, error: Throwable) {
        if (!config.enableReflectionCorrection) return
        val diagnosis = modules.reflectionEngine.reflect(
            cycle = cycle,
            attempt = 1,
            trigger = CorrectionTrigger.CycleException,
            decision = null,
            outcome = null,
            validation = null,
            error = error
        )
        val correctionDecision = modules.correctionPolicy.decide(
            diagnosis = diagnosis,
            attemptInCycle = 1,
            runtimeAttempts = snapshot.value.correctionRecords.size,
            previousCorrections = snapshot.value.correctionRecords,
            config = config
        )
        val record = CorrectionRecord(
            cycle = cycle,
            attempt = 1,
            trigger = CorrectionTrigger.CycleException,
            action = correctionDecision.action,
            reason = correctionDecision.reason,
            failureSignature = diagnosis.failureSignature,
            error = error.message
        )
        modules.memoryManager.onCorrection(snapshot.value.sessionId, record)
        mutateSnapshot { state ->
            state.copy(
                correctionRecords = appendCorrections(state.correctionRecords, listOf(record))
            )
        }
    }

    private fun appendCorrections(
        current: List<CorrectionRecord>,
        additions: List<CorrectionRecord>
    ): List<CorrectionRecord> {
        if (additions.isEmpty()) return current
        val limit = config.maxCorrectionRecords.coerceAtLeast(1)
        return (current + additions).takeLast(limit)
    }

    private suspend fun setLifecycle(
        state: AutonomousLifecycleState,
        stopReason: AutonomousStopReason? = null
    ) {
        mutateSnapshot { current ->
            current.copy(
                lifecycleState = state,
                stopReason = stopReason,
                updatedAt = System.currentTimeMillis()
            )
        }
        _events.emit(
            AutonomousAgentEvent.LifecycleChanged(
                sessionId = snapshot.value.sessionId,
                state = state,
                stopReason = stopReason
            )
        )
    }

    private suspend fun finish(
        state: AutonomousLifecycleState,
        stopReason: AutonomousStopReason
    ) {
        setLifecycle(state, stopReason)
        completeIfNeeded(snapshot.value)
    }

    private suspend fun completeIfNeeded(finalSnapshot: AutonomousAgentSnapshot) {
        if (!completion.isCompleted) {
            completion.complete(finalSnapshot)
        }
    }

    private suspend fun mutateSnapshot(transform: (AutonomousAgentSnapshot) -> AutonomousAgentSnapshot) {
        mutex.withLock {
            _snapshot.value = transform(_snapshot.value)
        }
    }

    private suspend fun mutateSnapshotAndGet(
        transform: (AutonomousAgentSnapshot) -> AutonomousAgentSnapshot
    ): AutonomousAgentSnapshot {
        return mutex.withLock {
            transform(_snapshot.value).also { _snapshot.value = it }
        }
    }
}
