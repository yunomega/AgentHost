package com.example.agenthost.overlay

import com.example.agenthost.session.CompletionReturnPolicy
import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionState
import com.example.agenthost.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRecoverySupportTest {
    @Test
    fun targetTaskNotFoundPrefersEndingSessionToIdle() {
        val session = baseSession(lastMigrationCode = "TARGET_TASK_NOT_FOUND")

        assertEquals(
            RecoveryPrimaryRoute.END_SESSION_TO_IDLE,
            OverlayRecoverySupport.derivePrimaryRoute(session)
        )
    }

    @Test
    fun migratedRecoveryPrefersMoveBackToExecution() {
        val session = baseSession(
            migratedToMain = true,
            currentVisibleDisplayId = 0,
            lastMigrationCode = "TASK_MIGRATION_OK"
        )

        assertEquals(
            RecoveryPrimaryRoute.MIGRATE_TO_EXECUTION,
            OverlayRecoverySupport.derivePrimaryRoute(session)
        )
    }

    @Test
    fun migratedMissingTaskRecoveryStillFallsBackToIdle() {
        val session = baseSession(
            migratedToMain = true,
            currentVisibleDisplayId = 0,
            lastMigrationCode = "TASK_ID_STALE"
        )

        assertEquals(
            RecoveryPrimaryRoute.END_SESSION_TO_IDLE,
            OverlayRecoverySupport.derivePrimaryRoute(session)
        )
    }

    @Test
    fun mirroredMainSurfaceStillFallsBackToIdleWhenTaskIsMissing() {
        val session = baseSession(
            visibleToUser = true,
            surfaceDisplayId = 0,
            lastMigrationCode = "TARGET_TASK_NOT_FOUND"
        )

        assertTrue(OverlayRecoverySupport.isAlreadyOnMain(session))
        assertEquals(
            RecoveryPrimaryRoute.END_SESSION_TO_IDLE,
            OverlayRecoverySupport.derivePrimaryRoute(session)
        )
    }

    @Test
    fun nonMissingTaskRecoveryDoesNotFallbackToIdle() {
        val session = baseSession(lastMigrationCode = "USER_OWNS_MAIN_INSTANCE")

        assertEquals(
            RecoveryPrimaryRoute.MIGRATE_TO_MAIN,
            OverlayRecoverySupport.derivePrimaryRoute(session)
        )
    }

    private fun baseSession(
        migratedToMain: Boolean = false,
        visibleToUser: Boolean = false,
        surfaceDisplayId: Int? = null,
        currentVisibleDisplayId: Int? = 9,
        lastMigrationCode: String? = null
    ): SessionState {
        return SessionState(
            sessionId = "session-1",
            command = "demo",
            executionDisplayId = 9,
            currentVisibleDisplayId = currentVisibleDisplayId,
            virtualDisplayId = 9,
            surfaceDisplayId = surfaceDisplayId,
            sessionTaskId = 100,
            lastKnownTaskId = 88,
            lastKnownTopActivity = "com.android.settings/.Settings",
            lastKnownTopPackage = "com.android.settings",
            status = SessionStatus.FAILED,
            lastError = null,
            waitingQuestion = null,
            automationStep = 2,
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            visibleToUser = visibleToUser,
            backgroundPreferred = true,
            returnToVirtualAfterUser = true,
            completionReturnPolicy = CompletionReturnPolicy.CURRENT_BEHAVIOR,
            migratedToMain = migratedToMain,
            lastMigrationCode = lastMigrationCode,
            lastMigrationDirection = if (migratedToMain) "TO_MAIN" else null,
            recoveryNeeded = true,
            bootstrapTarget = SessionBootstrapTarget.NONE,
            lastBootstrapCode = null
        )
    }
}
