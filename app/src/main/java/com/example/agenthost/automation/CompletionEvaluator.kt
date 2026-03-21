package com.example.agenthost.automation

enum class GoalSatisfactionStatus {
    SATISFIED,
    UNSURE,
    OFF_TRACK,
    NEED_USER
}

data class GoalSatisfactionInput(
    val task: String,
    val beforeObservation: ObservationResult,
    val afterObservation: ObservationResult?,
    val progressEvidence: ProgressEvidence,
    val recentSteps: List<ControlStepRecord>,
    val proposedAction: CanonicalAction,
    val finalAction: CanonicalAction,
    val guardrailState: GuardrailState,
    val subgoalState: SubgoalState,
    val previousSubgoalState: SubgoalState? = null
)

data class GoalSatisfactionDecision(
    val status: GoalSatisfactionStatus,
    val reason: String,
    val completionReason: String? = null,
    val userQuestion: String? = null,
    val needsUserConfirmation: Boolean = false
)

fun interface GoalSatisfactionEvaluator {
    fun evaluate(input: GoalSatisfactionInput): GoalSatisfactionDecision
}

class DefaultGoalSatisfactionEvaluator : GoalSatisfactionEvaluator {
    override fun evaluate(input: GoalSatisfactionInput): GoalSatisfactionDecision {
        if (input.finalAction.type == CanonicalActionType.ASK_USER) {
            return GoalSatisfactionDecision(
                status = GoalSatisfactionStatus.NEED_USER,
                reason = "explicit_user_handoff",
                userQuestion = input.finalAction.question ?: "当前需要你接手确认下一步。"
            )
        }

        if (input.progressEvidence.needsUserConfirmation) {
            return GoalSatisfactionDecision(
                status = GoalSatisfactionStatus.NEED_USER,
                reason = "completion_confirmation_required",
                userQuestion = "模型判断任务可能已完成，但当前界面仍需你确认。",
                needsUserConfirmation = true
            )
        }

        if (isSatisfied(input)) {
            return GoalSatisfactionDecision(
                status = GoalSatisfactionStatus.SATISFIED,
                reason = "local_evidence_satisfied",
                completionReason = completionReason(input)
            )
        }

        if (input.finalAction.type == CanonicalActionType.DONE &&
            input.beforeObservation is ObservationResult.Captured
        ) {
            return GoalSatisfactionDecision(
                status = GoalSatisfactionStatus.NEED_USER,
                reason = "model_done_requires_visual_confirmation",
                userQuestion = "模型判断任务可能已完成，但当前界面仍需你确认。",
                needsUserConfirmation = true
            )
        }

        if (isOffTrack(input)) {
            return GoalSatisfactionDecision(
                status = GoalSatisfactionStatus.OFF_TRACK,
                reason = "local_evidence_off_track"
            )
        }

        if (input.progressEvidence.modelRequestedDone) {
            return GoalSatisfactionDecision(
                status = GoalSatisfactionStatus.UNSURE,
                reason = "model_done_without_strong_local_evidence"
            )
        }

        return GoalSatisfactionDecision(
            status = GoalSatisfactionStatus.UNSURE,
            reason = "insufficient_local_evidence"
        )
    }

    private fun isSatisfied(input: GoalSatisfactionInput): Boolean {
        if (input.subgoalState.phase == SubgoalPhase.DONE_ONLY) {
            return true
        }
        if (input.progressEvidence.goalRelation == GoalRelation.AT_TARGET &&
            localOpenAppEvidenceSatisfied(input)
        ) {
            return true
        }
        if (input.progressEvidence.observationDelta.localRoiChanged &&
            input.previousSubgoalState?.phase == input.subgoalState.phase &&
            localOpenAppEvidenceSatisfied(input)
        ) {
            return true
        }
        if (input.progressEvidence.modelRequestedDone &&
            input.beforeObservation !is ObservationResult.Captured &&
            input.afterObservation !is ObservationResult.Captured &&
            input.progressEvidence.recentActionResult != RecentActionResult.PLATFORM_FAILED
        ) {
            return true
        }
        return false
    }

    private fun isOffTrack(input: GoalSatisfactionInput): Boolean {
        if (input.progressEvidence.goalRelation != GoalRelation.OFF_TRACK) {
            return false
        }
        if (input.progressEvidence.recentLowYield) {
            return true
        }
        if (input.progressEvidence.consecutiveLowYieldCount >= 2 &&
            input.progressEvidence.noProgressSignal
        ) {
            return true
        }
        return false
    }

    private fun completionReason(input: GoalSatisfactionInput): String {
        return when {
            input.progressEvidence.goalRelation == GoalRelation.AT_TARGET -> "goal_satisfaction_evaluator_satisfied"
            input.progressEvidence.observationDelta.localRoiChanged &&
                requiresOpenAppSurfaceEvidence(input) &&
                hasStableTargetUiEvidence(input) -> "target_surface_goal_satisfied"
            input.progressEvidence.observationDelta.localRoiChanged &&
                requiresOpenAppSurfaceEvidence(input) &&
                hasTopSurfaceSwitch(input) -> "surface_switch_goal_satisfied"
            input.progressEvidence.observationDelta.localRoiChanged -> "local_roi_goal_satisfied"
            input.progressEvidence.modelRequestedDone &&
                input.beforeObservation !is ObservationResult.Captured &&
                input.afterObservation !is ObservationResult.Captured ->
                input.finalAction.reason ?: "model_done_without_visual_evidence"

            else -> "goal_satisfaction_evaluator_satisfied"
        }
    }

    private fun localOpenAppEvidenceSatisfied(input: GoalSatisfactionInput): Boolean {
        if (!requiresOpenAppSurfaceEvidence(input)) {
            return true
        }
        return hasTopSurfaceSwitch(input) || hasStableTargetUiEvidence(input)
    }

    private fun requiresOpenAppSurfaceEvidence(input: GoalSatisfactionInput): Boolean {
        return input.subgoalState.spec == null && looksLikeOpenAppTask(input.task)
    }

    private fun looksLikeOpenAppTask(task: String): Boolean {
        val normalized = task.trim().lowercase()
        return normalized.startsWith("打开") ||
            normalized.startsWith("启动") ||
            normalized.startsWith("进入") ||
            normalized.startsWith("open ") ||
            normalized == "open" ||
            normalized.startsWith("launch ") ||
            normalized == "launch" ||
            normalized.startsWith("start ")
    }

    private fun hasTopSurfaceSwitch(input: GoalSatisfactionInput): Boolean {
        val previous = input.guardrailState.lastSurfaceContext
        val current = input.progressEvidence.surfaceContext
        if (isLauncherSurface(current)) {
            return false
        }
        return changedNonBlank(previous.topPackage, current.topPackage) ||
            changedNonBlank(previous.topActivity, current.topActivity)
    }

    private fun hasStableTargetUiEvidence(input: GoalSatisfactionInput): Boolean {
        return surfaceMatchesTask(input.task, input.progressEvidence.surfaceContext)
    }

    private fun changedNonBlank(previous: String?, current: String?): Boolean {
        return !current.isNullOrBlank() && previous != current
    }

    private fun isLauncherSurface(surfaceContext: SurfaceContext): Boolean {
        val topPackage = surfaceContext.topPackage?.lowercase().orEmpty()
        val topActivity = surfaceContext.topActivity?.lowercase().orEmpty()
        return topPackage.contains("launcher") || topActivity.contains("launcher")
    }

    private fun surfaceMatchesTask(task: String, surfaceContext: SurfaceContext): Boolean {
        val commandTokens = task.lowercase()
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
}
