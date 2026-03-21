package com.example.agenthost.automation

import com.example.agenthost.core.AgentHostLog
import java.util.concurrent.ConcurrentHashMap

data class ControlActionResolution(
    val phaseBefore: SubgoalPhase,
    val proposedAction: CanonicalAction,
    val finalAction: GuiAction,
    val finalCanonicalAction: CanonicalAction,
    val recoveryDecision: RecoveryPolicyDecision
)

data class ControlPostActionResolution(
    val phaseBefore: SubgoalPhase,
    val phaseAfter: SubgoalPhase,
    val goalStatus: GoalSatisfactionStatus,
    val resultSummary: StepResultSummary,
    val progressEvidence: ProgressEvidence,
    val requestSessionComplete: Boolean,
    val completionReason: String?,
    val requestUserHandoff: Boolean,
    val handoffQuestion: String?,
    val completionConfirmationPending: Boolean
) {
    val observationChanged: Boolean?
        get() = progressEvidence.observationChanged
}

class AgentControlCoordinator(
    private val subgoalStateMachine: SubgoalStateMachine = SubgoalStateMachine(),
    private val recoveryPolicy: RecoveryPolicy = DefaultRecoveryPolicy(),
    private val goalSatisfactionEvaluator: GoalSatisfactionEvaluator = DefaultGoalSatisfactionEvaluator(),
    private val config: GuardrailConfig = GuardrailConfig()
) {
    companion object {
        private const val TAG = "AgentControlCoordinator"
    }

    private val states = ConcurrentHashMap<String, GuardrailState>()

    fun clearSession(sessionId: String) {
        states.remove(sessionId)
    }

    fun buildPlanningSnapshot(
        sessionId: String,
        command: String,
        capabilityProfile: CapabilityProfile
    ): AgentControlSnapshot {
        val state = stateFor(sessionId, command)
        val recentActions = state.recentSteps
            .toList()
            .takeLast(4)
            .map {
                AgentControlRecentAction(
                    step = it.step,
                    actionType = it.finalActionType,
                    resultCategory = it.resultCategory,
                    summary = it.summary,
                    approxPoint = it.approxPoint,
                    phaseAfter = it.phaseAfter
                )
            }
        val constraints = recoveryPolicy.refineActionConstraints(
            state = state,
            capabilityProfile = capabilityProfile,
            baseAllowedActions = subgoalStateMachine.allowedActions(
                phase = state.subgoalState.phase,
                capabilityProfile = capabilityProfile
            )
        )
        return AgentControlSnapshot(
            globalTask = command,
            currentPhase = state.subgoalState.phase.name,
            parentHint = state.subgoalState.spec?.parentHint,
            targetHint = state.subgoalState.spec?.targetHint,
            allowedActions = constraints.allowedActions,
            blockedActions = constraints.blockedActions,
            recentActions = recentActions,
            lastResult = state.lastResult?.categoryName(),
            completionState = state.lastGoalStatus.name,
            doneOnly = state.subgoalState.phase == SubgoalPhase.DONE_ONLY
        )
    }

    fun resolveAction(
        sessionId: String,
        step: Int,
        command: String,
        beforeObservation: ObservationResult,
        action: CanonicalAction,
        surfaceContext: SurfaceContext = SurfaceContext()
    ): ControlActionResolution {
        val state = stateFor(sessionId, command)
        val decision = recoveryPolicy.decide(
            RecoveryPolicyInput(
                step = step,
                state = state,
                beforeObservation = beforeObservation,
                proposedAction = action,
                goalStatus = state.lastGoalStatus,
                surfaceContext = surfaceContext
            )
        )
        logRecovery(sessionId, step, action, decision)
        return ControlActionResolution(
            phaseBefore = state.subgoalState.phase,
            proposedAction = action,
            finalAction = decision.finalAction,
            finalCanonicalAction = decision.finalCanonicalAction,
            recoveryDecision = decision
        )
    }

    fun finalizeStep(
        sessionId: String,
        step: Int,
        command: String,
        beforeObservation: ObservationResult,
        afterObservation: ObservationResult?,
        resolution: ControlActionResolution,
        executionResult: ActionExecutionResult?,
        controlSyntheticCode: String?,
        controlSyntheticDetails: String?,
        localChangeConfirmedPoint: CanonicalPoint? = null,
        surfaceContext: SurfaceContext = SurfaceContext()
    ): ControlPostActionResolution {
        val state = stateFor(sessionId, command)
        val observationChanged = detectObservationChange(
            beforeObservation = beforeObservation,
            afterObservation = afterObservation,
            localChangeConfirmedPoint = localChangeConfirmedPoint
        )
        val candidatePhase = subgoalStateMachine.advancePhase(
            state = state.subgoalState,
            action = resolution.finalCanonicalAction,
            observationChanged = observationChanged
        )
        var evidence = buildProgressEvidence(
            command = command,
            state = state,
            beforeObservation = beforeObservation,
            afterObservation = afterObservation,
            surfaceContext = surfaceContext,
            resolution = resolution,
            executionResult = executionResult,
            controlSyntheticCode = controlSyntheticCode,
            controlSyntheticDetails = controlSyntheticDetails,
            localChangeConfirmedPoint = localChangeConfirmedPoint,
            observationChanged = observationChanged,
            candidatePhase = candidatePhase
        )
        logEvidence(sessionId, step, evidence)

        val goalDecision = goalSatisfactionEvaluator.evaluate(
            GoalSatisfactionInput(
                task = command,
                beforeObservation = beforeObservation,
                afterObservation = afterObservation,
                progressEvidence = evidence,
                recentSteps = state.recentSteps.toList(),
                proposedAction = resolution.proposedAction,
                finalAction = resolution.finalCanonicalAction,
                guardrailState = state,
                subgoalState = candidatePhase,
                previousSubgoalState = state.subgoalState
            )
        )
        evidence = evidence.copy(needsUserConfirmation = goalDecision.needsUserConfirmation)
        logGoal(sessionId, step, goalDecision)

        val nextPhase = subgoalStateMachine.finalizePhase(
            candidateState = candidatePhase,
            goalStatus = goalDecision.status
        )
        val resultSummary = buildResultSummary(
            resolution = resolution,
            executionResult = executionResult,
            controlSyntheticCode = controlSyntheticCode,
            controlSyntheticDetails = controlSyntheticDetails,
            progressEvidence = evidence,
            goalStatus = goalDecision.status
        )
        val updated = updateState(
            previous = state,
            step = step,
            resolution = resolution,
            resultSummary = resultSummary,
            nextPhase = nextPhase,
            afterObservation = afterObservation,
            progressEvidence = evidence,
            goalDecision = goalDecision,
            surfaceContext = surfaceContext,
            localChangeConfirmedPoint = localChangeConfirmedPoint
        )
        states[sessionId] = updated

        val requestSessionComplete = goalDecision.status == GoalSatisfactionStatus.SATISFIED
        val requestUserHandoff = goalDecision.status == GoalSatisfactionStatus.NEED_USER
        return ControlPostActionResolution(
            phaseBefore = state.subgoalState.phase,
            phaseAfter = nextPhase.phase,
            goalStatus = goalDecision.status,
            resultSummary = resultSummary,
            progressEvidence = evidence,
            requestSessionComplete = requestSessionComplete,
            completionReason = if (requestSessionComplete) {
                goalDecision.completionReason ?: resultSummary.message
            } else {
                null
            },
            requestUserHandoff = requestUserHandoff,
            handoffQuestion = if (requestUserHandoff) {
                goalDecision.userQuestion ?: "当前需要你接手确认下一步。"
            } else {
                null
            },
            completionConfirmationPending = goalDecision.needsUserConfirmation
        )
    }

    private fun buildProgressEvidence(
        command: String,
        state: GuardrailState,
        beforeObservation: ObservationResult,
        afterObservation: ObservationResult?,
        surfaceContext: SurfaceContext,
        resolution: ControlActionResolution,
        executionResult: ActionExecutionResult?,
        controlSyntheticCode: String?,
        controlSyntheticDetails: String?,
        localChangeConfirmedPoint: CanonicalPoint?,
        observationChanged: Boolean?,
        candidatePhase: SubgoalState
    ): ProgressEvidence {
        val surfaceChanged = surfaceContext != state.lastSurfaceContext
        val observationDelta = ObservationDeltaEvidence(
            globalChanged = observationChanged,
            localRoiChanged = localChangeConfirmedPoint != null,
            surfaceChanged = surfaceChanged
        )
        val phaseProgressed = phaseIndex(candidatePhase.phase) > phaseIndex(state.subgoalState.phase)
        val phaseRegressed = phaseIndex(candidatePhase.phase) < phaseIndex(state.subgoalState.phase)
        val recentActionResult = classifyRecentActionResult(
            resolution = resolution,
            executionResult = executionResult,
            observationChanged = observationChanged,
            localChangeConfirmedPoint = localChangeConfirmedPoint
        )
        val recentNoProgress = when {
            recentActionResult == RecentActionResult.NO_CHANGE -> true
            recentActionResult == RecentActionResult.BLOCKED -> true
            recentActionResult == RecentActionResult.WAIT -> true
            recentActionResult == RecentActionResult.ASK_USER -> true
            else -> false
        }
        val progress = observationChanged == true ||
            localChangeConfirmedPoint != null ||
            phaseProgressed
        val consecutiveNoChangeCount = if (observationChanged == false) {
            state.consecutiveNoChangeCount + 1
        } else {
            0
        }
        val lowYieldIncrement = recentNoProgress ||
            recentActionResult == RecentActionResult.BLOCKED ||
            recentActionResult == RecentActionResult.REJECTED
        val consecutiveLowYieldCount = if (progress) {
            0
        } else if (lowYieldIncrement) {
            state.consecutiveLowYieldCount + 1
        } else {
            state.consecutiveLowYieldCount
        }
        val goalRelation = inferGoalRelation(
            command = command,
            state = state,
            candidatePhase = candidatePhase,
            observationDelta = observationDelta,
            resolution = resolution,
            consecutiveLowYieldCount = consecutiveLowYieldCount,
            phaseProgressed = phaseProgressed,
            phaseRegressed = phaseRegressed,
            surfaceContext = surfaceContext
        )
        return ProgressEvidence(
            observationDelta = observationDelta,
            surfaceContext = surfaceContext,
            recentActionResult = recentActionResult,
            recentActionCode = controlSyntheticCode ?: executionResult?.code ?: resolution.finalCanonicalAction.type.name,
            recentActionMessage = controlSyntheticDetails
                ?: executionResult?.details
                ?: resolution.recoveryDecision.message
                ?: resolution.finalCanonicalAction.reason
                ?: resolution.finalCanonicalAction.question
                ?: resolution.finalCanonicalAction.type.name.lowercase(),
            consecutiveNoChangeCount = consecutiveNoChangeCount,
            consecutiveLowYieldCount = consecutiveLowYieldCount,
            recentLowYield = consecutiveLowYieldCount >= config.noChangeThreshold,
            recentNoProgress = recentNoProgress,
            recentBackIntent = resolution.proposedAction.type == CanonicalActionType.BACK ||
                resolution.finalCanonicalAction.type == CanonicalActionType.BACK,
            clickSuppressed = resolution.recoveryDecision.rule == "TOGGLE_REPEAT_CLICK_SUPPRESSED" ||
                resolution.recoveryDecision.rule == "REPEAT_CLICK_NEARBY_BLOCK",
            repeatTargetPoint = resolution.finalCanonicalAction.point ?: resolution.proposedAction.point,
            phaseProgressed = phaseProgressed,
            phaseRegressed = phaseRegressed,
            goalRelation = goalRelation,
            modelRequestedDone = resolution.proposedAction.type == CanonicalActionType.DONE
        )
    }

    private fun inferGoalRelation(
        command: String,
        state: GuardrailState,
        candidatePhase: SubgoalState,
        observationDelta: ObservationDeltaEvidence,
        resolution: ControlActionResolution,
        consecutiveLowYieldCount: Int,
        phaseProgressed: Boolean,
        phaseRegressed: Boolean,
        surfaceContext: SurfaceContext
    ): GoalRelation {
        val finalAction = resolution.finalCanonicalAction
        val currentSurfaceMatchesTask = surfaceMatchesTask(command, surfaceContext)
        val previousSurfaceMatchesTask = surfaceMatchesTask(command, state.lastSurfaceContext)
        if (candidatePhase.phase == SubgoalPhase.DONE_ONLY) {
            return GoalRelation.AT_TARGET
        }
        if (candidatePhase.phase == SubgoalPhase.TARGET_CLICKED_PENDING_SETTLE &&
            finalAction.type == CanonicalActionType.CLICK &&
            observationDelta.effectiveChanged == true
        ) {
            return GoalRelation.AT_TARGET
        }
        if (observationDelta.localRoiChanged &&
            finalAction.type == CanonicalActionType.CLICK &&
            candidatePhase.phase == state.subgoalState.phase
        ) {
            return GoalRelation.AT_TARGET
        }
        if (currentSurfaceMatchesTask &&
            (observationDelta.surfaceChanged || phaseProgressed || observationDelta.effectiveChanged == true)
        ) {
            return GoalRelation.AT_TARGET
        }
        if (phaseProgressed || (currentSurfaceMatchesTask && observationDelta.surfaceChanged)) {
            return GoalRelation.CLOSER
        }
        if ((phaseRegressed || (previousSurfaceMatchesTask && !currentSurfaceMatchesTask && observationDelta.surfaceChanged) ||
                resolution.recoveryDecision.rule == "REPEAT_CLICK_NEARBY_BLOCK") &&
            consecutiveLowYieldCount >= config.noChangeThreshold
        ) {
            return GoalRelation.OFF_TRACK
        }
        return GoalRelation.UNKNOWN
    }

    private fun buildResultSummary(
        resolution: ControlActionResolution,
        executionResult: ActionExecutionResult?,
        controlSyntheticCode: String?,
        controlSyntheticDetails: String?,
        progressEvidence: ProgressEvidence,
        goalStatus: GoalSatisfactionStatus
    ): StepResultSummary {
        if (goalStatus == GoalSatisfactionStatus.SATISFIED) {
            return StepResultSummary(
                category = StepResultCategory.COMPLETION_SATISFIED,
                code = controlSyntheticCode ?: executionResult?.code ?: "COMPLETION_SATISFIED",
                message = controlSyntheticDetails ?: executionResult?.details ?: "goal satisfied",
                observationChanged = progressEvidence.observationChanged
            )
        }
        executionResult?.let {
            if (!it.ok) {
                return StepResultSummary(
                    category = StepResultCategory.PLATFORM_FAILED,
                    code = it.code,
                    message = it.details,
                    observationChanged = progressEvidence.observationChanged
                )
            }
        }
        if (resolution.finalCanonicalAction.type == CanonicalActionType.ASK_USER ||
            goalStatus == GoalSatisfactionStatus.NEED_USER
        ) {
            return StepResultSummary(
                category = StepResultCategory.LOW_YIELD_HANDOFF,
                code = controlSyntheticCode ?: "ASK_USER",
                message = controlSyntheticDetails
                    ?: resolution.finalCanonicalAction.question
                    ?: "ask user",
                observationChanged = progressEvidence.observationChanged,
                lowYield = true,
                guardrailRule = resolution.recoveryDecision.rule
            )
        }
        if (resolution.finalCanonicalAction.type == CanonicalActionType.WAIT) {
            return StepResultSummary(
                category = StepResultCategory.WAIT_ELAPSED,
                code = controlSyntheticCode ?: "WAIT_ELAPSED",
                message = controlSyntheticDetails ?: "wait elapsed",
                observationChanged = progressEvidence.observationChanged,
                lowYield = progressEvidence.recentLowYield,
                guardrailRule = resolution.recoveryDecision.rule
            )
        }
        if (resolution.finalCanonicalAction.type == CanonicalActionType.DONE) {
            return StepResultSummary(
                category = StepResultCategory.SYNTHETIC_DONE,
                code = controlSyntheticCode ?: "DONE",
                message = controlSyntheticDetails
                    ?: resolution.finalCanonicalAction.reason
                    ?: "done",
                observationChanged = progressEvidence.observationChanged,
                guardrailRule = resolution.recoveryDecision.rule
            )
        }
        if (resolution.recoveryDecision.wasOverridden()) {
            return StepResultSummary(
                category = StepResultCategory.GUARDRAIL_BLOCKED,
                code = controlSyntheticCode ?: resolution.recoveryDecision.rule ?: "RECOVERY_OVERRIDE",
                message = controlSyntheticDetails ?: resolution.recoveryDecision.message.orEmpty(),
                observationChanged = progressEvidence.observationChanged,
                lowYield = progressEvidence.recentLowYield,
                guardrailRule = resolution.recoveryDecision.rule
            )
        }
        if (progressEvidence.observationChanged == false) {
            return StepResultSummary(
                category = if (progressEvidence.recentLowYield) {
                    StepResultCategory.LOW_YIELD_HANDOFF
                } else {
                    StepResultCategory.SUCCESS_NO_CHANGE
                },
                code = controlSyntheticCode ?: executionResult?.code ?: "NO_CHANGE",
                message = controlSyntheticDetails ?: executionResult?.details ?: "no change",
                observationChanged = false,
                lowYield = progressEvidence.recentLowYield
            )
        }
        return StepResultSummary(
            category = StepResultCategory.SUCCESS_CHANGED,
            code = controlSyntheticCode ?: executionResult?.code ?: "SUCCESS_CHANGED",
            message = controlSyntheticDetails ?: executionResult?.details ?: "changed",
            observationChanged = progressEvidence.observationChanged
        )
    }

    private fun updateState(
        previous: GuardrailState,
        step: Int,
        resolution: ControlActionResolution,
        resultSummary: StepResultSummary,
        nextPhase: SubgoalState,
        afterObservation: ObservationResult?,
        progressEvidence: ProgressEvidence,
        goalDecision: GoalSatisfactionDecision,
        surfaceContext: SurfaceContext,
        localChangeConfirmedPoint: CanonicalPoint?
    ): GuardrailState {
        val finalAction = resolution.finalCanonicalAction
        val progress = progressEvidence.strongProgressSignal ||
            goalDecision.status == GoalSatisfactionStatus.SATISFIED
        val nextFingerprint = afterObservation?.let { ObservationChangeDetector.fingerprint(it) }
            ?: previous.lastObservationFingerprint
        val nextClickRepeat = when {
            finalAction.type != CanonicalActionType.CLICK -> 0
            finalAction.point == null -> 0
            previous.lastClickPoint == null -> 1
            !finalAction.point.isNear(
                previous.lastClickPoint,
                config.nearbyClickRadiusPx(afterObservation ?: ObservationResult.Failed("OBS", "n/a"))
            ) -> 1

            progressEvidence.observationChanged == true -> 1
            else -> previous.nearbyRepeatClickCount + 1
        }
        val nextBackHomeBlocks = if (resolution.recoveryDecision.rule?.contains("BACK") == true ||
            resolution.recoveryDecision.rule?.contains("HOME") == true
        ) {
            previous.consecutiveBackHomeBlocks + 1
        } else if (progress) {
            0
        } else {
            previous.consecutiveBackHomeBlocks
        }
        val nextPoint = if (finalAction.type == CanonicalActionType.CLICK) {
            finalAction.point
        } else {
            previous.lastClickPoint
        }
        val inferredToggleLikeClickPoint = when {
            localChangeConfirmedPoint != null -> localChangeConfirmedPoint
            finalAction.type == CanonicalActionType.CLICK &&
                finalAction.point != null &&
                progressEvidence.observationChanged == true &&
                previous.subgoalState.phase == nextPhase.phase ->
                finalAction.point

            resolution.recoveryDecision.rule == "TOGGLE_REPEAT_CLICK_SUPPRESSED" ->
                previous.lastLocallyConfirmedClickPoint

            else -> null
        }
        val nextLocallyConfirmedClickPoint = when {
            inferredToggleLikeClickPoint != null -> inferredToggleLikeClickPoint
            progress -> null
            else -> previous.lastLocallyConfirmedClickPoint
        }
        val nextLocallyConfirmedClickStep = when {
            inferredToggleLikeClickPoint != null -> step
            progress -> null
            else -> previous.lastLocallyConfirmedClickStep
        }
        val record = ControlStepRecord(
            step = step,
            proposedActionType = resolution.proposedAction.actionType(),
            finalActionType = finalAction.actionType(),
            approxPoint = finalAction.point ?: finalAction.points.firstOrNull(),
            resultCategory = resultSummary.categoryName(),
            summary = resultSummary.message,
            guardrailRule = resolution.recoveryDecision.rule,
            guardrailDecision = resolution.recoveryDecision.type.name,
            pageChanged = progressEvidence.observationChanged,
            phaseBefore = previous.subgoalState.phase.name,
            phaseAfter = nextPhase.phase.name,
            platformCode = resultSummary.code
        )
        return previous.copy(
            subgoalState = nextPhase,
            lastObservationFingerprint = nextFingerprint,
            lastResult = resultSummary,
            lastSurfaceContext = surfaceContext,
            lastProgressStep = if (progress) step else previous.lastProgressStep,
            consecutiveNoChangeCount = progressEvidence.consecutiveNoChangeCount,
            consecutiveLowYieldCount = progressEvidence.consecutiveLowYieldCount,
            consecutiveBackHomeBlocks = nextBackHomeBlocks,
            lastClickPoint = nextPoint,
            nearbyRepeatClickCount = nextClickRepeat,
            lastLocallyConfirmedClickPoint = nextLocallyConfirmedClickPoint,
            lastLocallyConfirmedClickStep = nextLocallyConfirmedClickStep,
            lastGoalStatus = goalDecision.status,
            lastGoalReason = goalDecision.reason
        ).append(record, config.maxRecentSteps)
    }

    private fun classifyRecentActionResult(
        resolution: ControlActionResolution,
        executionResult: ActionExecutionResult?,
        observationChanged: Boolean?,
        localChangeConfirmedPoint: CanonicalPoint?
    ): RecentActionResult {
        if (executionResult?.ok == false) {
            return RecentActionResult.PLATFORM_FAILED
        }
        return when (resolution.finalCanonicalAction.type) {
            CanonicalActionType.ASK_USER -> RecentActionResult.ASK_USER
            CanonicalActionType.DONE -> RecentActionResult.DONE
            CanonicalActionType.WAIT -> RecentActionResult.WAIT
            else -> {
                when {
                    resolution.recoveryDecision.wasOverridden() &&
                        resolution.finalCanonicalAction.type != CanonicalActionType.BACK ->
                        RecentActionResult.BLOCKED

                    localChangeConfirmedPoint != null || observationChanged == true ->
                        RecentActionResult.SUCCESS

                    executionResult?.ok == true ->
                        RecentActionResult.NO_CHANGE

                    else -> RecentActionResult.REJECTED
                }
            }
        }
    }

    private fun stateFor(sessionId: String, command: String): GuardrailState {
        return states.computeIfAbsent(sessionId) {
            GuardrailState(subgoalState = subgoalStateMachine.initialState(command))
        }
    }

    private fun detectObservationChange(
        beforeObservation: ObservationResult,
        afterObservation: ObservationResult?,
        localChangeConfirmedPoint: CanonicalPoint?
    ): Boolean? {
        if (localChangeConfirmedPoint != null) {
            return true
        }
        val before = beforeObservation as? ObservationResult.Captured ?: return null
        val after = afterObservation as? ObservationResult.Captured ?: return null
        return !ObservationChangeDetector.areNearlyUnchanged(before, after)
    }

    private fun phaseIndex(phase: SubgoalPhase): Int {
        return when (phase) {
            SubgoalPhase.GENERAL_NAVIGATION -> 0
            SubgoalPhase.NEED_OPEN_PARENT -> 1
            SubgoalPhase.NEED_CLICK_TARGET -> 2
            SubgoalPhase.TARGET_CLICKED_PENDING_SETTLE -> 3
            SubgoalPhase.DONE_ONLY -> 4
        }
    }

    private fun surfaceMatchesTask(command: String, surfaceContext: SurfaceContext): Boolean {
        val commandTokens = command.lowercase()
            .replace(Regex("[^a-z0-9._/]+"), " ")
            .split(' ', '.', '/', '_')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
        if (commandTokens.isEmpty()) {
            return false
        }
        return surfaceContext.normalizedTokens().any { it in commandTokens }
    }

    private fun logEvidence(sessionId: String, step: Int, evidence: ProgressEvidence) {
        AgentHostLog.i(
            TAG,
            "event=decision_kernel sessionId=$sessionId step=$step code=EVIDENCE_UPDATED " +
                "globalChanged=${evidence.observationDelta.globalChanged} " +
                "localRoiChanged=${evidence.observationDelta.localRoiChanged} " +
                "surfaceChanged=${evidence.observationDelta.surfaceChanged} " +
                "recentActionResult=${evidence.recentActionResult.name} " +
                "goalRelation=${evidence.goalRelation.name} " +
                "lowYield=${evidence.recentLowYield}"
        )
    }

    private fun logGoal(sessionId: String, step: Int, decision: GoalSatisfactionDecision) {
        val code = when (decision.status) {
            GoalSatisfactionStatus.SATISFIED -> "GOAL_SATISFIED"
            GoalSatisfactionStatus.UNSURE -> "GOAL_UNSURE"
            GoalSatisfactionStatus.OFF_TRACK -> "GOAL_OFF_TRACK"
            GoalSatisfactionStatus.NEED_USER -> "GOAL_UNSURE"
        }
        AgentHostLog.i(
            TAG,
            "event=decision_kernel sessionId=$sessionId step=$step code=$code " +
                "status=${decision.status.name} reason=${decision.reason}"
        )
    }

    private fun logRecovery(
        sessionId: String,
        step: Int,
        proposedAction: CanonicalAction,
        decision: RecoveryPolicyDecision
    ) {
        val recoveryCode = when (decision.rule) {
            "RECOVERY_BACK_ALLOWED" -> "RECOVERY_BACK_ALLOWED"
            "BACK_RECENT_PROGRESS_BLOCK" -> "RECOVERY_BACK_BLOCKED"
            "REPEAT_CLICK_NEARBY_BLOCK",
            "TOGGLE_REPEAT_CLICK_SUPPRESSED" -> "RECOVERY_WAIT_CHOSEN"

            else -> null
        }
        recoveryCode?.let { code ->
            AgentHostLog.i(
                TAG,
                "event=decision_kernel sessionId=$sessionId step=$step code=$code " +
                    "proposedAction=${proposedAction.type.name} finalAction=${decision.finalCanonicalAction.type.name} " +
                    "rule=${decision.rule}"
            )
        }
        AgentHostLog.i(
            TAG,
            "event=decision_kernel sessionId=$sessionId step=$step code=CONTROL_DECISION_EMITTED " +
                "proposedAction=${proposedAction.type.name} finalAction=${decision.finalCanonicalAction.type.name} " +
                "directive=${decision.directive} rule=${decision.rule ?: "NONE"}"
        )
    }
}
