package com.example.agenthost.automation

data class ExecutorCapabilities(
    val supported: Boolean,
    val displayFlag: String?,
    val code: String,
    val details: String
)

data class ActionExecutionResult(
    val ok: Boolean,
    val code: String,
    val details: String
)

data class ActionExecutionRequest(
    val sessionId: String,
    val vdDisplayId: Int,
    val action: GuiAction
)

interface DisplayActionExecutor {
    fun capabilities(): ExecutorCapabilities

    fun execute(request: ActionExecutionRequest): ActionExecutionResult
}
