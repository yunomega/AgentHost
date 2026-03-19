package com.example.agenthost.automation

enum class GuardrailDecisionType {
    ALLOW,
    BLOCK_REPLACE,
    BLOCK_TERMINAL
}

data class GuardrailDecision(
    val type: GuardrailDecisionType,
    val finalAction: GuiAction,
    val finalCanonicalAction: CanonicalAction,
    val rule: String? = null,
    val message: String? = null
) {
    fun wasBlocked(): Boolean = type != GuardrailDecisionType.ALLOW
}

class GuardrailEvaluator(
    private val config: GuardrailConfig = GuardrailConfig()
) {
    fun evaluatePreExecution(
        step: Int,
        state: GuardrailState,
        beforeObservation: ObservationResult,
        action: CanonicalAction
    ): GuardrailDecision {
        if (state.subgoalState.phase == SubgoalPhase.DONE_ONLY &&
            action.type != CanonicalActionType.DONE &&
            action.type != CanonicalActionType.ASK_USER
        ) {
            val done = GuiAction.Done("post_target_gate")
            return GuardrailDecision(
                type = GuardrailDecisionType.BLOCK_TERMINAL,
                finalAction = done,
                finalCanonicalAction = done.toCanonicalAction(sourceMode = action.sourceMode),
                rule = "POST_TARGET_GATE",
                message = "phase=DONE_ONLY only allows DONE or ASK_USER"
            )
        }

        if (action.type == CanonicalActionType.BACK && state.isRecentProgress(step, config.recentProgressBackBlockWindow)) {
            val replacement = GuiAction.Wait(500L)
            return GuardrailDecision(
                type = GuardrailDecisionType.BLOCK_REPLACE,
                finalAction = replacement,
                finalCanonicalAction = replacement.toCanonicalAction(sourceMode = action.sourceMode),
                rule = "BACK_RECENT_PROGRESS_BLOCK",
                message = "recent progress observed; BACK is blocked"
            )
        }

        if (action.type == CanonicalActionType.HOME &&
            state.consecutiveLowYieldCount < config.noChangeThreshold &&
            state.consecutiveBackHomeBlocks < config.backHomeBlockEscalationThreshold
        ) {
            val replacement = GuiAction.AskUser("当前没有明确卡死证据，是否需要返回主屏？")
            return GuardrailDecision(
                type = GuardrailDecisionType.BLOCK_REPLACE,
                finalAction = replacement,
                finalCanonicalAction = replacement.toCanonicalAction(sourceMode = action.sourceMode),
                rule = "HOME_REQUIRES_RECOVERY_EVIDENCE",
                message = "HOME is blocked without low-yield or recovery evidence"
            )
        }

        if (action.type == CanonicalActionType.CLICK) {
            val currentPoint = action.point
            val previousPoint = state.lastClickPoint
            val radius = config.nearbyClickRadiusPx(beforeObservation)
            val repeatCount = state.nearbyRepeatClickCount
            if (currentPoint != null &&
                previousPoint != null &&
                currentPoint.isNear(previousPoint, radius) &&
                repeatCount >= config.repeatClickBlockThreshold - 1
            ) {
                val replacement = if (state.consecutiveLowYieldCount + 1 >= config.lowYieldFuseThreshold) {
                    GuiAction.AskUser("连续点击没有推进，当前需要你接手确认下一步。")
                } else {
                    GuiAction.Wait(500L)
                }
                return GuardrailDecision(
                    type = GuardrailDecisionType.BLOCK_REPLACE,
                    finalAction = replacement,
                    finalCanonicalAction = replacement.toCanonicalAction(sourceMode = action.sourceMode),
                    rule = "REPEAT_CLICK_NEARBY_BLOCK",
                    message = "nearby click repeated without progress"
                )
            }
        }

        return GuardrailDecision(
            type = GuardrailDecisionType.ALLOW,
            finalAction = action.toGuiAction(),
            finalCanonicalAction = action
        )
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
