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

interface DisplayActionExecutor {
    fun capabilities(): ExecutorCapabilities

    fun execute(vdDisplayId: Int, action: GuiAction): ActionExecutionResult
}
