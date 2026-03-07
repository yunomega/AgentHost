package com.example.agenthost.automation

import android.view.KeyEvent
import com.example.agenthost.core.AgentHostLog
import com.example.agenthost.platform.PlatformAutomationGateway

class PlatformDisplayActionExecutor(
    private val gateway: PlatformAutomationGateway
) : DisplayActionExecutor {
    companion object {
        private const val TAG = "PlatformDisplayExecutor"
    }

    init {
        AgentHostLog.i(
            TAG,
            "event=display_executor_selected sessionId=null vdDisplayId=-1 " +
                "sessionTaskId=-1 step=0 actionType=NONE code=PLATFORM_EXECUTOR extras=service=agenthost_platform"
        )
    }

    override fun capabilities(): ExecutorCapabilities {
        return if (gateway.isAvailable()) {
            ExecutorCapabilities(
                supported = true,
                displayFlag = null,
                code = "PLATFORM_EXECUTOR_READY",
                details = "service=agenthost_platform"
            )
        } else {
            ExecutorCapabilities(
                supported = false,
                displayFlag = null,
                code = "UNSUPPORTED",
                details = "agenthost_platform service unavailable"
            )
        }
    }

    override fun execute(request: ActionExecutionRequest): ActionExecutionResult {
        val response = when (val action = request.action) {
            is GuiAction.Click -> gateway.injectTap(request.sessionId, request.vdDisplayId, action.x, action.y)
            is GuiAction.Swipe -> gateway.injectSwipe(
                request.sessionId,
                request.vdDisplayId,
                action.x1,
                action.y1,
                action.x2,
                action.y2,
                action.durationMs
            )

            is GuiAction.LongPress -> gateway.injectLongPress(
                request.sessionId,
                request.vdDisplayId,
                action.x,
                action.y,
                action.durationMs
            )

            is GuiAction.TypeText -> gateway.injectText(request.sessionId, request.vdDisplayId, action.text)
            GuiAction.Back -> gateway.injectKey(request.sessionId, request.vdDisplayId, KeyEvent.KEYCODE_BACK)
            GuiAction.Home -> gateway.injectKey(request.sessionId, request.vdDisplayId, KeyEvent.KEYCODE_HOME)
            is GuiAction.Wait,
            is GuiAction.AskUser,
            is GuiAction.Done -> return ActionExecutionResult(
                ok = false,
                code = "EXECUTOR_ACTION_UNSUPPORTED",
                details = "action=${request.action.actionType()}"
            )
        }

        return ActionExecutionResult(
            ok = response.ok,
            code = response.code,
            details = response.details
        )
    }
}
