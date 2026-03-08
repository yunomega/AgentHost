package com.example.agenthost.automation

sealed class PlanResult {
    data class PlannedAction(val action: GuiAction) : PlanResult()

    data class PlanFailure(
        val code: String,
        val message: String,
        val rawResponseSnippet: String? = null
    ) : PlanResult()
}
