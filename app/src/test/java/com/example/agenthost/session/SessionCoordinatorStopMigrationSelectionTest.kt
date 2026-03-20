package com.example.agenthost.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCoordinatorStopMigrationSelectionTest {
    @Test
    fun bindStopMigrationStateResolvesVirtualDisplayTargetWhenMissing() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = -1
        )

        val rebound = bindStopMigrationState(state) {
            com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 202,
                displayId = 9,
                topActivity = "com.android.settings/.SettingsHomepageActivity",
                packageName = "com.android.settings"
            )
        }

        assertEquals(202, rebound.lastKnownTaskId)
        assertEquals("com.android.settings", rebound.lastKnownTopPackage)
    }

    @Test
    fun virtualDisplayStopMigrationPrefersLastKnownTargetTask() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 202
        )

        assertEquals(202, preferredStopMigrationTaskId(state))
    }

    @Test
    fun virtualDisplayStopMigrationFallsBackToSessionTaskWhenTargetUnknown() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = -1
        )

        assertEquals(-1, preferredStopMigrationTaskId(state))
    }

    @Test
    fun mainDisplayStopMigrationKeepsUsingSessionTask() {
        val state = baseState(
            displayMode = SessionDisplayMode.MAIN_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 202
        )

        assertEquals(101, preferredStopMigrationTaskId(state))
    }

    private fun baseState(
        displayMode: SessionDisplayMode,
        sessionTaskId: Int,
        lastKnownTaskId: Int
    ): SessionState {
        return SessionState(
            sessionId = "session-1",
            command = "demo",
            executionDisplayId = 9,
            currentVisibleDisplayId = 9,
            virtualDisplayId = if (displayMode == SessionDisplayMode.VIRTUAL_DISPLAY) 9 else null,
            surfaceDisplayId = null,
            sessionTaskId = sessionTaskId,
            lastKnownTaskId = lastKnownTaskId,
            lastKnownTopActivity = "com.android.settings/.Settings",
            lastKnownTopPackage = "com.android.settings",
            status = SessionStatus.WAITING_USER,
            lastError = null,
            waitingQuestion = "确认是否迁回主屏",
            automationStep = 2,
            displayMode = displayMode,
            visibleToUser = false,
            backgroundPreferred = displayMode != SessionDisplayMode.MAIN_DISPLAY,
            returnToVirtualAfterUser = true,
            completionReturnPolicy = CompletionReturnPolicy.CURRENT_BEHAVIOR,
            migratedToMain = false,
            lastMigrationCode = null,
            lastMigrationDirection = null,
            recoveryNeeded = false,
            bootstrapTarget = SessionBootstrapTarget.NONE,
            lastBootstrapCode = null
        )
    }
}
