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
}
