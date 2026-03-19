package com.example.agenthost.automation

import java.util.concurrent.ConcurrentHashMap

data class ControlActionResolution(
    val phaseBefore: SubgoalPhase,
    val proposedAction: CanonicalAction,
    val finalAction: GuiAction,
    val finalCanonicalAction: CanonicalAction,
    val guardrailDecision: GuardrailDecision
)

/**
 * Control-layer post-step resolution.
 *
 * The request fields are instructions for the coordinator callback boundary,
 * not canonical session states.
 */
data class ControlPostActionResolution(
    val phaseBefore: SubgoalPhase,
    val phaseAfter: SubgoalPhase,
    val completionVerdict: CompletionVerdict,
    val resultSummary: StepResultSummary,
    val observationChanged: Boolean?,
    val requestSessionComplete: Boolean,
    val completionReason: String?,
    val requestUserHandoff: Boolean,
    val handoffQuestion: String?
)

class AgentControlCoordinator(
    private val subgoalStateMachine: SubgoalStateMachine = SubgoalStateMachine(),
    private val guardrailEvaluator: GuardrailEvaluator = GuardrailEvaluator(),
    private val completionEvaluator: TaskTerminalCondition = DefaultCompletionEvaluator(),
    private val config: GuardrailConfig = GuardrailConfig()
) {
    private val states = ConcurrentHashMap<String, GuardrailState>()

    fun clearSession(sessionId: String) {
        states.remove(sessionId)
    }

    fun buildPlanningSnapshot(
        sessionId: String,
        command: String,
        capabilityProfile: CapabilityProfile
    ): AgentControlSnapshot {
        val state = states.computeIfAbsent(sessionId) {
            GuardrailState(subgoalState = subgoalStateMachine.initialState(command))
        }
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
        return AgentControlSnapshot(
            globalTask = command,
            currentPhase = state.subgoalState.phase.name,
            parentHint = state.subgoalState.spec?.parentHint,
            targetHint = state.subgoalState.spec?.targetHint,
            allowedActions = subgoalStateMachine.allowedActions(state.subgoalState.phase, capabilityProfile),
            blockedActions = subgoalStateMachine.blockedActions(state.subgoalState.phase, capabilityProfile),
            recentActions = recentActions,
            lastResult = state.lastResult?.categoryName(),
            completionState = state.completionVerdict.name,
            doneOnly = state.subgoalState.phase == SubgoalPhase.DONE_ONLY
        )
    }

    fun resolveAction(
        sessionId: String,
        step: Int,
        command: String,
        beforeObservation: ObservationResult,
        action: CanonicalAction
    ): ControlActionResolution {
        val state = states.computeIfAbsent(sessionId) {
            GuardrailState(subgoalState = subgoalStateMachine.initialState(command))
        }
        val decision = guardrailEvaluator.evaluatePreExecution(
            step = step,
            state = state,
            beforeObservation = beforeObservation,
            action = action
        )
        return ControlActionResolution(
            phaseBefore = state.subgoalState.phase,
            proposedAction = action,
            finalAction = decision.finalAction,
            finalCanonicalAction = decision.finalCanonicalAction,
            guardrailDecision = decision
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
        controlSyntheticDetails: String?
    ): ControlPostActionResolution {
        val state = states.computeIfAbsent(sessionId) {
            GuardrailState(subgoalState = subgoalStateMachine.initialState(command))
        }
        val observationChanged = detectObservationChange(beforeObservation, afterObservation)
        val modelRequestedDone = resolution.proposedAction.type == CanonicalActionType.DONE
        val candidatePhase = subgoalStateMachine.advancePhase(
            state = state.subgoalState,
            action = resolution.finalCanonicalAction,
            observationChanged = observationChanged
        )
        val completionVerdict = completionEvaluator.evaluate(
            CompletionInput(
                task = command,
                beforeObservation = beforeObservation,
                afterObservation = afterObservation,
                recentSteps = state.recentSteps.toList(),
                lastAction = resolution.finalCanonicalAction,
                guardrailState = state,
                subgoalState = candidatePhase,
                previousSubgoalState = state.subgoalState,
                modelRequestedDone = modelRequestedDone,
                observationChanged = observationChanged
            )
        )
        val nextPhase = subgoalStateMachine.finalizePhase(
            candidateState = candidatePhase,
            completionVerdict = completionVerdict
        )
        val resultSummary = buildResultSummary(
            resolution = resolution,
            executionResult = executionResult,
            controlSyntheticCode = controlSyntheticCode,
            controlSyntheticDetails = controlSyntheticDetails,
            observationChanged = observationChanged,
            completionVerdict = completionVerdict,
            state = state
        )
        val requiresDoneConfirmation = resolution.finalCanonicalAction.type == CanonicalActionType.DONE &&
            beforeObservation is ObservationResult.Captured &&
            completionVerdict != CompletionVerdict.SATISFIED
        val requestUserHandoff = resultSummary.category == StepResultCategory.LOW_YIELD_HANDOFF
            || requiresDoneConfirmation
        val updated = updateState(
            previous = state,
            step = step,
            resolution = resolution,
            resultSummary = resultSummary,
            observationChanged = observationChanged,
            nextPhase = nextPhase,
            afterObservation = afterObservation,
            guardrailRule = resolution.guardrailDecision.rule
        )
        states[sessionId] = updated
        return ControlPostActionResolution(
            phaseBefore = state.subgoalState.phase,
            phaseAfter = nextPhase.phase,
            completionVerdict = completionVerdict,
            resultSummary = resultSummary,
            observationChanged = observationChanged,
            requestSessionComplete = completionVerdict == CompletionVerdict.SATISFIED ||
                (resolution.finalCanonicalAction.type == CanonicalActionType.DONE && !requiresDoneConfirmation),
            completionReason = when {
                completionVerdict == CompletionVerdict.SATISFIED -> "completion_evaluator_satisfied"
                resolution.finalCanonicalAction.type == CanonicalActionType.DONE && !requiresDoneConfirmation ->
                    resolution.finalCanonicalAction.reason ?: "model_done"
                else -> null
            },
            requestUserHandoff = requestUserHandoff,
            handoffQuestion = if (requestUserHandoff) {
                if (requiresDoneConfirmation) {
                    "模型判断任务可能已完成，但当前界面仍需你确认。"
                } else {
                    "连续多步没有明显推进，当前需要你接手确认下一步。"
                }
            } else {
                null
            }
        )
    }

    private fun buildResultSummary(
        resolution: ControlActionResolution,
        executionResult: ActionExecutionResult?,
        controlSyntheticCode: String?,
        controlSyntheticDetails: String?,
        observationChanged: Boolean?,
        completionVerdict: CompletionVerdict,
        state: GuardrailState
    ): StepResultSummary {
        if (resolution.guardrailDecision.wasBlocked()) {
            return StepResultSummary(
                category = when (resolution.finalCanonicalAction.type) {
                    CanonicalActionType.ASK_USER -> StepResultCategory.LOW_YIELD_HANDOFF
                    CanonicalActionType.DONE -> StepResultCategory.COMPLETION_SATISFIED
                    else -> StepResultCategory.GUARDRAIL_BLOCKED
                },
                code = controlSyntheticCode ?: resolution.guardrailDecision.rule ?: "GUARDRAIL_BLOCKED",
                message = controlSyntheticDetails ?: resolution.guardrailDecision.message.orEmpty(),
                observationChanged = observationChanged,
                lowYield = resolution.guardrailDecision.rule == "REPEAT_CLICK_NEARBY_BLOCK",
                guardrailRule = resolution.guardrailDecision.rule
            )
        }
        if (completionVerdict == CompletionVerdict.SATISFIED) {
            return StepResultSummary(
                category = StepResultCategory.COMPLETION_SATISFIED,
                code = controlSyntheticCode ?: executionResult?.code ?: "COMPLETION_SATISFIED",
                message = controlSyntheticDetails ?: executionResult?.details ?: "completion satisfied",
                observationChanged = observationChanged
            )
        }
        executionResult?.let {
            if (!it.ok) {
                return StepResultSummary(
                    category = StepResultCategory.PLATFORM_FAILED,
                    code = it.code,
                    message = it.details,
                    observationChanged = observationChanged
                )
            }
            return if (observationChanged == false) {
                val lowYield = state.consecutiveNoChangeCount + 1 >= config.noChangeThreshold
                StepResultSummary(
                    category = if (lowYield) StepResultCategory.LOW_YIELD_HANDOFF else StepResultCategory.SUCCESS_NO_CHANGE,
                    code = it.code,
                    message = it.details,
                    observationChanged = false,
                    lowYield = lowYield
                )
            } else {
                StepResultSummary(
                    category = StepResultCategory.SUCCESS_CHANGED,
                    code = it.code,
                    message = it.details,
                    observationChanged = observationChanged
                )
            }
        }
        if (resolution.finalCanonicalAction.type == CanonicalActionType.WAIT) {
            return StepResultSummary(
                category = StepResultCategory.WAIT_ELAPSED,
                code = controlSyntheticCode ?: "WAIT_ELAPSED",
                message = controlSyntheticDetails ?: "wait elapsed",
                observationChanged = observationChanged
            )
        }
        if (resolution.finalCanonicalAction.type == CanonicalActionType.ASK_USER) {
            return StepResultSummary(
                category = StepResultCategory.LOW_YIELD_HANDOFF,
                code = controlSyntheticCode ?: "ASK_USER",
                message = controlSyntheticDetails ?: (resolution.finalCanonicalAction.question ?: "ask user"),
                observationChanged = observationChanged,
                lowYield = true
            )
        }
        if (resolution.finalCanonicalAction.type == CanonicalActionType.DONE) {
            return StepResultSummary(
                category = StepResultCategory.SYNTHETIC_DONE,
                code = controlSyntheticCode ?: "DONE",
                message = controlSyntheticDetails ?: (resolution.finalCanonicalAction.reason ?: "done"),
                observationChanged = observationChanged
            )
        }
        return StepResultSummary(
            category = StepResultCategory.GUARDRAIL_BLOCKED,
            code = controlSyntheticCode ?: "NO_RESULT",
            message = controlSyntheticDetails ?: "no result",
            observationChanged = observationChanged
        )
    }

    private fun updateState(
        previous: GuardrailState,
        step: Int,
        resolution: ControlActionResolution,
        resultSummary: StepResultSummary,
        observationChanged: Boolean?,
        nextPhase: SubgoalState,
        afterObservation: ObservationResult?,
        guardrailRule: String?
    ): GuardrailState {
        val progress = observationChanged == true ||
            resultSummary.category == StepResultCategory.COMPLETION_SATISFIED ||
            previous.subgoalState.phase != nextPhase.phase
        val nextFingerprint = afterObservation?.let { ObservationChangeDetector.fingerprint(it) } ?: previous.lastObservationFingerprint
        val nextNoChange = if (observationChanged == false) {
            previous.consecutiveNoChangeCount + 1
        } else {
            0
        }
        val lowYieldCategory = resultSummary.lowYield ||
            resultSummary.category == StepResultCategory.GUARDRAIL_BLOCKED ||
            resultSummary.category == StepResultCategory.LOW_YIELD_HANDOFF
        val nextLowYield = if (progress) {
            0
        } else if (lowYieldCategory || observationChanged == false) {
            previous.consecutiveLowYieldCount + 1
        } else {
            previous.consecutiveLowYieldCount
        }
        val finalAction = resolution.finalCanonicalAction
        val nextClickRepeat = when {
            finalAction.type != CanonicalActionType.CLICK -> 0
            finalAction.point == null -> 0
            previous.lastClickPoint == null -> 1
            !finalAction.point.isNear(previous.lastClickPoint, config.nearbyClickRadiusPx(afterObservation ?: ObservationResult.Failed("OBS", "n/a"))) -> 1
            observationChanged == true -> 1
            else -> previous.nearbyRepeatClickCount + 1
        }
        val nextBackHomeBlocks = if (guardrailRule?.contains("BACK") == true || guardrailRule?.contains("HOME") == true) {
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
        val record = ControlStepRecord(
            step = step,
            proposedActionType = resolution.proposedAction.actionType(),
            finalActionType = finalAction.actionType(),
            approxPoint = finalAction.point ?: finalAction.points.firstOrNull(),
            resultCategory = resultSummary.categoryName(),
            summary = resultSummary.message,
            guardrailRule = guardrailRule,
            guardrailDecision = if (guardrailRule == null) "ALLOW" else "BLOCKED",
            pageChanged = observationChanged,
            phaseBefore = previous.subgoalState.phase.name,
            phaseAfter = nextPhase.phase.name,
            platformCode = resultSummary.code
        )
        return previous.copy(
            subgoalState = nextPhase,
            lastObservationFingerprint = nextFingerprint,
            lastResult = resultSummary,
            lastProgressStep = if (progress) step else previous.lastProgressStep,
            consecutiveNoChangeCount = nextNoChange,
            consecutiveLowYieldCount = nextLowYield,
            consecutiveBackHomeBlocks = nextBackHomeBlocks,
            lastClickPoint = nextPoint,
            nearbyRepeatClickCount = nextClickRepeat,
            completionVerdict = if (nextPhase.phase == SubgoalPhase.DONE_ONLY) {
                CompletionVerdict.SATISFIED
            } else {
                when {
                    resultSummary.category == StepResultCategory.COMPLETION_SATISFIED -> CompletionVerdict.SATISFIED
                    resultSummary.category == StepResultCategory.SYNTHETIC_DONE -> CompletionVerdict.UNSURE
                    else -> CompletionVerdict.NOT_SATISFIED
                }
            }
        ).append(record, config.maxRecentSteps)
    }

    private fun detectObservationChange(
        beforeObservation: ObservationResult,
        afterObservation: ObservationResult?
    ): Boolean? {
        val before = beforeObservation as? ObservationResult.Captured ?: return null
        val after = afterObservation as? ObservationResult.Captured ?: return null
        return !ObservationChangeDetector.areNearlyUnchanged(before, after)
    }
}
