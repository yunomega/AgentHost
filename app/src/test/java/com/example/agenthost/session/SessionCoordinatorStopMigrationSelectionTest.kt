package com.example.agenthost.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorStopMigrationSelectionTest {
    @Test
    fun resolveStopMigrationTargetPrefersCurrentFrontTaskOverStaleLastKnownTask() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 404,
            bootstrapTarget = SessionBootstrapTarget.SECONDARY_HOME
        ).copy(
            lastKnownTopActivity = "com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher",
            lastKnownTopPackage = "com.android.launcher3"
        )

        val resolved = resolveStopMigrationTarget(
            state = state,
            taskQuerySupported = true,
            currentTopTask = com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 505,
                displayId = 9,
                topActivity = "com.android.calendar/.AllInOneActivity",
                packageName = "com.android.calendar"
            ),
            agentHostPackage = "com.example.agenthost"
        )

        assertTrue(resolved.isResolved)
        assertEquals(505, resolved.selectedTarget?.taskId)
        assertEquals("com.android.calendar", resolved.selectedTarget?.packageName)
        assertEquals(STOP_TARGET_SOURCE_CURRENT_TOP_TASK, resolved.resolutionSource)
    }

    @Test
    fun bindBootstrapTargetStateResolvesBootstrapTaskWhenMissing() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = -1,
            bootstrapTarget = SessionBootstrapTarget.SETTINGS
        ).copy(
            lastKnownTopActivity = null,
            lastKnownTopPackage = null
        )

        val bound = bindBootstrapTargetState(state) {
            com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 303,
                displayId = 9,
                topActivity = "com.android.settings/.SettingsHomepageActivity",
                packageName = "com.android.settings"
            )
        }

        assertEquals(303, bound.lastKnownTaskId)
        assertEquals("com.android.settings/.SettingsHomepageActivity", bound.lastKnownTopActivity)
        assertEquals("com.android.settings", bound.lastKnownTopPackage)
    }

    @Test
    fun bindBootstrapTargetStateSupportsSecondaryHomeWhenMissing() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = -1,
            bootstrapTarget = SessionBootstrapTarget.SECONDARY_HOME
        ).copy(
            lastKnownTopActivity = null,
            lastKnownTopPackage = null
        )

        val bound = bindBootstrapTargetState(state) {
            com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 404,
                displayId = 9,
                topActivity = "com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher",
                packageName = "com.android.launcher3"
            )
        }

        assertEquals(404, bound.lastKnownTaskId)
        assertEquals("com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher", bound.lastKnownTopActivity)
        assertEquals("com.android.launcher3", bound.lastKnownTopPackage)
        assertEquals(9, bound.currentVisibleDisplayId)
    }

    @Test
    fun bindBootstrapTargetStateForceRefreshRebindsExistingTarget() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 303,
            bootstrapTarget = SessionBootstrapTarget.SECONDARY_HOME
        ).copy(
            lastKnownTopActivity = "old.launcher/.Home",
            lastKnownTopPackage = "old.launcher",
            currentVisibleDisplayId = 5
        )

        val rebound = bindBootstrapTargetState(state, forceRefresh = true) {
            com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 505,
                displayId = 9,
                topActivity = "com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher",
                packageName = "com.android.launcher3"
            )
        }

        assertEquals(505, rebound.lastKnownTaskId)
        assertEquals("com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher", rebound.lastKnownTopActivity)
        assertEquals("com.android.launcher3", rebound.lastKnownTopPackage)
        assertEquals(9, rebound.currentVisibleDisplayId)
    }

    @Test
    fun bindBootstrapTargetStateWithoutForceRefreshKeepsExistingTarget() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 303,
            bootstrapTarget = SessionBootstrapTarget.SECONDARY_HOME
        )

        val unchanged = bindBootstrapTargetState(state) {
            com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 505,
                displayId = 9,
                topActivity = "com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher",
                packageName = "com.android.launcher3"
            )
        }

        assertEquals(state, unchanged)
    }

    @Test
    fun resolveStopMigrationTargetAllowsReuseWhenCurrentFrontMatchesLastKnownTask() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 202
        )

        val resolved = resolveStopMigrationTarget(
            state = state,
            taskQuerySupported = true,
            currentTopTask = com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 202,
                displayId = 9,
                topActivity = "com.android.settings/.Settings",
                packageName = "com.android.settings"
            ),
            agentHostPackage = "com.example.agenthost"
        )

        assertTrue(resolved.isResolved)
        assertEquals(202, resolved.selectedTarget?.taskId)
        assertEquals(STOP_TARGET_SOURCE_REUSED_LAST_KNOWN, resolved.resolutionSource)
    }

    @Test
    fun resolveStopMigrationTargetFailsWhenTaskQueryUnsupported() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 202
        )

        val resolved = resolveStopMigrationTarget(
            state = state,
            taskQuerySupported = false,
            currentTopTask = null,
            agentHostPackage = "com.example.agenthost"
        )

        assertFalse(resolved.isResolved)
        assertEquals(STOP_TARGET_UNRESOLVED_CODE, resolved.failureCode)
        assertEquals(STOP_TARGET_SOURCE_UNRESOLVED_QUERY_UNSUPPORTED, resolved.resolutionSource)
    }

    @Test
    fun resolveStopMigrationTargetRejectsBootstrapOnlyShellTask() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 404,
            bootstrapTarget = SessionBootstrapTarget.SECONDARY_HOME
        )

        val resolved = resolveStopMigrationTarget(
            state = state,
            taskQuerySupported = true,
            currentTopTask = com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 404,
                displayId = 9,
                topActivity = "com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher",
                packageName = "com.android.launcher3"
            ),
            agentHostPackage = "com.example.agenthost"
        )

        assertFalse(resolved.isResolved)
        assertEquals(STOP_TARGET_UNRESOLVED_CODE, resolved.failureCode)
        assertEquals(STOP_TARGET_SOURCE_UNRESOLVED_BOOTSTRAP_ONLY_SHELL, resolved.resolutionSource)
        assertEquals(404, resolved.observedTask?.taskId)
    }

    @Test
    fun resolveStopMigrationTargetRejectsAgentHostTask() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 202
        )

        val resolved = resolveStopMigrationTarget(
            state = state,
            taskQuerySupported = true,
            currentTopTask = com.example.agenthost.migration.TaskMigrationTarget(
                taskId = 606,
                displayId = 9,
                topActivity = "com.example.agenthost/.session.SessionRootActivity",
                packageName = "com.example.agenthost"
            ),
            agentHostPackage = "com.example.agenthost"
        )

        assertFalse(resolved.isResolved)
        assertEquals(STOP_TARGET_UNRESOLVED_CODE, resolved.failureCode)
        assertEquals(STOP_TARGET_SOURCE_UNRESOLVED_AGENT_HOST_TASK, resolved.resolutionSource)
    }

    @Test
    fun virtualDisplayStopMigrationPrefersFreshResolvedStopTarget() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 202
        ).copy(
            stopTargetTaskId = 505,
            stopTargetTopActivity = "com.android.calendar/.AllInOneActivity",
            stopTargetTopPackage = "com.android.calendar",
            stopTargetDisplayId = 9,
            stopTargetResolutionSource = STOP_TARGET_SOURCE_CURRENT_TOP_TASK
        )

        assertEquals(505, preferredStopMigrationTaskId(state))
    }

    @Test
    fun virtualDisplayStopMigrationDoesNotFallBackToLastKnownWhenStopTargetUnresolved() {
        val state = baseState(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            sessionTaskId = 101,
            lastKnownTaskId = 202
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
        lastKnownTaskId: Int,
        bootstrapTarget: SessionBootstrapTarget = SessionBootstrapTarget.NONE
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
            bootstrapTarget = bootstrapTarget,
            lastBootstrapCode = null
        )
    }
}
