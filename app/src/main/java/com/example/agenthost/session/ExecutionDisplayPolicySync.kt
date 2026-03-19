package com.example.agenthost.session

import android.view.Display
import com.example.agenthost.platform.ExecutionDisplayPolicyGateway
import com.example.agenthost.platform.ExecutionDisplayPolicyResponse

internal class ExecutionDisplayPolicySync(
    private val gateway: ExecutionDisplayPolicyGateway
) {
    companion object {
        const val STATE_EXECUTION = "EXECUTION"
        const val STATE_USER_HANDOFF = "USER_HANDOFF"
        const val STATE_COMPLETED = "COMPLETED"
    }

    fun shouldManage(state: SessionState): Boolean {
        return state.displayMode == SessionDisplayMode.VIRTUAL_DISPLAY &&
            state.executionDisplayId != Display.DEFAULT_DISPLAY
    }

    fun register(state: SessionState): ExecutionDisplayPolicyResponse {
        return gateway.registerExecutionDisplaySession(state.sessionId, state.executionDisplayId)
    }

    fun markExecution(state: SessionState, reason: String): ExecutionDisplayPolicyResponse {
        return updateState(state, STATE_EXECUTION, reason)
    }

    fun markUserHandoff(state: SessionState, reason: String): ExecutionDisplayPolicyResponse {
        return updateState(state, STATE_USER_HANDOFF, reason)
    }

    fun markCompleted(state: SessionState, reason: String): ExecutionDisplayPolicyResponse {
        return updateState(state, STATE_COMPLETED, reason)
    }

    fun clear(state: SessionState, reason: String): ExecutionDisplayPolicyResponse {
        return gateway.clearExecutionDisplaySession(state.sessionId, reason)
    }

    private fun updateState(
        state: SessionState,
        policyState: String,
        reason: String
    ): ExecutionDisplayPolicyResponse {
        return gateway.updateExecutionDisplaySessionState(
            sessionId = state.sessionId,
            executionDisplayId = state.executionDisplayId,
            state = policyState,
            reason = reason
        )
    }
}
