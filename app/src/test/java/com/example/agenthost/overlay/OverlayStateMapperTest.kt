package com.example.agenthost.overlay

import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionState
import com.example.agenthost.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStateMapperTest {
    private val mapper = OverlayStateMapper()

    @Test
    fun nullSessionMapsToIdle() {
        val state = mapper.map(null)

        assertEquals(OverlayMode.IDLE, state.mode)
        assertEquals("空闲", state.bubbleText)
    }

    @Test
    fun runningVirtualDisplayMapsToRunning() {
        val state = mapper.map(baseSession(status = SessionStatus.RUNNING))

        assertEquals(OverlayMode.RUNNING, state.mode)
        assertEquals("执行中", state.bubbleText)
        assertEquals("虚拟屏后台执行", state.bubbleDetail)
    }

    @Test
    fun waitingUserBeforeMigrationMapsToNeedUser() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.WAITING_USER,
                waitingQuestion = "请输入验证码"
            )
        )

        assertEquals(OverlayMode.NEED_USER, state.mode)
        assertEquals("去主屏处理", state.primaryLabel)
        assertFalse(state.alreadyOnMain)
    }

    @Test
    fun waitingUserAfterMigrationMapsToHandoff() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.WAITING_USER,
                waitingQuestion = "请输入验证码",
                migratedToMain = true,
                currentVisibleDisplayId = 0,
                lastMigrationCode = "TASK_MIGRATION_OK"
            )
        )

        assertEquals(OverlayMode.HANDOFF_ACTIVE, state.mode)
        assertTrue(state.requiresReplyInput)
        assertEquals(OverlayUiState.DEFAULT_REPLY_TEXT, state.defaultReplyText)
        assertTrue(state.alreadyOnMain)
    }

    @Test
    fun recoveryNeededTakesPriorityOverWaitingUser() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.WAITING_USER,
                waitingQuestion = "请输入验证码",
                migratedToMain = true,
                currentVisibleDisplayId = 0,
                recoveryNeeded = true,
                lastMigrationCode = "TASK_MOVE_BACK_CONFLICT"
            )
        )

        assertEquals(OverlayMode.RECOVERY, state.mode)
        assertEquals("异常", state.bubbleText)
        assertEquals("返回后台执行", state.primaryLabel)
    }

    @Test
    fun mainDisplayWaitingUserStillUsesNeedUserWithoutMigrationAction() {
        val state = mapper.map(
            baseSession(
                displayMode = SessionDisplayMode.MAIN_DISPLAY,
                executionDisplayId = 0,
                currentExecutionDisplayId = 0,
                currentVisibleDisplayId = 0,
                virtualDisplayId = null,
                surfaceDisplayId = 0,
                status = SessionStatus.WAITING_USER,
                waitingQuestion = "请确认是否继续"
            )
        )

        assertEquals(OverlayMode.NEED_USER, state.mode)
        assertTrue(state.alreadyOnMain)
        assertEquals("继续处理", state.primaryLabel)
    }

    private fun baseSession(
        displayMode: SessionDisplayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
        executionDisplayId: Int = 9,
        currentExecutionDisplayId: Int = executionDisplayId,
        currentVisibleDisplayId: Int? = executionDisplayId,
        virtualDisplayId: Int? = executionDisplayId,
        surfaceDisplayId: Int? = null,
        status: SessionStatus = SessionStatus.RUNNING,
        waitingQuestion: String? = null,
        migratedToMain: Boolean = false,
        recoveryNeeded: Boolean = false,
        lastMigrationCode: String? = null
    ): SessionState {
        return SessionState(
            sessionId = "session-1",
            command = "demo",
            executionDisplayId = executionDisplayId,
            currentExecutionDisplayId = currentExecutionDisplayId,
            currentVisibleDisplayId = currentVisibleDisplayId,
            virtualDisplayId = virtualDisplayId,
            surfaceDisplayId = surfaceDisplayId,
            sessionTaskId = 100,
            lastKnownTaskId = 88,
            lastKnownTopActivity = "com.android.settings/.Settings",
            lastKnownTopPackage = "com.android.settings",
            status = status,
            lastError = null,
            waitingQuestion = waitingQuestion,
            automationStep = 2,
            displayMode = displayMode,
            visibleToUser = displayMode == SessionDisplayMode.MAIN_DISPLAY || migratedToMain,
            backgroundPreferred = displayMode != SessionDisplayMode.MAIN_DISPLAY,
            returnToVirtualAfterUser = true,
            pendingReturnToVirtualDisplay = false,
            migratedToMain = migratedToMain,
            lastMigrationCode = lastMigrationCode,
            lastMigrationDirection = if (migratedToMain) "TO_MAIN" else null,
            recoveryNeeded = recoveryNeeded,
            bootstrapTarget = SessionBootstrapTarget.NONE,
            lastBootstrapCode = null
        )
    }
}
