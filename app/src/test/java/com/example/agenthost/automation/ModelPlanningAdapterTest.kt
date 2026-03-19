package com.example.agenthost.automation

import com.example.agenthost.model.FakeModelClient
import com.example.agenthost.model.ModelRequest
import com.example.agenthost.model.ModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun targetLockBlocksBackAfterFirstClickOnSequentialCommand() {
        val adapter = adapterForSuccess("""{"action":"BACK"}""")

        val result = adapter.planNext(
            buildRequest(
                controlSnapshot = controlSnapshot(
                    currentPhase = "NEED_CLICK_TARGET",
                    allowedActions = listOf("ASK_USER", "CLICK", "DONE", "SWIPE"),
                    blockedActions = listOf("BACK", "HOME")
                )
            )
        )

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_OUTPUT_PHASE_BLOCKED", (result as PlanResult.PlanFailure).code)
    }

    @Test
    fun targetLockBlocksHomeBeforeCapabilityValidation() {
        val adapter = adapterForSuccess("""{"action":"HOME"}""")

        val result = adapter.planNext(
            buildRequest(
                controlSnapshot = controlSnapshot(
                    currentPhase = "NEED_CLICK_TARGET",
                    allowedActions = listOf("ASK_USER", "CLICK", "DONE", "SWIPE"),
                    blockedActions = listOf("BACK", "HOME")
                )
            )
        )

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_OUTPUT_PHASE_BLOCKED", (result as PlanResult.PlanFailure).code)
    }

    @Test
    fun targetLockStillAllowsClickSwipeDoneAndAskUser() {
        val snapshot = controlSnapshot(
            currentPhase = "NEED_CLICK_TARGET",
            allowedActions = listOf("ASK_USER", "CLICK", "DONE", "SWIPE"),
            blockedActions = listOf("BACK", "HOME")
        )

        assertPlannedAction(
            outputText = """{"action":"CLICK","x":500,"y":600}""",
            controlSnapshot = snapshot,
            expectedActionType = "CLICK"
        )
        assertPlannedAction(
            outputText = """{"action":"SWIPE","x1":500,"y1":1500,"x2":500,"y2":400,"durationMs":300}""",
            controlSnapshot = snapshot,
            expectedActionType = "SWIPE"
        )
        assertPlannedAction(
            outputText = """{"action":"DONE","reason":"target reached"}""",
            controlSnapshot = snapshot,
            expectedActionType = "DONE"
        )
        assertPlannedAction(
            outputText = """{"action":"ASK_USER","question":"需要确认是否继续吗"}""",
            controlSnapshot = snapshot,
            expectedActionType = "ASK_USER"
        )
    }

    @Test
    fun doneOnlyBlocksClickViaControlSnapshot() {
        val adapter = adapterForSuccess("""{"action":"CLICK","x":540,"y":960}""")

        val result = adapter.planNext(
            buildRequest(
                controlSnapshot = controlSnapshot(
                    currentPhase = "DONE_ONLY",
                    allowedActions = listOf("ASK_USER", "DONE"),
                    blockedActions = listOf("BACK", "CLICK", "HOME", "SWIPE"),
                    doneOnly = true,
                    completionState = "SATISFIED"
                )
            )
        )

        assertTrue(result is PlanResult.PlanFailure)
        assertEquals("MODEL_OUTPUT_PHASE_BLOCKED", (result as PlanResult.PlanFailure).code)
    }

    @Test
    fun legacyAndroidPromptFieldsAreDebugOnlySummaries() {
        var capturedRequest: ModelRequest? = null
        val adapter = ModelPlanningAdapter(
            mode = PlanningMode.MODEL,
            modelClient = FakeModelClient { request ->
                capturedRequest = request
                ModelResponse.Success("""{"action":"DONE","reason":"ok"}""")
            }
        )

        val result = adapter.planNext(buildRequest())

        assertTrue(result is PlanResult.PlannedAction)
        val request = requireNotNull(capturedRequest)
        assertTrue(request.systemPrompt.startsWith("DEBUG_ONLY_ANDROID_SYSTEM_PROMPT"))
        assertTrue(request.userPrompt.startsWith("DEBUG_ONLY_ANDROID_USER_PROMPT"))
        assertFalse(request.systemPrompt.contains("Return exactly one JSON object"))
        assertFalse(request.userPrompt.contains("Return exactly one JSON object"))
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
        command: String = "open settings",
        recentActions: List<HistoryActionRecord> = emptyList(),
        capabilityProfile: CapabilityProfile = CapabilityProfile.phase3PlatformReady(true),
        controlSnapshot: AgentControlSnapshot? = null
    ): PlanningRequest {
        return PlanningRequest(
            sessionId = "session-1",
            command = command,
            step = 1,
            executionDisplayId = 42,
            sessionTaskId = 314,
            planningMode = PlanningMode.MODEL,
            capabilityProfile = capabilityProfile,
            observation = ObservationResult.Failed("CAPTURE_FAILED", "no screenshot"),
            recentActions = recentActions,
            lastFailureCode = null,
            lastUserReply = null,
            controlSnapshot = controlSnapshot
        )
    }

    private fun successfulClickRecord(): HistoryActionRecord {
        return HistoryActionRecord(
            step = 1,
            actionType = "CLICK",
            code = "OK",
            summary = "tap parent entry",
            timestampMs = 1L
        )
    }

    private fun assertPlannedAction(
        outputText: String,
        controlSnapshot: AgentControlSnapshot,
        expectedActionType: String
    ) {
        val adapter = adapterForSuccess(outputText)
        val result = adapter.planNext(
            buildRequest(
                controlSnapshot = controlSnapshot
            )
        )

        assertTrue(result is PlanResult.PlannedAction)
        assertEquals(expectedActionType, (result as PlanResult.PlannedAction).action.actionType())
    }

    private fun controlSnapshot(
        currentPhase: String,
        allowedActions: List<String>,
        blockedActions: List<String>,
        doneOnly: Boolean = false,
        completionState: String = "NOT_SATISFIED"
    ): AgentControlSnapshot {
        return AgentControlSnapshot(
            globalTask = "点击一次应用选项，点开后再点击一次语燕输入法",
            currentPhase = currentPhase,
            parentHint = "应用选项",
            targetHint = "语燕输入法",
            allowedActions = allowedActions,
            blockedActions = blockedActions,
            recentActions = listOf(
                AgentControlRecentAction(
                    step = 1,
                    actionType = "CLICK",
                    resultCategory = "SUCCESS_CHANGED",
                    summary = "tap parent entry",
                    approxPoint = CanonicalPoint(400, 600),
                    phaseAfter = currentPhase
                )
            ),
            lastResult = "SUCCESS_CHANGED",
            completionState = completionState,
            doneOnly = doneOnly
        )
    }
}
