package com.example.agenthost.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControlCoordinatorTest {
    @Test
    fun askUserProducesCoordinatorHandoffRequestNotSessionCompletion() {
        val coordinator = AgentControlCoordinator()
        val before = ObservationResult.Captured(base64Png = "ZmFrZS1h", width = 1080, height = 1920)
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
        assertFalse(post.requestSessionComplete)
        assertTrue(post.requestUserHandoff)
        assertEquals("连续多步没有明显推进，当前需要你接手确认下一步。", post.handoffQuestion)
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
        assertEquals(CompletionVerdict.NOT_SATISFIED.name, snapshot.completionState)
        assertFalse(snapshot.doneOnly)
        assertTrue(snapshot.allowedActions.contains("ASK_USER"))
        assertTrue(snapshot.allowedActions.contains("DONE"))
    }

    @Test
    fun localRoiConfirmationCountsAsProgressEvenWhenWholeFrameLooksUnchanged() {
        val coordinator = AgentControlCoordinator()
        val before = ObservationResult.Captured(base64Png = "ZmFrZS1h", width = 1080, height = 1920)
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

        assertEquals(StepResultCategory.SUCCESS_CHANGED, post.resultSummary.category)
        assertEquals("LOCAL_ROI_CHANGE_CONFIRMED", post.resultSummary.code)
        assertTrue(post.observationChanged == true)
        assertFalse(post.requestUserHandoff)
    }

    @Test
    fun samePhaseChangedClickArmsToggleSuppressionWithoutExplicitLocalRoi() {
        val coordinator = AgentControlCoordinator()
        val before = ObservationResult.Captured(base64Png = "ZmFrZS1h", width = 1080, height = 1920)
        val after = ObservationResult.Captured(base64Png = "ZmFrZS1i", width = 1080, height = 1920)
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
        val firstPost = coordinator.finalizeStep(
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

        assertEquals(StepResultCategory.SUCCESS_CHANGED, firstPost.resultSummary.category)

        val blocked = coordinator.resolveAction(
            sessionId = "toggle-infer-session",
            step = 2,
            command = "关闭显示密码",
            beforeObservation = after,
            action = action
        )

        assertEquals(GuardrailDecisionType.BLOCK_REPLACE, blocked.guardrailDecision.type)
        assertEquals("TOGGLE_REPEAT_CLICK_SUPPRESSED", blocked.guardrailDecision.rule)
        assertEquals("WAIT", blocked.finalAction.actionType())
    }

    @Test
    fun toggleSuppressionRefreshesWhenSamePointRetryKeepsComing() {
        val coordinator = AgentControlCoordinator(
            config = GuardrailConfig(toggleRepeatSuppressionWindow = 1)
        )
        val before = ObservationResult.Captured(base64Png = "ZmFrZS1h", width = 1080, height = 1920)
        val after = ObservationResult.Captured(base64Png = "ZmFrZS1i", width = 1080, height = 1920)
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
        assertEquals("TOGGLE_REPEAT_CLICK_SUPPRESSED", blockedAtStep2.guardrailDecision.rule)
        coordinator.finalizeStep(
            sessionId = "toggle-refresh-session",
            step = 2,
            command = "关闭显示密码",
            beforeObservation = after,
            afterObservation = after,
            resolution = blockedAtStep2,
            executionResult = null,
            controlSyntheticCode = blockedAtStep2.guardrailDecision.rule,
            controlSyntheticDetails = blockedAtStep2.guardrailDecision.message
        )

        val blockedAtStep3 = coordinator.resolveAction(
            sessionId = "toggle-refresh-session",
            step = 3,
            command = "关闭显示密码",
            beforeObservation = after,
            action = action
        )

        assertEquals(GuardrailDecisionType.BLOCK_REPLACE, blockedAtStep3.guardrailDecision.type)
        assertEquals("TOGGLE_REPEAT_CLICK_SUPPRESSED", blockedAtStep3.guardrailDecision.rule)
        assertEquals("WAIT", blockedAtStep3.finalAction.actionType())
    }
}
