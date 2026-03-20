package com.example.agenthost.overlay

import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.CompletionReturnPolicy
import com.example.agenthost.session.PostCompletionDisposition
import com.example.agenthost.session.PostCompletionState
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionState
import com.example.agenthost.session.SessionStatus
import com.example.agenthost.session.WaitingUserDisposition
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
        assertEquals(NewTaskEntryState.AVAILABLE, state.newTaskEntryState)
    }

    @Test
    fun runningVirtualDisplayMapsToRunning() {
        val state = mapper.map(baseSession(status = SessionStatus.RUNNING))

        assertEquals(OverlayMode.RUNNING, state.mode)
        assertEquals("执行中", state.bubbleText)
        assertEquals("虚拟屏后台执行", state.bubbleDetail)
        assertEquals(NewTaskEntryState.BLOCKED, state.newTaskEntryState)
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
        assertEquals(NewTaskEntryState.HIDDEN, state.newTaskEntryState)
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
        assertEquals(NewTaskEntryState.HIDDEN, state.newTaskEntryState)
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
        assertEquals(NewTaskEntryState.HIDDEN, state.newTaskEntryState)
    }

    @Test
    fun recoveryMissingTaskUsesIdleFallbackLabel() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.FAILED,
                visibleToUser = true,
                surfaceDisplayId = 0,
                recoveryNeeded = true,
                lastMigrationCode = "TARGET_TASK_NOT_FOUND"
            )
        )

        assertEquals(OverlayMode.RECOVERY, state.mode)
        assertTrue(state.alreadyOnMain)
        assertEquals("返回空闲", state.primaryLabel)
        assertTrue(state.message.orEmpty().contains("无法恢复到原任务"))
    }

    @Test
    fun mainDisplayWaitingUserStillUsesNeedUserWithoutMigrationAction() {
        val state = mapper.map(
            baseSession(
                displayMode = SessionDisplayMode.MAIN_DISPLAY,
                executionDisplayId = 0,
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
        assertEquals(NewTaskEntryState.HIDDEN, state.newTaskEntryState)
    }

    @Test
    fun completionConfirmationBeforeMigrationKeepsTaskInBackgroundAndShowsReplyFlow() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.WAITING_USER,
                waitingQuestion = "模型判断任务可能已完成，但当前界面仍需你确认。",
                waitingUserDisposition = WaitingUserDisposition.COMPLETION_CONFIRMATION
            )
        )

        assertEquals(OverlayMode.NEED_USER, state.mode)
        assertTrue(state.requiresReplyInput)
        assertEquals("继续后台", state.primaryLabel)
        assertEquals("迁回主屏", state.secondaryLabel)
        assertFalse(state.alreadyOnMain)
    }

    @Test
    fun recoverableConflictKeepsOriginalPrimaryAction() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.FAILED,
                recoveryNeeded = true,
                lastMigrationCode = "USER_OWNS_MAIN_INSTANCE"
            )
        )

        assertEquals(OverlayMode.RECOVERY, state.mode)
        assertEquals("去主屏处理", state.primaryLabel)
    }

    @Test
    fun completedCleanupImmediateMapsToTerminalPostCompletionUi() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.COMPLETED,
                postCompletionDisposition = PostCompletionDisposition.CLEANUP_IMMEDIATELY,
                postCompletionState = PostCompletionState.SUCCEEDED,
                postCompletionResultCode = "COMPLETION_CLEANUP_READY"
            )
        )

        assertEquals(OverlayMode.POST_COMPLETION, state.mode)
        assertEquals("已完成", state.bubbleText)
        assertEquals("会话已清理", state.bubbleDetail)
        assertEquals("知道了", state.primaryLabel)
        assertTrue(state.message.orEmpty().contains("后台会话已清理"))
    }

    @Test
    fun completedReturnToMainSuccessMapsToMainRevealUi() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.COMPLETED,
                migratedToMain = true,
                currentVisibleDisplayId = 0,
                visibleToUser = true,
                postCompletionDisposition = PostCompletionDisposition.RETURN_TASK_TO_MAIN,
                postCompletionState = PostCompletionState.SUCCEEDED,
                postCompletionResultCode = "TASK_MIGRATION_OK"
            )
        )

        assertEquals(OverlayMode.POST_COMPLETION, state.mode)
        assertEquals("已回主屏", state.bubbleDetail)
        assertEquals("查看主屏", state.primaryLabel)
        assertTrue(state.alreadyOnMain)
        assertTrue(state.message.orEmpty().contains("已迁回主屏"))
    }

    @Test
    fun completedReturnToMainFallbackUsesTerminalFailureCopy() {
        val state = mapper.map(
            baseSession(
                status = SessionStatus.COMPLETED,
                recoveryNeeded = false,
                lastMigrationCode = "TARGET_TASK_NOT_FOUND",
                postCompletionDisposition = PostCompletionDisposition.RETURN_TASK_TO_MAIN,
                postCompletionState = PostCompletionState.FALLBACK_CLEANUP,
                postCompletionResultCode = "TARGET_TASK_NOT_FOUND"
            )
        )

        assertEquals(OverlayMode.POST_COMPLETION, state.mode)
        assertEquals("已安全结束", state.bubbleDetail)
        assertEquals("知道了", state.primaryLabel)
        assertTrue(state.message.orEmpty().contains("未能将目标任务迁回主屏"))
        assertTrue(state.message.orEmpty().contains("失败码: TARGET_TASK_NOT_FOUND"))
        assertFalse(state.message.orEmpty().contains("恢复"))
    }

    @Test
    fun modeNameUsesSameCanonicalMappingAsRenderedUiState() {
        val sessions = listOf(
            null,
            baseSession(status = SessionStatus.RUNNING),
            baseSession(status = SessionStatus.WAITING_USER, waitingQuestion = "请输入验证码"),
            baseSession(
                status = SessionStatus.WAITING_USER,
                waitingQuestion = "请输入验证码",
                migratedToMain = true,
                currentVisibleDisplayId = 0,
                lastMigrationCode = "TASK_MIGRATION_OK"
            ),
            baseSession(
                status = SessionStatus.FAILED,
                recoveryNeeded = true,
                lastMigrationCode = "TASK_MOVE_BACK_CONFLICT"
            ),
            baseSession(
                status = SessionStatus.COMPLETED,
                postCompletionDisposition = PostCompletionDisposition.CLEANUP_IMMEDIATELY,
                postCompletionState = PostCompletionState.SUCCEEDED,
                postCompletionResultCode = "COMPLETION_CLEANUP_READY"
            )
        )

        sessions.forEach { session ->
            assertEquals(mapper.map(session).mode.name, mapper.modeName(session))
        }
    }

    private fun baseSession(
        displayMode: SessionDisplayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
        executionDisplayId: Int = 9,
        currentVisibleDisplayId: Int? = executionDisplayId,
        virtualDisplayId: Int? = executionDisplayId,
        surfaceDisplayId: Int? = null,
        status: SessionStatus = SessionStatus.RUNNING,
        waitingQuestion: String? = null,
        waitingUserDisposition: WaitingUserDisposition = WaitingUserDisposition.STANDARD_HANDOFF,
        migratedToMain: Boolean = false,
        visibleToUser: Boolean = displayMode == SessionDisplayMode.MAIN_DISPLAY || migratedToMain,
        recoveryNeeded: Boolean = false,
        lastMigrationCode: String? = null,
        postCompletionDisposition: PostCompletionDisposition = PostCompletionDisposition.NONE,
        postCompletionState: PostCompletionState = PostCompletionState.NONE,
        postCompletionResultCode: String? = null
    ): SessionState {
        return SessionState(
            sessionId = "session-1",
            command = "demo",
            executionDisplayId = executionDisplayId,
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
            waitingUserDisposition = waitingUserDisposition,
            automationStep = 2,
            displayMode = displayMode,
            visibleToUser = visibleToUser,
            backgroundPreferred = displayMode != SessionDisplayMode.MAIN_DISPLAY,
            returnToVirtualAfterUser = true,
            completionReturnPolicy = CompletionReturnPolicy.CURRENT_BEHAVIOR,
            migratedToMain = migratedToMain,
            lastMigrationCode = lastMigrationCode,
            lastMigrationDirection = if (migratedToMain) "TO_MAIN" else null,
            recoveryNeeded = recoveryNeeded,
            bootstrapTarget = SessionBootstrapTarget.NONE,
            lastBootstrapCode = null,
            postCompletionDisposition = postCompletionDisposition,
            postCompletionState = postCompletionState,
            postCompletionResultCode = postCompletionResultCode
        )
    }
}
