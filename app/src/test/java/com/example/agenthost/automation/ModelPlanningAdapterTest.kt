package com.example.agenthost.automation

import com.example.agenthost.model.FakeModelClient
import com.example.agenthost.model.ModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPlanningAdapterTest {
    @Test
    fun plansClickFromValidModelOutput() {
        val adapter = adapterForSuccess("""{"action":"CLICK","x":540,"y":960}""")

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlannedAction)
        assertEquals("CLICK", (result as PlanResult.PlannedAction).action.actionType())
    }

    @Test
    fun plansAskUserFromValidModelOutput() {
        val adapter = adapterForSuccess("""{"action":"ASK_USER","question":"请输入验证码"}""")

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlannedAction)
        assertEquals("ASK_USER", (result as PlanResult.PlannedAction).action.actionType())
    }

    @Test
    fun plansDoneFromValidModelOutput() {
        val adapter = adapterForSuccess("""{"action":"DONE","reason":"task complete"}""")

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlannedAction)
        assertEquals("DONE", (result as PlanResult.PlannedAction).action.actionType())
    }

    @Test
    fun rejectsHomeAsUnsupportedAction() {
        val adapter = adapterForSuccess("""{"action":"HOME"}""")

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_OUTPUT_UNSUPPORTED_ACTION", (result as PlanResult.PlanFailure).code)
    }

    @Test
    fun rejectsArrayAsInvalidOutput() {
        val adapter = adapterForSuccess("""[{"action":"CLICK","x":1,"y":2}]""")

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_OUTPUT_INVALID", (result as PlanResult.PlanFailure).code)
    }

    @Test
    fun returnsTimeoutWhenModelClientTimesOut() {
        val adapter = ModelPlanningAdapter(
            mode = PlanningMode.MODEL,
            modelClient = FakeModelClient {
                ModelResponse.Failure(code = "MODEL_TIMEOUT", message = "timeout")
            }
        )

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_TIMEOUT", (result as PlanResult.PlanFailure).code)
    }

    @Test
    fun returnsUnavailableWhenModelClientIsNotConfigured() {
        val adapter = ModelPlanningAdapter(
            mode = PlanningMode.MODEL,
            modelClient = FakeModelClient {
                ModelResponse.Failure(code = "MODEL_UNAVAILABLE", message = "not configured")
            }
        )

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_UNAVAILABLE", (result as PlanResult.PlanFailure).code)
    }

    @Test
    fun rejectsEmojiWhenOnlyAsciiBasicIsSupported() {
        val adapter = adapterForSuccess("""{"action":"TYPE_TEXT","text":"hello🙂"}""")

        val result = adapter.planNext(
            buildRequest(
                capabilityProfile = CapabilityProfile.phase3PlatformReady(true)
            )
        )

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_OUTPUT_UNSUPPORTED_ACTION", (result as PlanResult.PlanFailure).code)
    }

    private fun adapterForSuccess(outputText: String): ModelPlanningAdapter {
        return ModelPlanningAdapter(
            mode = PlanningMode.MODEL,
            modelClient = FakeModelClient {
                ModelResponse.Success(outputText)
            }
        )
    }

    private fun buildRequest(
        capabilityProfile: CapabilityProfile = CapabilityProfile.phase3PlatformReady(true)
    ): PlanningRequest {
        return PlanningRequest(
            sessionId = "session-1",
            command = "open settings",
            step = 1,
            executionDisplayId = 42,
            sessionTaskId = 314,
            planningMode = PlanningMode.MODEL,
            capabilityProfile = capabilityProfile,
            observation = ObservationResult.Failed("CAPTURE_FAILED", "no screenshot"),
            recentActions = emptyList(),
            lastFailureCode = null,
            lastUserReply = null
        )
    }
}
