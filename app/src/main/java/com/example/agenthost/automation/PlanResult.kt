package com.example.agenthost.automation

sealed class PlanResult {
    data class PlannedAction(
        val action: GuiAction,
        val canonicalAction: CanonicalAction = action.toCanonicalAction(),
        val rawResponseText: String? = null
    ) : PlanResult()

    data class PlanFailure(
        val code: String,
        val message: String,
        val rawResponseSnippet: String? = null
    ) : PlanResult()
}
