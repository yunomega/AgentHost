package com.example.agenthost.automation

import com.example.agenthost.model.FakeModelClient
import com.example.agenthost.model.ModelRequest
import com.example.agenthost.model.ModelResponse
import com.example.agenthost.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionAutomationEngineModelModeTest {
    @Test
    fun modelModeSupportsWaitingUserReplyAndCompleteFlow() {
        val history = AutomationHistory()
        val callbacks = TestCallbacks(sessionId = "s1", command = "enter verification code")
        val planner = ModelPlanningAdapter(
            mode = PlanningMode.MODEL,
            modelClient = FakeModelClient { request: ModelRequest ->
                if (request.context.lastUserReply.isNullOrBlank()) {
                    ModelResponse.Success("""{"action":"ASK_USER","question":"请输入验证码"}""")
                } else {
                    ModelResponse.Success("""{"action":"DONE","reason":"task complete"}""")
                }
            }
        )
        val engine = SessionAutomationEngine(
            callbacks = callbacks,
            observationProvider = FailedObservationProvider,
            planningAdapter = planner,
            actionExecutor = FakeExecutor(),
            history = history,
            maxSteps = 5,
            maxActionRetries = 0
        )

        assertTrue(engine.startAutomation("s1").ok)
        assertTrue(callbacks.waitingLatch.await(2, TimeUnit.SECONDS))
        assertEquals(SessionStatus.WAITING_USER, callbacks.status())
        assertEquals("请输入验证码", callbacks.waitingQuestion)

        callbacks.markRunning()
        assertTrue(engine.replyToSession("s1", "123456").ok)
        assertTrue(callbacks.completedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(SessionStatus.COMPLETED, callbacks.status())
    }

    @Test
    fun stopAutomationInterruptsModelModeLoop() {
        val history = AutomationHistory()
        val callbacks = TestCallbacks(sessionId = "s2", command = "wait here")
        val plannerEntered = CountDownLatch(1)
        val planner = ModelPlanningAdapter(
            mode = PlanningMode.MODEL,
            modelClient = FakeModelClient {
                plannerEntered.countDown()
                ModelResponse.Success("""{"action":"WAIT","durationMs":5000}""")
            }
        )
        val engine = SessionAutomationEngine(
            callbacks = callbacks,
            observationProvider = FailedObservationProvider,
            planningAdapter = planner,
            actionExecutor = FakeExecutor(),
            history = history,
            maxSteps = 5,
            maxActionRetries = 0
        )

        assertTrue(engine.startAutomation("s2").ok)
        assertTrue(plannerEntered.await(2, TimeUnit.SECONDS))

        val stop = engine.stopAutomation("s2")
        assertTrue(stop.ok)
        assertEquals("AUTOMATION_STOP_REQUESTED", stop.code)

        Thread.sleep(300)
        assertFalse(callbacks.completedLatch.await(100, TimeUnit.MILLISECONDS))
        assertFalse(callbacks.failedLatch.await(100, TimeUnit.MILLISECONDS))

        callbacks.markRunning()
        val restart = engine.startAutomation("s2")
        assertTrue(restart.ok)
        engine.stopAutomation("s2")
    }

    private class TestCallbacks(
        sessionId: String,
        command: String
    ) : AutomationStateCallbacks {
        private val lock = Any()
        private var snapshot = AutomationSessionSnapshot(
            sessionId = sessionId,
            command = command,
            vdDisplayId = 9,
            sessionTaskId = 21,
            status = SessionStatus.RUNNING,
            runtimeHandle = null
        )

        val waitingLatch = CountDownLatch(1)
        val completedLatch = CountDownLatch(1)
        val failedLatch = CountDownLatch(1)
        @Volatile var waitingQuestion: String? = null

        override fun getSnapshot(sessionId: String): AutomationSessionSnapshot? = synchronized(lock) {
            if (snapshot.sessionId == sessionId) snapshot else null
        }

        override fun onSessionWaitingUser(sessionId: String, step: Int, question: String) {
            synchronized(lock) {
                waitingQuestion = question
                snapshot = snapshot.copy(status = SessionStatus.WAITING_USER)
            }
            waitingLatch.countDown()
        }

        override fun onSessionCompleted(sessionId: String, step: Int, reason: String) {
            synchronized(lock) {
                snapshot = snapshot.copy(status = SessionStatus.COMPLETED)
            }
            completedLatch.countDown()
        }

        override fun onSessionFailed(sessionId: String, step: Int, code: String, details: String) {
            synchronized(lock) {
                snapshot = snapshot.copy(status = SessionStatus.FAILED)
            }
            failedLatch.countDown()
        }

        fun markRunning() {
            synchronized(lock) {
                snapshot = snapshot.copy(status = SessionStatus.RUNNING)
            }
        }

        fun status(): SessionStatus = synchronized(lock) { snapshot.status }
    }

    private class FakeExecutor : DisplayActionExecutor {
        override fun capabilities(): ExecutorCapabilities {
            return ExecutorCapabilities(
                supported = true,
                displayFlag = null,
                code = "PLATFORM_EXECUTOR_READY",
                details = "service=agenthost_platform"
            )
        }

        override fun execute(request: ActionExecutionRequest): ActionExecutionResult {
            return ActionExecutionResult(ok = true, code = "OK", details = "ok")
        }
    }

    private object FailedObservationProvider : ObservationProvider {
        override fun capture(request: ObservationRequest): ObservationResult {
            return ObservationResult.Failed(code = "CAPTURE_FAILED", message = "no screenshot")
        }
    }
}
