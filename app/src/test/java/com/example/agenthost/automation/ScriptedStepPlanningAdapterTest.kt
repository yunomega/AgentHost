package com.example.agenthost.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptedStepPlanningAdapterTest {
    @Test
    fun plansSingleStepEachRound() {
        val history = AutomationHistory()
        val planner = ScriptedStepPlanningAdapter(history)
        val sessionId = "s1"
        val command = """
            {
              "steps": [
                {"action": "CLICK", "x": 10, "y": 20},
                {"action": "SWIPE", "x1": 1, "y1": 2, "x2": 3, "y2": 4, "durationMs": 500},
                {"action": "DONE", "reason": "ok"}
              ]
            }
        """.trimIndent()

        val step1 = planner.planNext(buildRequest(sessionId, command, 1, null))
        assertTrue(step1 is PlanResult.PlannedAction)
        assertEquals("CLICK", (step1 as PlanResult.PlannedAction).action.actionType())

        val step2 = planner.planNext(buildRequest(sessionId, command, 2, null))
        assertTrue(step2 is PlanResult.PlannedAction)
        assertEquals("SWIPE", (step2 as PlanResult.PlannedAction).action.actionType())

        val step3 = planner.planNext(buildRequest(sessionId, command, 3, null))
        assertTrue(step3 is PlanResult.PlannedAction)
        assertEquals("DONE", (step3 as PlanResult.PlannedAction).action.actionType())
    }

    @Test
    fun asksUserWhenTypeTextNeedsReplyAndThenContinues() {
        val history = AutomationHistory()
        val planner = ScriptedStepPlanningAdapter(history)
        val sessionId = "s2"
        val command = """
            {
              "steps": [
                {"action": "TYPE_TEXT", "text": "{{last_user_input}}"},
                {"action": "DONE", "reason": "ok"}
              ]
            }
        """.trimIndent()

        val beforeReply = planner.planNext(buildRequest(sessionId, command, 1, null))
        assertTrue(beforeReply is PlanResult.PlannedAction)
        assertEquals("ASK_USER", (beforeReply as PlanResult.PlannedAction).action.actionType())

        val afterReply = planner.planNext(buildRequest(sessionId, command, 2, "hello"))
        assertTrue(afterReply is PlanResult.PlannedAction)
        assertEquals("TYPE_TEXT", (afterReply as PlanResult.PlannedAction).action.actionType())
        val text = (afterReply.action as GuiAction.TypeText).text
        assertEquals("hello", text)

        val done = planner.planNext(buildRequest(sessionId, command, 3, "hello"))
        assertTrue(done is PlanResult.PlannedAction)
        assertEquals("DONE", (done as PlanResult.PlannedAction).action.actionType())
    }

    private fun buildRequest(
        sessionId: String,
        command: String,
        step: Int,
        lastUserReply: String?
    ): PlanningRequest {
        return PlanningRequest(
            sessionId = sessionId,
            command = command,
            step = step,
            vdDisplayId = 7,
            sessionTaskId = 9,
            planningMode = PlanningMode.SCRIPTED,
            capabilityProfile = CapabilityProfile.phase3PlatformReady(true),
            observation = ObservationResult.Failed("OBS", "n/a"),
            recentActions = emptyList(),
            lastFailureCode = null,
            lastUserReply = lastUserReply
        )
    }
}
