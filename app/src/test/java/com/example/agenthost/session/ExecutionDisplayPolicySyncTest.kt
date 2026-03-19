package com.example.agenthost.session

import android.view.Display
import com.example.agenthost.platform.ExecutionDisplayPolicyGateway
import com.example.agenthost.platform.ExecutionDisplayPolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionDisplayPolicySyncTest {
    @Test
    fun shouldManageOnlyVirtualExecutionDisplaySessions() {
        val gateway = FakeGateway()
        val sync = ExecutionDisplayPolicySync(gateway)

        assertTrue(sync.shouldManage(virtualState()))
        assertFalse(sync.shouldManage(virtualState().copy(executionDisplayId = Display.DEFAULT_DISPLAY)))
        assertFalse(sync.shouldManage(mainState()))
    }

    @Test
    fun registerDelegatesToGatewayRegister() {
        val gateway = FakeGateway()
        val sync = ExecutionDisplayPolicySync(gateway)
        val state = virtualState()

        val response = sync.register(state)

        assertTrue(response.ok)
        assertEquals("register:${state.sessionId}:${state.executionDisplayId}", gateway.lastCall)
    }

    @Test
    fun stateUpdatesMapToExpectedFrameworkStates() {
        val gateway = FakeGateway()
        val sync = ExecutionDisplayPolicySync(gateway)
        val state = virtualState()

        sync.syncState(state, ExecutionDisplayBridgeState.USER_HANDOFF, "waiting_user")
        assertEquals(
            "update:${state.sessionId}:${state.executionDisplayId}:USER_HANDOFF:waiting_user",
            gateway.lastCall
        )

        sync.syncState(state, ExecutionDisplayBridgeState.EXECUTION, "reply_session")
        assertEquals(
            "update:${state.sessionId}:${state.executionDisplayId}:EXECUTION:reply_session",
            gateway.lastCall
        )

        sync.syncState(state, ExecutionDisplayBridgeState.COMPLETED, "session_completed")
        assertEquals(
            "update:${state.sessionId}:${state.executionDisplayId}:COMPLETED:session_completed",
            gateway.lastCall
        )
    }

    @Test
    fun clearDelegatesToGatewayClear() {
        val gateway = FakeGateway()
        val sync = ExecutionDisplayPolicySync(gateway)
        val state = virtualState()

        sync.clear(state, "cleanup")

        assertEquals("clear:${state.sessionId}:cleanup", gateway.lastCall)
    }

    private fun virtualState(): SessionState {
        return SessionState(
            sessionId = "session-1",
            command = "demo",
            executionDisplayId = 42,
            currentVisibleDisplayId = 42,
            virtualDisplayId = 42,
            surfaceDisplayId = null,
            sessionTaskId = 10,
            lastKnownTaskId = 10,
            lastKnownTopActivity = "pkg/.Activity",
            lastKnownTopPackage = "pkg",
            status = SessionStatus.RUNNING,
            lastError = null,
            waitingQuestion = null,
            automationStep = 0,
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            visibleToUser = false,
            backgroundPreferred = true,
            returnToVirtualAfterUser = true,
            completionReturnPolicy = CompletionReturnPolicy.CURRENT_BEHAVIOR,
            migratedToMain = false,
            lastMigrationCode = null,
            lastMigrationDirection = null,
            bootstrapTarget = SessionBootstrapTarget.NONE,
            lastBootstrapCode = null
        )
    }

    private fun mainState(): SessionState {
        return virtualState().copy(
            executionDisplayId = Display.DEFAULT_DISPLAY,
            currentVisibleDisplayId = Display.DEFAULT_DISPLAY,
            virtualDisplayId = null,
            surfaceDisplayId = Display.DEFAULT_DISPLAY,
            displayMode = SessionDisplayMode.MAIN_DISPLAY,
            visibleToUser = true,
            backgroundPreferred = false,
            returnToVirtualAfterUser = false
        )
    }

    private class FakeGateway : ExecutionDisplayPolicyGateway {
        var lastCall: String? = null

        override fun registerExecutionDisplaySession(
            sessionId: String,
            executionDisplayId: Int
        ): ExecutionDisplayPolicyResponse {
            lastCall = "register:$sessionId:$executionDisplayId"
            return ExecutionDisplayPolicyResponse(true, "EXECUTION_SESSION_REGISTERED", "ok")
        }

        override fun updateExecutionDisplaySessionState(
            sessionId: String,
            executionDisplayId: Int,
            state: String,
            reason: String
        ): ExecutionDisplayPolicyResponse {
            lastCall = "update:$sessionId:$executionDisplayId:$state:$reason"
            return ExecutionDisplayPolicyResponse(true, "EXECUTION_SESSION_UPDATED", "ok")
        }

        override fun clearExecutionDisplaySession(
            sessionId: String,
            reason: String
        ): ExecutionDisplayPolicyResponse {
            lastCall = "clear:$sessionId:$reason"
            return ExecutionDisplayPolicyResponse(true, "EXECUTION_SESSION_CLEARED", "ok")
        }
    }
}
