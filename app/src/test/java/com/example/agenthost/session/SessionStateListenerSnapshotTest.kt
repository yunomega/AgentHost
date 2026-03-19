package com.example.agenthost.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionStateListenerSnapshotTest {
    @Test
    fun automationStepDoesNotChangeListenerSnapshot() {
        val base = baseSession()

        assertEquals(
            SessionStateListenerSnapshot.forRenderDiff(base),
            SessionStateListenerSnapshot.forRenderDiff(base.copy(automationStep = 7))
        )
    }

    @Test
    fun waitingQuestionChangeDoesChangeListenerSnapshot() {
        val base = baseSession()

        assertNotEquals(
            SessionStateListenerSnapshot.forRenderDiff(base),
            SessionStateListenerSnapshot.forRenderDiff(base.copy(waitingQuestion = "请输入验证码"))
        )
    }

    @Test
    fun postCompletionFieldsDoChangeListenerSnapshot() {
        val base = baseSession()

        assertNotEquals(
            SessionStateListenerSnapshot.forRenderDiff(base),
            SessionStateListenerSnapshot.forRenderDiff(
                base.copy(
                    status = SessionStatus.COMPLETED,
                    postCompletionDisposition = PostCompletionDisposition.CLEANUP_IMMEDIATELY,
                    postCompletionState = PostCompletionState.SUCCEEDED,
                    postCompletionResultCode = "COMPLETION_CLEANUP_READY"
                )
            )
        )
    }

    @Test
    fun recoveryFieldsDoChangeListenerSnapshot() {
        val base = baseSession()

        assertNotEquals(
            SessionStateListenerSnapshot.forRenderDiff(base),
            SessionStateListenerSnapshot.forRenderDiff(
                base.copy(
                    recoveryNeeded = true,
                    lastMigrationCode = "TASK_MOVE_BACK_CONFLICT"
                )
            )
        )
    }

    private fun baseSession(): SessionState {
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
            status = SessionStatus.RUNNING,
            lastError = null,
            waitingQuestion = null,
            automationStep = 2,
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            visibleToUser = false,
            backgroundPreferred = true,
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
