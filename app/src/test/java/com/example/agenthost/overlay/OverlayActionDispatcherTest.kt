package com.example.agenthost.overlay

import com.example.agenthost.session.CompletionReturnPolicy
import com.example.agenthost.session.OverlaySessionGateway
import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionResult
import com.example.agenthost.session.SessionState
import com.example.agenthost.session.SessionStatus
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayActionDispatcherTest {
    private val directExecutor = Executor { runnable -> runnable.run() }
    private val recoveryUi = OverlayUiState(
        mode = OverlayMode.RECOVERY,
        sessionId = "session-1",
        bubbleText = "异常",
        bubbleDetail = "需要人工接手",
        bubbleTone = BubbleTone.RECOVERY
    )

    @Test
    fun submitNewTaskTrimsCommandBeforeDispatch() {
        val commands = mutableListOf<String>()
        val results = mutableListOf<Pair<String, SessionResult>>()
        val dispatcher = OverlayActionDispatcher(
            coordinatorProvider = { throw AssertionError("coordinator should not be used") },
            executor = directExecutor,
            onActionResult = { action, result -> results += action to result },
            logger = {},
            startNewTask = { command ->
                commands += command
                SessionResult(ok = true, code = "SESSION_STARTED")
            }
        )

        dispatcher.submitNewTask("  打开设置并检查 Wi-Fi  ")

        assertEquals(listOf("打开设置并检查 Wi-Fi"), commands)
        assertEquals(1, results.size)
        assertEquals("start_session", results.single().first)
        assertTrue(results.single().second.ok)
    }

    @Test
    fun submitNewTaskReportsBusyGuardResult() {
        val results = mutableListOf<Pair<String, SessionResult>>()
        val dispatcher = OverlayActionDispatcher(
            coordinatorProvider = { throw AssertionError("coordinator should not be used") },
            executor = directExecutor,
            onActionResult = { action, result -> results += action to result },
            logger = {},
            startNewTask = {
                SessionResult(
                    ok = false,
                    code = "SESSION_ALREADY_ACTIVE",
                    data = mapOf("sessionId" to "session-1", "status" to "RUNNING")
                )
            }
        )

        dispatcher.submitNewTask("继续执行当前任务")

        assertEquals(1, results.size)
        assertEquals("start_session", results.single().first)
        assertFalse(results.single().second.ok)
        assertEquals("SESSION_ALREADY_ACTIVE", results.single().second.code)
        assertEquals("session-1", results.single().second.data["sessionId"])
    }

    @Test
    fun submitNewTaskRejectsBlankCommandWithoutCallingStarter() {
        var starterCalled = false
        val results = mutableListOf<Pair<String, SessionResult>>()
        val dispatcher = OverlayActionDispatcher(
            coordinatorProvider = { throw AssertionError("coordinator should not be used") },
            executor = directExecutor,
            onActionResult = { action, result -> results += action to result },
            logger = {},
            startNewTask = {
                starterCalled = true
                SessionResult(ok = true, code = "UNEXPECTED")
            }
        )

        dispatcher.submitNewTask("   ")

        assertFalse(starterCalled)
        assertEquals(1, results.size)
        assertEquals("start_session", results.single().first)
        assertEquals("COMMAND_EMPTY", results.single().second.code)
    }

    @Test
    fun recoveryPrimaryMissingTaskStopsSessionInsteadOfSurfacingMirror() {
        val results = mutableListOf<Pair<String, SessionResult>>()
        val gateway = FakeGateway(
            activeSession = baseSession(
                status = SessionStatus.WAITING_USER,
                recoveryNeeded = true,
                lastMigrationCode = "TARGET_TASK_NOT_FOUND"
            ),
            stopResult = SessionResult(ok = true, code = "CLOSED")
        )
        val dispatcher = OverlayActionDispatcher(
            coordinatorProvider = { gateway },
            executor = directExecutor,
            onActionResult = { action, result -> results += action to result },
            logger = {}
        )

        dispatcher.requestRecoveryPrimary("session-1", recoveryUi)

        assertEquals(1, gateway.stopCalls)
        assertEquals(0, gateway.showOnMainCalls)
        assertEquals("recovery_primary", results.single().first)
        assertEquals("CLOSED", results.single().second.code)
    }

    @Test
    fun staleSessionActionReturnsSessionStaleWithoutTouchingLiveSession() {
        val results = mutableListOf<Pair<String, SessionResult>>()
        val gateway = FakeGateway(activeSession = baseSession(sessionId = "session-2"))
        val dispatcher = OverlayActionDispatcher(
            coordinatorProvider = { gateway },
            executor = directExecutor,
            onActionResult = { action, result -> results += action to result },
            logger = {}
        )

        dispatcher.requestMoveToMain("session-1", recoveryUi.copy(mode = OverlayMode.NEED_USER))

        assertEquals(0, gateway.moveToMainCalls)
        assertEquals("SESSION_STALE", results.single().second.code)
        assertEquals("session-2", results.single().second.data["activeSessionId"])
    }

    @Test
    fun returnToMainStopsSessionWithMigration() {
        val results = mutableListOf<Pair<String, SessionResult>>()
        val gateway = FakeGateway(
            activeSession = baseSession(),
            stopResult = SessionResult(ok = true, code = "MIGRATED_TO_DEFAULT_DISPLAY")
        )
        val dispatcher = OverlayActionDispatcher(
            coordinatorProvider = { gateway },
            executor = directExecutor,
            onActionResult = { action, result -> results += action to result },
            logger = {}
        )

        dispatcher.requestReturnToMain(
            "session-1",
            recoveryUi.copy(mode = OverlayMode.NEED_USER)
        )

        assertEquals(1, gateway.stopCalls)
        assertTrue(gateway.lastStopMigrate)
        assertEquals("return_to_main", results.single().first)
        assertEquals("MIGRATED_TO_DEFAULT_DISPLAY", results.single().second.code)
    }

    private fun baseSession(
        sessionId: String = "session-1",
        status: SessionStatus = SessionStatus.WAITING_USER,
        recoveryNeeded: Boolean = false,
        lastMigrationCode: String? = null
    ): SessionState {
        return SessionState(
            sessionId = sessionId,
            command = "demo",
            executionDisplayId = 9,
            currentVisibleDisplayId = 9,
            virtualDisplayId = 9,
            surfaceDisplayId = null,
            sessionTaskId = 100,
            lastKnownTaskId = 88,
            lastKnownTopActivity = "com.android.settings/.Settings",
            lastKnownTopPackage = "com.android.settings",
            status = status,
            lastError = null,
            waitingQuestion = "请输入验证码",
            automationStep = 2,
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            visibleToUser = false,
            backgroundPreferred = true,
            returnToVirtualAfterUser = true,
            completionReturnPolicy = CompletionReturnPolicy.CURRENT_BEHAVIOR,
            migratedToMain = false,
            lastMigrationCode = lastMigrationCode,
            lastMigrationDirection = null,
            recoveryNeeded = recoveryNeeded,
            bootstrapTarget = SessionBootstrapTarget.NONE,
            lastBootstrapCode = null
        )
    }

    private class FakeGateway(
        private var activeSession: SessionState?,
        private val stopResult: SessionResult = SessionResult(ok = true, code = "STOPPED"),
        private val moveToMainResult: SessionResult = SessionResult(ok = true, code = "TASK_MIGRATION_OK")
    ) : OverlaySessionGateway {
        var moveToMainCalls = 0
        var moveToExecutionCalls = 0
        var showOnMainCalls = 0
        var replyCalls = 0
        var stopCalls = 0
        var lastStopMigrate = false

        override fun getActiveSession(): SessionState? = activeSession

        override fun replyToSession(sessionId: String, userText: String): SessionResult {
            replyCalls += 1
            return SessionResult(ok = true, code = "AUTOMATION_RESUMED")
        }

        override fun showSessionOnMain(sessionId: String?): SessionResult {
            showOnMainCalls += 1
            return SessionResult(ok = true, code = "SESSION_SURFACED_ON_MAIN")
        }

        override fun migrateTaskToMain(sessionId: String?): SessionResult {
            moveToMainCalls += 1
            return moveToMainResult
        }

        override fun migrateTaskToExecution(sessionId: String?): SessionResult {
            moveToExecutionCalls += 1
            return SessionResult(ok = true, code = "TASK_MIGRATION_BACK_OK")
        }

        override fun stopSession(migrate: Boolean): SessionResult {
            stopCalls += 1
            lastStopMigrate = migrate
            activeSession = null
            return stopResult
        }
    }
}
