package com.example.agenthost.automation

import com.example.agenthost.model.FakeModelClient
import com.example.agenthost.model.ModelRequest
import com.example.agenthost.model.ModelResponse
import com.example.agenthost.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun clearSessionDuringCaptureStopsBeforePlanningNextStep() {
        val history = AutomationHistory()
        val callbacks = TestCallbacks(sessionId = "s3", command = "tap the center once")
        val captureStarted = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val planCalls = AtomicInteger(0)
        val planner = object : PlanningAdapter {
            override fun modeForSession(sessionId: String): PlanningMode = PlanningMode.MODEL

            override fun planNext(request: PlanningRequest): PlanResult {
                planCalls.incrementAndGet()
                return PlanResult.PlanFailure(
                    code = "PLAN_SHOULD_NOT_RUN",
                    message = "planner invoked after clearSession"
                )
            }

            override fun clearSession(sessionId: String) = Unit
        }
        val observationProvider = object : ObservationProvider {
            override fun capture(request: ObservationRequest): ObservationResult {
                captureStarted.countDown()
                releaseCapture.await(2, TimeUnit.SECONDS)
                return ObservationResult.Captured(
                    base64Png = "",
                    width = 1080,
                    height = 1920
                )
            }
        }
        val engine = SessionAutomationEngine(
            callbacks = callbacks,
            observationProvider = observationProvider,
            planningAdapter = planner,
            actionExecutor = FakeExecutor(),
            history = history,
            maxSteps = 5,
            maxActionRetries = 0
        )

        assertTrue(engine.startAutomation("s3").ok)
        assertTrue(captureStarted.await(2, TimeUnit.SECONDS))

        engine.clearSession("s3")
        releaseCapture.countDown()

        Thread.sleep(300)
        assertEquals(0, planCalls.get())
        assertFalse(callbacks.completedLatch.await(100, TimeUnit.MILLISECONDS))
        assertFalse(callbacks.failedLatch.await(100, TimeUnit.MILLISECONDS))
        assertNull(callbacks.waitingQuestion)
    }

    @Test
    fun nextSessionClearsStaleInterruptedWorkerState() {
        val history = AutomationHistory()
        val callbacks = MultiSessionCallbacks(
            snapshots = linkedMapOf(
                "s1" to baseSnapshot("s1", "click once"),
                "s2" to baseSnapshot("s2", "ask for verification code")
            )
        )
        val captureStarted = CountDownLatch(1)
        val planner = object : PlanningAdapter {
            override fun modeForSession(sessionId: String): PlanningMode = PlanningMode.MODEL

            override fun planNext(request: PlanningRequest): PlanResult {
                return when (request.sessionId) {
                    "s2" -> {
                        when (request.observation) {
                            is ObservationResult.Captured -> PlanResult.PlannedAction(
                                GuiAction.AskUser("verification code?")
                            )

                            is ObservationResult.Failed -> PlanResult.PlanFailure(
                                code = request.observation.code,
                                message = request.observation.message
                            )
                        }
                    }

                    else -> PlanResult.PlanFailure(
                        code = "PLAN_SHOULD_NOT_RUN",
                        message = "unexpected planner invocation"
                    )
                }
            }

            override fun clearSession(sessionId: String) = Unit
        }
        val observationProvider = object : ObservationProvider {
            override fun capture(request: ObservationRequest): ObservationResult {
                return when (request.sessionId) {
                    "s1" -> {
                        captureStarted.countDown()
                        try {
                            Thread.sleep(5_000)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return ObservationResult.Failed(
                                code = "STOPPED",
                                message = "capture interrupted"
                            )
                        }
                        ObservationResult.Captured(base64Png = "", width = 1080, height = 1920)
                    }

                    "s2" -> {
                        if (Thread.currentThread().isInterrupted) {
                            ObservationResult.Failed(
                                code = "STALE_INTERRUPT",
                                message = "worker interrupt flag leaked across sessions"
                            )
                        } else {
                            ObservationResult.Captured(base64Png = "", width = 1080, height = 1920)
                        }
                    }

                    else -> ObservationResult.Failed(code = "UNKNOWN_SESSION", message = request.sessionId)
                }
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val engine = SessionAutomationEngine(
            callbacks = callbacks,
            observationProvider = observationProvider,
            planningAdapter = planner,
            actionExecutor = FakeExecutor(),
            history = history,
            maxSteps = 5,
            maxActionRetries = 0,
            worker = worker
        )
        try {
            assertTrue(engine.startAutomation("s1").ok)
            assertTrue(captureStarted.await(2, TimeUnit.SECONDS))
            assertTrue(engine.stopAutomation("s1").ok)

            callbacks.markMissing("s1")
            assertTrue(engine.startAutomation("s2").ok)
            assertTrue(callbacks.waitingLatch.await(2, TimeUnit.SECONDS))
            assertEquals(SessionStatus.WAITING_USER, callbacks.status("s2"))
        } finally {
            worker.shutdownNow()
        }
    }

    private fun baseSnapshot(sessionId: String, command: String): AutomationSessionSnapshot {
        return AutomationSessionSnapshot(
            sessionId = sessionId,
            command = command,
            executionDisplayId = 9,
            virtualDisplayId = 9,
            surfaceDisplayId = null,
            sessionTaskId = 21,
            status = SessionStatus.RUNNING,
            runtimeHandle = null
        )
    }

    private class TestCallbacks(
        sessionId: String,
        command: String
    ) : AutomationStateCallbacks {
        private val lock = Any()
        private var snapshot = AutomationSessionSnapshot(
            sessionId = sessionId,
            command = command,
            executionDisplayId = 9,
            virtualDisplayId = 9,
            surfaceDisplayId = null,
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

    private class MultiSessionCallbacks(
        snapshots: Map<String, AutomationSessionSnapshot>
    ) : AutomationStateCallbacks {
        private val lock = Any()
        private val snapshots = snapshots.toMutableMap()

        val waitingLatch = CountDownLatch(1)

        override fun getSnapshot(sessionId: String): AutomationSessionSnapshot? = synchronized(lock) {
            snapshots[sessionId]
        }

        override fun onSessionWaitingUser(sessionId: String, step: Int, question: String) {
            synchronized(lock) {
                val snapshot = snapshots[sessionId] ?: return
                snapshots[sessionId] = snapshot.copy(status = SessionStatus.WAITING_USER)
            }
            waitingLatch.countDown()
        }

        override fun onSessionCompleted(sessionId: String, step: Int, reason: String) {
            synchronized(lock) {
                val snapshot = snapshots[sessionId] ?: return
                snapshots[sessionId] = snapshot.copy(status = SessionStatus.COMPLETED)
            }
        }

        override fun onSessionFailed(sessionId: String, step: Int, code: String, details: String) {
            synchronized(lock) {
                val snapshot = snapshots[sessionId] ?: return
                snapshots[sessionId] = snapshot.copy(status = SessionStatus.FAILED)
            }
        }

        fun markMissing(sessionId: String) {
            synchronized(lock) {
                snapshots.remove(sessionId)
            }
        }

        fun status(sessionId: String): SessionStatus? = synchronized(lock) {
            snapshots[sessionId]?.status
        }
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
