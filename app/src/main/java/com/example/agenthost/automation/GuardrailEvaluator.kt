package com.example.agenthost.automation

enum class RecoveryDecisionType {
    EXECUTE_PROPOSED,
    OVERRIDE
}

data class RecoveryPolicyDecision(
    val type: RecoveryDecisionType,
    val directive: String,
    val finalAction: GuiAction,
    val finalCanonicalAction: CanonicalAction,
    val rule: String? = null,
    val message: String? = null
) {
    fun wasOverridden(): Boolean = type != RecoveryDecisionType.EXECUTE_PROPOSED
}

data class RecoveryPolicyInput(
    val step: Int,
    val state: GuardrailState,
    val beforeObservation: ObservationResult,
    val proposedAction: CanonicalAction,
    val goalStatus: GoalSatisfactionStatus,
    val surfaceContext: SurfaceContext
)

data class RecoveryActionConstraints(
    val allowedActions: List<String>,
    val blockedActions: List<String>
)

interface RecoveryPolicy {
    fun decide(input: RecoveryPolicyInput): RecoveryPolicyDecision

    fun refineActionConstraints(
        state: GuardrailState,
        capabilityProfile: CapabilityProfile,
        baseAllowedActions: List<String>
    ): RecoveryActionConstraints
}

class DefaultRecoveryPolicy(
    private val config: GuardrailConfig = GuardrailConfig()
) : RecoveryPolicy {
    override fun decide(input: RecoveryPolicyInput): RecoveryPolicyDecision {
        val action = input.proposedAction

        if (input.goalStatus == GoalSatisfactionStatus.SATISFIED &&
            action.type != CanonicalActionType.DONE &&
            action.type != CanonicalActionType.ASK_USER
        ) {
            val done = GuiAction.Done("goal_already_satisfied")
            return RecoveryPolicyDecision(
                type = RecoveryDecisionType.OVERRIDE,
                directive = "DONE",
                finalAction = done,
                finalCanonicalAction = done.toCanonicalAction(sourceMode = action.sourceMode),
                rule = "GOAL_ALREADY_SATISFIED",
                message = "local evidence already satisfies the goal"
            )
        }

        if (action.type == CanonicalActionType.BACK &&
            input.state.isRecentProgress(input.step, config.recentProgressBackBlockWindow)
        ) {
            return waitDecision(
                action = action,
                rule = "BACK_RECENT_PROGRESS_BLOCK",
                message = "recent progress observed; BACK is blocked"
            )
        }

        if (shouldAllowRecoveryBack(input)) {
            return RecoveryPolicyDecision(
                type = RecoveryDecisionType.OVERRIDE,
                directive = "BACK",
                finalAction = GuiAction.Back,
                finalCanonicalAction = GuiAction.Back.toCanonicalAction(sourceMode = action.sourceMode),
                rule = "RECOVERY_BACK_ALLOWED",
                message = "off track with sustained low yield; recovery BACK is allowed"
            )
        }

        if (action.type == CanonicalActionType.HOME && !hasRecoveryEvidence(input.state)) {
            val ask = GuiAction.AskUser("当前没有明确卡死证据，是否需要返回主屏？")
            return RecoveryPolicyDecision(
                type = RecoveryDecisionType.OVERRIDE,
                directive = "ASK_USER",
                finalAction = ask,
                finalCanonicalAction = ask.toCanonicalAction(sourceMode = action.sourceMode),
                rule = "HOME_REQUIRES_RECOVERY_EVIDENCE",
                message = "HOME is blocked without low-yield or recovery evidence"
            )
        }

        if (action.type == CanonicalActionType.CLICK) {
            val currentPoint = action.point
            val previousPoint = input.state.lastClickPoint
            val radius = config.nearbyClickRadiusPx(input.beforeObservation)
            val lastLocalSuccessPoint = input.state.lastLocallyConfirmedClickPoint
            val lastLocalSuccessStep = input.state.lastLocallyConfirmedClickStep
            if (currentPoint != null &&
                lastLocalSuccessPoint != null &&
                lastLocalSuccessStep != null &&
                input.step - lastLocalSuccessStep <= config.toggleRepeatSuppressionWindow &&
                currentPoint.isNear(lastLocalSuccessPoint, radius)
            ) {
                return waitDecision(
                    action = action,
                    durationMs = 250L,
                    rule = "TOGGLE_REPEAT_CLICK_SUPPRESSED",
                    message = "recent local ROI confirmation near click point"
                )
            }
            if (currentPoint != null &&
                previousPoint != null &&
                currentPoint.isNear(previousPoint, radius) &&
                input.state.nearbyRepeatClickCount >= config.repeatClickBlockThreshold - 1
            ) {
                val nextLowYield = input.state.consecutiveLowYieldCount + 1
                return if (nextLowYield >= config.lowYieldFuseThreshold) {
                    val ask = GuiAction.AskUser("连续点击没有推进，当前需要你接手确认下一步。")
                    RecoveryPolicyDecision(
                        type = RecoveryDecisionType.OVERRIDE,
                        directive = "ASK_USER",
                        finalAction = ask,
                        finalCanonicalAction = ask.toCanonicalAction(sourceMode = action.sourceMode),
                        rule = "REPEAT_CLICK_NEARBY_BLOCK",
                        message = "nearby click repeated without progress"
                    )
                } else {
                    waitDecision(
                        action = action,
                        rule = "REPEAT_CLICK_NEARBY_BLOCK",
                        message = "nearby click repeated without progress"
                    )
                }
            }
        }

        return RecoveryPolicyDecision(
            type = RecoveryDecisionType.EXECUTE_PROPOSED,
            directive = "EXECUTE_PROPOSED",
            finalAction = action.toGuiAction(),
            finalCanonicalAction = action
        )
    }

    override fun refineActionConstraints(
        state: GuardrailState,
        capabilityProfile: CapabilityProfile,
        baseAllowedActions: List<String>
    ): RecoveryActionConstraints {
        val supported = capabilityProfile.supportedActions().toSet()
        val allowed = baseAllowedActions.toMutableSet()

        when (state.lastGoalStatus) {
            GoalSatisfactionStatus.SATISFIED -> {
                allowed.retainAll(setOf("DONE", "ASK_USER"))
            }

            GoalSatisfactionStatus.NEED_USER -> {
                allowed.add("ASK_USER")
            }

            else -> Unit
        }

        if (shouldAllowRecoveryBackPreview(state)) {
            if ("BACK" in supported) {
                allowed.add("BACK")
            }
        } else if (state.subgoalState.phase == SubgoalPhase.NEED_OPEN_PARENT ||
            state.subgoalState.phase == SubgoalPhase.NEED_CLICK_TARGET
        ) {
            allowed.remove("BACK")
        }

        if (!hasRecoveryEvidence(state)) {
            allowed.remove("HOME")
        }

        val normalizedAllowed = allowed.filter { it in supported }.sorted()
        return RecoveryActionConstraints(
            allowedActions = normalizedAllowed,
            blockedActions = supported.minus(normalizedAllowed.toSet()).toList().sorted()
        )
    }

    private fun waitDecision(
        action: CanonicalAction,
        durationMs: Long = 500L,
        rule: String,
        message: String
    ): RecoveryPolicyDecision {
        val wait = GuiAction.Wait(durationMs)
        return RecoveryPolicyDecision(
            type = RecoveryDecisionType.OVERRIDE,
            directive = "WAIT",
            finalAction = wait,
            finalCanonicalAction = wait.toCanonicalAction(sourceMode = action.sourceMode),
            rule = rule,
            message = message
        )
    }

    private fun hasRecoveryEvidence(state: GuardrailState): Boolean {
        return state.consecutiveLowYieldCount >= config.noChangeThreshold ||
            state.consecutiveBackHomeBlocks >= config.backHomeBlockEscalationThreshold
    }

    private fun shouldAllowRecoveryBack(input: RecoveryPolicyInput): Boolean {
        if (input.goalStatus != GoalSatisfactionStatus.OFF_TRACK) {
            return false
        }
        if (input.state.consecutiveLowYieldCount < config.noChangeThreshold) {
            return false
        }
        if (input.state.isRecentProgress(input.step, config.recentProgressBackBlockWindow)) {
            return false
        }
        val lastFinalAction = input.state.recentSteps.lastOrNull()?.finalActionType
        if (lastFinalAction == "BACK" || lastFinalAction == "HOME") {
            return false
        }
        if (input.proposedAction.type == CanonicalActionType.ASK_USER ||
            input.proposedAction.type == CanonicalActionType.DONE
        ) {
            return false
        }
        return true
    }

    private fun shouldAllowRecoveryBackPreview(state: GuardrailState): Boolean {
        if (state.lastGoalStatus != GoalSatisfactionStatus.OFF_TRACK) {
            return false
        }
        if (state.consecutiveLowYieldCount < config.noChangeThreshold) {
            return false
        }
        val lastFinalAction = state.recentSteps.lastOrNull()?.finalActionType
        return lastFinalAction != "BACK" && lastFinalAction != "HOME"
    }
}

