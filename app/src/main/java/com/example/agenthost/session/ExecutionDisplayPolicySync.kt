package com.example.agenthost.session

import android.view.Display
import com.example.agenthost.platform.ExecutionDisplayPolicyGateway
import com.example.agenthost.platform.ExecutionDisplayPolicyResponse

internal enum class ExecutionDisplayBridgeState {
    EXECUTION,
    USER_HANDOFF,
    COMPLETED
}

internal class ExecutionDisplayPolicySync(
    private val gateway: ExecutionDisplayPolicyGateway
) {
    fun shouldManage(state: SessionState): Boolean {
        return state.displayMode == SessionDisplayMode.VIRTUAL_DISPLAY &&
            state.executionDisplayId != Display.DEFAULT_DISPLAY
    }

    fun register(state: SessionState): ExecutionDisplayPolicyResponse {
        return gateway.registerExecutionDisplaySession(state.sessionId, state.executionDisplayId)
    }

    fun syncState(
        state: SessionState,
        bridgeState: ExecutionDisplayBridgeState,
        reason: String
    ): ExecutionDisplayPolicyResponse {
        return gateway.updateExecutionDisplaySessionState(
            sessionId = state.sessionId,
            executionDisplayId = state.executionDisplayId,
            state = bridgeState.wireValue(),
            reason = reason
        )
    }

    fun clear(state: SessionState, reason: String): ExecutionDisplayPolicyResponse {
        return gateway.clearExecutionDisplaySession(state.sessionId, reason)
    }
}

private fun ExecutionDisplayBridgeState.wireValue(): String {
    return when (this) {
        ExecutionDisplayBridgeState.EXECUTION -> "EXECUTION"
        ExecutionDisplayBridgeState.USER_HANDOFF -> "USER_HANDOFF"
        ExecutionDisplayBridgeState.COMPLETED -> "COMPLETED"
    }
}
