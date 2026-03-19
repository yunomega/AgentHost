package com.example.agenthost.overlay

import com.example.agenthost.session.CompletionReturnPolicy
import com.example.agenthost.session.OverlaySessionGateway
import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionResult
import com.example.agenthost.session.SessionState
import com.example.agenthost.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStartGuardTest {
    @Test
    fun missingTaskRecoveryIsCleanedBeforeStartingNextTask() {
        val gateway = FakeGateway(
            activeSession = baseSession(
                status = SessionStatus.WAITING_USER,
                recoveryNeeded = true,
                lastMigrationCode = "TARGET_TASK_NOT_FOUND"
            ),
            cleanupResult = SessionResult(ok = true, code = "CLOSED")
        )
        var startedCommand: String? = null
        val guard = OverlayStartGuard(
            sessionGateway = gateway,
            logger = {},
            startTask = { command ->
                startedCommand = command
                SessionResult(ok = true, code = "SESSION_STARTED")
            }
        )

        val result = guard.start("打开设置")

        assertTrue(result.ok)
        assertEquals("SESSION_STARTED", result.code)
        assertEquals("打开设置", startedCommand)
        assertEquals(1, gateway.stopCalls)
    }

    @Test
    fun recoverableSessionStillBlocksNewTask() {
        val gateway = FakeGateway(
            activeSession = baseSession(
                status = SessionStatus.FAILED,
                recoveryNeeded = true,
                lastMigrationCode = "USER_OWNS_MAIN_INSTANCE"
            )
        )
        val guard = OverlayStartGuard(
            sessionGateway = gateway,
            logger = {},
            startTask = { SessionResult(ok = true, code = "UNEXPECTED") }
        )

        val result = guard.start("打开设置")

        assertEquals("SESSION_ALREADY_ACTIVE", result.code)
        assertEquals(0, gateway.stopCalls)
    }

    @Test
    fun missingTaskCleanupLogUsesSharedOverlayMapperOutput() {
        val gateway = FakeGateway(
            activeSession = baseSession(
                status = SessionStatus.WAITING_USER,
                recoveryNeeded = true,
                lastMigrationCode = "TARGET_TASK_NOT_FOUND"
            ),
            cleanupResult = SessionResult(ok = true, code = "CLOSED")
        )
        val logs = mutableListOf<String>()
        val guard = OverlayStartGuard(
            sessionGateway = gateway,
            logger = { logs += it },
            startTask = { SessionResult(ok = true, code = "SESSION_STARTED") },
            overlayModeLabel = { "MAPPER_MODE" }
        )

        guard.start("打开设置")

        assertTrue(logs.single().contains("overlayUiState=MAPPER_MODE"))
    }

    private fun baseSession(
        status: SessionStatus,
        recoveryNeeded: Boolean,
        lastMigrationCode: String?
    ): SessionState {
        return SessionState(
            sessionId = "session-1",
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
            waitingQuestion = null,
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
        private val cleanupResult: SessionResult = SessionResult(ok = true, code = "CLOSED")
    ) : OverlaySessionGateway {
        var stopCalls = 0

        override fun getActiveSession(): SessionState? = activeSession

        override fun replyToSession(sessionId: String, userText: String): SessionResult {
            error("not used")
        }

        override fun showSessionOnMain(sessionId: String?): SessionResult {
            error("not used")
        }

        override fun migrateTaskToMain(sessionId: String?): SessionResult {
            error("not used")
        }

        override fun migrateTaskToExecution(sessionId: String?): SessionResult {
            error("not used")
        }

        override fun stopSession(migrate: Boolean): SessionResult {
            stopCalls += 1
            activeSession = null
            return cleanupResult
        }
    }
}
