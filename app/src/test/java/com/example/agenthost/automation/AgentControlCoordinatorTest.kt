package com.example.agenthost.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControlCoordinatorTest {
    @Test
    fun askUserProducesCoordinatorHandoffRequestNotSessionCompletion() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val resolution = coordinator.resolveAction(
            sessionId = "handoff-session",
            step = 1,
            command = "需要验证码时问我",
            beforeObservation = before,
            action = CanonicalAction(
                type = CanonicalActionType.ASK_USER,
                question = "请输入验证码"
            )
        )

        val post = coordinator.finalizeStep(
            sessionId = "handoff-session",
            step = 1,
            command = "需要验证码时问我",
            beforeObservation = before,
            afterObservation = null,
            resolution = resolution,
            executionResult = null,
            controlSyntheticCode = "ASK_USER",
            controlSyntheticDetails = "请输入验证码"
        )

        assertEquals(StepResultCategory.LOW_YIELD_HANDOFF, post.resultSummary.category)
        assertEquals(GoalSatisfactionStatus.NEED_USER, post.goalStatus)
        assertFalse(post.requestSessionComplete)
        assertTrue(post.requestUserHandoff)
        assertEquals("请输入验证码", post.handoffQuestion)
    }

    @Test
    fun planningSnapshotRemainsControlOnly() {
        val coordinator = AgentControlCoordinator()

        val snapshot = coordinator.buildPlanningSnapshot(
            sessionId = "planner-session",
            command = "打开后点击 Wi-Fi",
            capabilityProfile = CapabilityProfile.phase3PlatformReady(platformAvailable = true)
        )

        assertEquals(SubgoalPhase.NEED_OPEN_PARENT.name, snapshot.currentPhase)
        assertEquals(GoalSatisfactionStatus.UNSURE.name, snapshot.completionState)
        assertFalse(snapshot.doneOnly)
        assertTrue(snapshot.allowedActions.contains("ASK_USER"))
        assertTrue(snapshot.allowedActions.contains("DONE"))
    }

    @Test
    fun localRoiConfirmationCanSatisfyToggleGoalWithoutModelDone() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val action = CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(960, 570)
        )
        val resolution = coordinator.resolveAction(
            sessionId = "local-roi-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            action = action
        )

        val post = coordinator.finalizeStep(
            sessionId = "local-roi-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            afterObservation = before,
            resolution = resolution,
            executionResult = ActionExecutionResult(ok = true, code = "OK", details = "tap injected"),
            controlSyntheticCode = null,
            controlSyntheticDetails = null,
            localChangeConfirmedPoint = CanonicalPoint(960, 570)
        )

        assertEquals(GoalSatisfactionStatus.SATISFIED, post.goalStatus)
        assertEquals(StepResultCategory.COMPLETION_SATISFIED, post.resultSummary.category)
        assertTrue(post.requestSessionComplete)
        assertTrue(post.observationChanged == true)
        assertFalse(post.requestUserHandoff)
    }

    @Test
    fun openAppLocalRoiWithoutSurfaceEvidenceDoesNotSatisfyGoal() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val action = CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(929, 1766)
        )
        val resolution = coordinator.resolveAction(
            sessionId = "open-app-local-roi-session",
            step = 1,
            command = "打开日历",
            beforeObservation = before,
            action = action,
            surfaceContext = SurfaceContext(
                topPackage = "com.android.launcher3",
                topActivity = "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher"
            )
        )

        val post = coordinator.finalizeStep(
            sessionId = "open-app-local-roi-session",
            step = 1,
            command = "打开日历",
            beforeObservation = before,
            afterObservation = before,
            resolution = resolution,
            executionResult = ActionExecutionResult(ok = true, code = "OK", details = "tap injected"),
            controlSyntheticCode = null,
            controlSyntheticDetails = null,
            localChangeConfirmedPoint = CanonicalPoint(929, 1766),
            surfaceContext = SurfaceContext(
                topPackage = "com.android.launcher3",
                topActivity = "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher"
            )
        )

        assertEquals(GoalSatisfactionStatus.UNSURE, post.goalStatus)
        assertFalse(post.requestSessionComplete)
        assertFalse(post.requestUserHandoff)
    }

    @Test
    fun surfaceMatchCanSatisfyGoalWithoutModelDone() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val after = captured("ZmFrZS1i")
        val resolution = coordinator.resolveAction(
            sessionId = "surface-match-session",
            step = 1,
            command = "open settings",
            beforeObservation = before,
            action = CanonicalAction(
                type = CanonicalActionType.CLICK,
                coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
                point = CanonicalPoint(540, 960)
            ),
            surfaceContext = SurfaceContext(
                topPackage = "launcher",
                topActivity = "launcher.Home"
            )
        )

        val post = coordinator.finalizeStep(
            sessionId = "surface-match-session",
            step = 1,
            command = "open settings",
            beforeObservation = before,
            afterObservation = after,
            resolution = resolution,
            executionResult = ActionExecutionResult(ok = true, code = "OK", details = "tap injected"),
            controlSyntheticCode = null,
            controlSyntheticDetails = null,
            surfaceContext = SurfaceContext(
                topPackage = "com.android.settings",
                topActivity = "com.android.settings.Settings"
            )
        )

        assertEquals(GoalSatisfactionStatus.SATISFIED, post.goalStatus)
        assertTrue(post.requestSessionComplete)
    }

    @Test
    fun openAppLocalRoiWithTargetSurfaceEvidenceCanSatisfyGoal() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val after = captured("ZmFrZS1i")
        val action = CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(929, 1766)
        )
        val resolution = coordinator.resolveAction(
            sessionId = "open-app-surface-match-session",
            step = 1,
            command = "打开日历",
            beforeObservation = before,
            action = action,
            surfaceContext = SurfaceContext(
                topPackage = "com.android.launcher3",
                topActivity = "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher"
            )
        )

        val post = coordinator.finalizeStep(
            sessionId = "open-app-surface-match-session",
            step = 1,
            command = "打开日历",
            beforeObservation = before,
            afterObservation = after,
            resolution = resolution,
            executionResult = ActionExecutionResult(ok = true, code = "OK", details = "tap injected"),
            controlSyntheticCode = null,
            controlSyntheticDetails = null,
            localChangeConfirmedPoint = CanonicalPoint(929, 1766),
            surfaceContext = SurfaceContext(
                topPackage = "com.android.calendar",
                topActivity = "com.android.calendar.AllInOneActivity"
            )
        )

        assertEquals(GoalSatisfactionStatus.SATISFIED, post.goalStatus)
        assertTrue(post.requestSessionComplete)
    }

    @Test
    fun observationChangedNoiseDoesNotForceGoalDecision() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val after = captured("ZmFrZS1i")
        val resolution = coordinator.resolveAction(
            sessionId = "noise-session",
            step = 1,
            command = "scroll the list",
            beforeObservation = before,
            action = CanonicalAction(type = CanonicalActionType.WAIT, durationMs = 500L)
        )

        val post = coordinator.finalizeStep(
            sessionId = "noise-session",
            step = 1,
            command = "scroll the list",
            beforeObservation = before,
            afterObservation = after,
            resolution = resolution,
            executionResult = null,
            controlSyntheticCode = "WAIT_ELAPSED",
            controlSyntheticDetails = "durationMs=500"
        )

        assertEquals(GoalSatisfactionStatus.UNSURE, post.goalStatus)
        assertFalse(post.requestSessionComplete)
        assertFalse(post.requestUserHandoff)
    }

    @Test
    fun samePhaseChangedClickArmsToggleSuppressionWithoutExplicitLocalRoi() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val after = captured("ZmFrZS1i")
        val action = CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(960, 570)
        )

        val firstResolution = coordinator.resolveAction(
            sessionId = "toggle-infer-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            action = action
        )
        coordinator.finalizeStep(
            sessionId = "toggle-infer-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            afterObservation = after,
            resolution = firstResolution,
            executionResult = ActionExecutionResult(ok = true, code = "OK", details = "tap injected"),
            controlSyntheticCode = null,
            controlSyntheticDetails = null
        )

        val blocked = coordinator.resolveAction(
            sessionId = "toggle-infer-session",
            step = 2,
            command = "关闭显示密码",
            beforeObservation = after,
            action = action
        )

        assertEquals(RecoveryDecisionType.OVERRIDE, blocked.recoveryDecision.type)
        assertEquals("TOGGLE_REPEAT_CLICK_SUPPRESSED", blocked.recoveryDecision.rule)
        assertEquals("WAIT", blocked.finalAction.actionType())
    }

    @Test
    fun toggleSuppressionRefreshesWhenSamePointRetryKeepsComing() {
        val coordinator = AgentControlCoordinator(
            config = GuardrailConfig(toggleRepeatSuppressionWindow = 1)
        )
        val before = captured("ZmFrZS1h")
        val after = captured("ZmFrZS1i")
        val action = CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(960, 570)
        )

        val firstResolution = coordinator.resolveAction(
            sessionId = "toggle-refresh-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            action = action
        )
        coordinator.finalizeStep(
            sessionId = "toggle-refresh-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            afterObservation = after,
            resolution = firstResolution,
            executionResult = ActionExecutionResult(ok = true, code = "OK", details = "tap injected"),
            controlSyntheticCode = null,
            controlSyntheticDetails = null
        )

        val blockedAtStep2 = coordinator.resolveAction(
            sessionId = "toggle-refresh-session",
            step = 2,
            command = "关闭显示密码",
            beforeObservation = after,
            action = action
        )
        assertEquals("TOGGLE_REPEAT_CLICK_SUPPRESSED", blockedAtStep2.recoveryDecision.rule)
        coordinator.finalizeStep(
            sessionId = "toggle-refresh-session",
            step = 2,
            command = "关闭显示密码",
            beforeObservation = after,
            afterObservation = after,
            resolution = blockedAtStep2,
            executionResult = null,
            controlSyntheticCode = blockedAtStep2.recoveryDecision.rule,
            controlSyntheticDetails = blockedAtStep2.recoveryDecision.message
        )

        val blockedAtStep3 = coordinator.resolveAction(
            sessionId = "toggle-refresh-session",
            step = 3,
            command = "关闭显示密码",
            beforeObservation = after,
            action = action
        )

        assertEquals(RecoveryDecisionType.OVERRIDE, blockedAtStep3.recoveryDecision.type)
        assertEquals("TOGGLE_REPEAT_CLICK_SUPPRESSED", blockedAtStep3.recoveryDecision.rule)
        assertEquals("WAIT", blockedAtStep3.finalAction.actionType())
    }

    private fun captured(base64: String): ObservationResult.Captured {
        return ObservationResult.Captured(
            base64Png = base64,
            width = 1080,
            height = 1920
        )
    }
}
