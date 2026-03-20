package com.example.agenthost.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardrailEvaluatorTest {
    @Test
    fun doneOnlyPhaseReplacesClickWithSyntheticDone() {
        val evaluator = GuardrailEvaluator()
        val decision = evaluator.evaluatePreExecution(
            step = 3,
            state = GuardrailState(
                subgoalState = SubgoalState(
                    spec = null,
                    phase = SubgoalPhase.DONE_ONLY
                )
            ),
            beforeObservation = captured("ZmFrZS1h"),
            action = CanonicalAction(
                type = CanonicalActionType.CLICK,
                coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
                point = CanonicalPoint(540, 960)
            )
        )

        assertEquals(GuardrailDecisionType.BLOCK_TERMINAL, decision.type)
        assertEquals("POST_TARGET_GATE", decision.rule)
        assertEquals(CanonicalActionType.DONE, decision.finalCanonicalAction.type)
    }

    @Test
    fun repeatedNearbyClicksWithoutProgressAreBlocked() {
        val coordinator = AgentControlCoordinator(
            config = GuardrailConfig(repeatClickBlockThreshold = 3)
        )
        val action = CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(200, 300)
        )
        val before = captured("ZmFrZS1h")
        val after = captured("ZmFrZS1h")

        repeat(2) { index ->
            val resolution = coordinator.resolveAction(
                sessionId = "repeat-session",
                step = index + 1,
                command = "点击一次应用选项，点开后再点击一次语燕输入法",
                beforeObservation = before,
                action = action
            )
            assertEquals(GuardrailDecisionType.ALLOW, resolution.guardrailDecision.type)
            coordinator.finalizeStep(
                sessionId = "repeat-session",
                step = index + 1,
                command = "点击一次应用选项，点开后再点击一次语燕输入法",
                beforeObservation = before,
                afterObservation = after,
                resolution = resolution,
                executionResult = ActionExecutionResult(ok = true, code = "OK", details = "no change"),
                controlSyntheticCode = null,
                controlSyntheticDetails = null
            )
        }

        val blocked = coordinator.resolveAction(
            sessionId = "repeat-session",
            step = 3,
            command = "点击一次应用选项，点开后再点击一次语燕输入法",
            beforeObservation = before,
            action = action
        )

        assertEquals(GuardrailDecisionType.BLOCK_REPLACE, blocked.guardrailDecision.type)
        assertEquals("REPEAT_CLICK_NEARBY_BLOCK", blocked.guardrailDecision.rule)
        assertEquals("WAIT", blocked.finalAction.actionType())
    }

    @Test
    fun doneWithCapturedObservationRequiresUserConfirmationWhenOnlyUnsure() {
        val coordinator = AgentControlCoordinator()
        val before = captured("ZmFrZS1h")
        val resolution = coordinator.resolveAction(
            sessionId = "done-confirm-session",
            step = 1,
            command = "点开搜索设置栏，然后等我输入",
            beforeObservation = before,
            action = CanonicalAction(
                type = CanonicalActionType.DONE,
                reason = "search bar looks ready"
            )
        )

        val post = coordinator.finalizeStep(
            sessionId = "done-confirm-session",
            step = 1,
            command = "点开搜索设置栏，然后等我输入",
            beforeObservation = before,
            afterObservation = null,
            resolution = resolution,
            executionResult = null,
            controlSyntheticCode = "DONE",
            controlSyntheticDetails = "search bar looks ready"
        )

        assertEquals(CompletionVerdict.UNSURE, post.completionVerdict)
        assertFalse(post.requestSessionComplete)
        assertTrue(post.requestUserHandoff)
        assertTrue(post.completionConfirmationPending)
        assertEquals("模型判断任务可能已完成，但当前界面仍需你确认。", post.handoffQuestion)
    }

    @Test
    fun doneWithoutCapturedObservationCanStillComplete() {
        val coordinator = AgentControlCoordinator()
        val before = ObservationResult.Failed("CAPTURE_FAILED", "no screenshot")
        val resolution = coordinator.resolveAction(
            sessionId = "done-no-visual-session",
            step = 1,
            command = "enter verification code",
            beforeObservation = before,
            action = CanonicalAction(
                type = CanonicalActionType.DONE,
                reason = "task complete"
            )
        )

        val post = coordinator.finalizeStep(
            sessionId = "done-no-visual-session",
            step = 1,
            command = "enter verification code",
            beforeObservation = before,
            afterObservation = null,
            resolution = resolution,
            executionResult = null,
            controlSyntheticCode = "DONE",
            controlSyntheticDetails = "task complete"
        )

        assertTrue(post.requestSessionComplete)
        assertFalse(post.requestUserHandoff)
        assertFalse(post.completionConfirmationPending)
    }

    @Test
    fun recentlyLocallyConfirmedToggleClickIsSuppressedOnSamePoint() {
        val coordinator = AgentControlCoordinator(
            config = GuardrailConfig(toggleRepeatSuppressionWindow = 1)
        )
        val before = captured("ZmFrZS1h")
        val action = CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(960, 570)
        )

        val resolution = coordinator.resolveAction(
            sessionId = "toggle-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            action = action
        )
        coordinator.finalizeStep(
            sessionId = "toggle-session",
            step = 1,
            command = "关闭显示密码",
            beforeObservation = before,
            afterObservation = before,
            resolution = resolution,
            executionResult = ActionExecutionResult(ok = true, code = "OK", details = "local roi confirmed"),
            controlSyntheticCode = null,
            controlSyntheticDetails = null,
            localChangeConfirmedPoint = CanonicalPoint(960, 570)
        )

        val blocked = coordinator.resolveAction(
            sessionId = "toggle-session",
            step = 2,
            command = "关闭显示密码",
            beforeObservation = before,
            action = action
        )

        assertEquals(GuardrailDecisionType.BLOCK_REPLACE, blocked.guardrailDecision.type)
        assertEquals("TOGGLE_REPEAT_CLICK_SUPPRESSED", blocked.guardrailDecision.rule)
        assertEquals("WAIT", blocked.finalAction.actionType())
    }

    private fun captured(base64: String): ObservationResult.Captured {
        return ObservationResult.Captured(
            base64Png = base64,
            width = 1080,
            height = 1920
        )
    }
}