private fun CanonicalAction.toGuiAction(): GuiAction {
    return when (type) {
        CanonicalActionType.CLICK -> GuiAction.Click(
            x = requireNotNull(point).x,
            y = requireNotNull(point).y
        )

        CanonicalActionType.LONG_PRESS -> GuiAction.LongPress(
            x = requireNotNull(point).x,
            y = requireNotNull(point).y,
            durationMs = durationMs ?: 800L
        )

        CanonicalActionType.SWIPE -> GuiAction.Swipe(
            x1 = requireNotNull(points.getOrNull(0)).x,
            y1 = requireNotNull(points.getOrNull(0)).y,
            x2 = requireNotNull(points.getOrNull(1)).x,
            y2 = requireNotNull(points.getOrNull(1)).y,
            durationMs = durationMs ?: 300L
        )

        CanonicalActionType.TYPE_TEXT -> GuiAction.TypeText(text.orEmpty())
        CanonicalActionType.BACK -> GuiAction.Back
        CanonicalActionType.HOME -> GuiAction.Home
        CanonicalActionType.WAIT -> GuiAction.Wait(durationMs ?: 500L)
        CanonicalActionType.ASK_USER -> GuiAction.AskUser(question.orEmpty())
        CanonicalActionType.DONE -> GuiAction.Done(reason ?: "done")
    }
}
