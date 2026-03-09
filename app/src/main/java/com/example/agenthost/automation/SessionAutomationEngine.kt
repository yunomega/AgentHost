package com.example.agenthost.automation

import com.example.agenthost.core.AgentHostLog
import com.example.agenthost.runtime.VirtualDisplayRuntime
import com.example.agenthost.session.SessionStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

private data class LoopControl(
    val stopRequested: AtomicBoolean,
    var future: Future<*>?
)

data class EngineResult(
    val ok: Boolean,
    val code: String,
    val details: String = ""
)

data class AutomationSessionSnapshot(
    val sessionId: String,
    val command: String,
    val executionDisplayId: Int,
    val virtualDisplayId: Int?,
    val surfaceDisplayId: Int?,
    val sessionTaskId: Int,
    val status: SessionStatus,
    val runtimeHandle: VirtualDisplayRuntime.RuntimeHandle?
)

interface AutomationStateCallbacks {
    fun getSnapshot(sessionId: String): AutomationSessionSnapshot?

    fun onSessionWaitingUser(sessionId: String, step: Int, question: String)

    fun onSessionCompleted(sessionId: String, step: Int, reason: String)

    fun onSessionFailed(sessionId: String, step: Int, code: String, details: String)
}

class SessionAutomationEngine(
    private val callbacks: AutomationStateCallbacks,
    private val observationProvider: ObservationProvider,
    private val planningAdapter: PlanningAdapter,
    private val actionExecutor: DisplayActionExecutor,
    private val history: AutomationHistory,
    private val maxSteps: Int = 30,
    private val maxActionRetries: Int = 2
) {
    companion object {
        private const val TAG = "SessionAutomationEngine"
    }

    private val worker = Executors.newCachedThreadPool()
    private val loops = ConcurrentHashMap<String, LoopControl>()

    fun startAutomation(sessionId: String): EngineResult {
        val existing = loops[sessionId]
        if (existing?.future?.isDone == false) {
            return EngineResult(false, "AUTOMATION_ALREADY_RUNNING")
        }

        val control = LoopControl(stopRequested = AtomicBoolean(false), future = null)
        return try {
            val future = worker.submit {
                try {
                    runLoop(sessionId, control)
                } finally {
                    loops.remove(sessionId)
                }
            }
            control.future = future
            loops[sessionId] = control
            EngineResult(true, "AUTOMATION_STARTED")
        } catch (t: Throwable) {
            EngineResult(
                ok = false,
                code = "AUTOMATION_START_FAILED",
                details = t.message ?: "submit failed"
            )
        }
    }

    fun replyToSession(sessionId: String, userText: String): EngineResult {
        history.setLastUserReply(sessionId, userText)
        return startAutomation(sessionId)
    }

    fun stopAutomation(sessionId: String): EngineResult {
        val control = loops[sessionId]
            ?: return EngineResult(true, "AUTOMATION_NOT_RUNNING")
        control.stopRequested.set(true)
        control.future?.cancel(true)
        return EngineResult(true, "AUTOMATION_STOP_REQUESTED")
    }

    fun clearSession(sessionId: String) {
        stopAutomation(sessionId)
        planningAdapter.clearSession(sessionId)
        history.clearSession(sessionId)
    }

    private fun runLoop(sessionId: String, control: LoopControl) {
        val bootstrapSnapshot = callbacks.getSnapshot(sessionId)
        val bootstrapExecutionDisplayId = bootstrapSnapshot?.executionDisplayId ?: -1
        val bootstrapVirtualDisplayId = bootstrapSnapshot?.virtualDisplayId
        val bootstrapSurfaceDisplayId = bootstrapSnapshot?.surfaceDisplayId
        val bootstrapTaskId = bootstrapSnapshot?.sessionTaskId ?: -1
        log(
            event = "automation_start",
            sessionId = sessionId,
            executionDisplayId = bootstrapExecutionDisplayId,
            virtualDisplayId = bootstrapVirtualDisplayId,
            surfaceDisplayId = bootstrapSurfaceDisplayId,
            sessionTaskId = bootstrapTaskId,
            step = 0,
            actionType = "NONE",
            code = "AUTOMATION_STARTED"
        )

        val capabilities = actionExecutor.capabilities()
        log(
            event = "executor_probe",
            sessionId = sessionId,
            executionDisplayId = bootstrapExecutionDisplayId,
            virtualDisplayId = bootstrapVirtualDisplayId,
            surfaceDisplayId = bootstrapSurfaceDisplayId,
            sessionTaskId = bootstrapTaskId,
            step = 0,
            actionType = "NONE",
            code = capabilities.code,
            extras = "details=${capabilities.details}"
        )

        var loopCount = 0
        while (loopCount < maxSteps) {
            if (control.stopRequested.get()) {
                log(
                    event = "automation_stopped",
                    sessionId = sessionId,
                    executionDisplayId = bootstrapExecutionDisplayId,
                    virtualDisplayId = bootstrapVirtualDisplayId,
                    surfaceDisplayId = bootstrapSurfaceDisplayId,
                    sessionTaskId = bootstrapTaskId,
                    step = loopCount,
                    actionType = "NONE",
                    code = "STOP_REQUESTED"
                )
                return
            }

            val snapshot = callbacks.getSnapshot(sessionId) ?: run {
                log(
                    event = "automation_stopped",
                    sessionId = sessionId,
                    executionDisplayId = -1,
                    virtualDisplayId = null,
                    surfaceDisplayId = null,
                    sessionTaskId = -1,
                    step = loopCount,
                    actionType = "NONE",
                    code = "SESSION_NOT_FOUND"
                )
                return
            }

            if (snapshot.status != SessionStatus.RUNNING) {
                log(
                    event = "automation_stopped",
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    virtualDisplayId = snapshot.virtualDisplayId,
                    surfaceDisplayId = snapshot.surfaceDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    step = loopCount,
                    actionType = "NONE",
                    code = "SESSION_NOT_RUNNING",
                    extras = "status=${snapshot.status.name}"
                )
                return
            }

            loopCount += 1
            val step = history.claimNextStep(sessionId)
            log(
                event = "automation_step_begin",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "NONE",
                code = "STEP_BEGIN"
            )

            if (shouldAbortStep(control, snapshot, step, actionType = "NONE")) {
                return
            }

            val observation = observationProvider.capture(
                ObservationRequest(
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    imageReader = snapshot.runtimeHandle?.imageReader
                )
            )

            if (shouldAbortStep(control, snapshot, step, actionType = "NONE")) {
                return
            }

            when (observation) {
                is ObservationResult.Captured -> {
                    log(
                        event = "observation_captured",
                        sessionId = snapshot.sessionId,
                        executionDisplayId = snapshot.executionDisplayId,
                        virtualDisplayId = snapshot.virtualDisplayId,
                        surfaceDisplayId = snapshot.surfaceDisplayId,
                        sessionTaskId = snapshot.sessionTaskId,
                        step = step,
                        actionType = "NONE",
                        code = "OBSERVATION_OK",
                        extras = "width=${observation.width} height=${observation.height}"
                    )
                }

                is ObservationResult.Failed -> {
                    log(
                        event = "observation_captured",
                        sessionId = snapshot.sessionId,
                        executionDisplayId = snapshot.executionDisplayId,
                        virtualDisplayId = snapshot.virtualDisplayId,
                        surfaceDisplayId = snapshot.surfaceDisplayId,
                        sessionTaskId = snapshot.sessionTaskId,
                        step = step,
                        actionType = "NONE",
                        code = observation.code,
                        extras = "message=${observation.message}"
                    )
                }
            }

            if (shouldAbortStep(control, snapshot, step, actionType = "NONE")) {
                return
            }

            val planResult = planningAdapter.planNext(
                request = PlanningRequest(
                    sessionId = snapshot.sessionId,
                    command = snapshot.command,
                    step = step,
                    executionDisplayId = snapshot.executionDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    planningMode = planningAdapter.modeForSession(snapshot.sessionId),
                    capabilityProfile = CapabilityProfile.phase3PlatformReady(capabilities.supported),
                    observation = observation,
                    recentActions = history.recentActions(snapshot.sessionId),
                    lastFailureCode = history.lastFailureCode(snapshot.sessionId),
                    lastUserReply = history.lastUserReply(snapshot.sessionId)
                )
            )

            if (shouldAbortStep(control, snapshot, step, actionType = "NONE")) {
                return
            }

            when (planResult) {
                is PlanResult.PlanFailure -> {
                    history.setLastFailureCode(sessionId, planResult.code)
                    val failureExtras = buildString {
                        append("message=${planResult.message}")
                        planResult.rawResponseSnippet?.let {
                            append(" rawSnippet=$it")
                        }
                    }
                    log(
                        event = "planner_result",
                        sessionId = snapshot.sessionId,
                        executionDisplayId = snapshot.executionDisplayId,
                        virtualDisplayId = snapshot.virtualDisplayId,
                        surfaceDisplayId = snapshot.surfaceDisplayId,
                        sessionTaskId = snapshot.sessionTaskId,
                        step = step,
                        actionType = "NONE",
                        code = planResult.code,
                        extras = failureExtras
                    )
                    log(
                        event = "session_failed",
                        sessionId = snapshot.sessionId,
                        executionDisplayId = snapshot.executionDisplayId,
                        virtualDisplayId = snapshot.virtualDisplayId,
                        surfaceDisplayId = snapshot.surfaceDisplayId,
                        sessionTaskId = snapshot.sessionTaskId,
                        step = step,
                        actionType = "NONE",
                        code = planResult.code,
                        extras = failureExtras
                    )
                    callbacks.onSessionFailed(
                        sessionId = snapshot.sessionId,
                        step = step,
                        code = planResult.code,
                        details = planResult.message
                    )
                    return
                }

                is PlanResult.PlannedAction -> {
                    val action = planResult.action
                    val actionType = action.actionType()
                    log(
                        event = "planner_result",
                        sessionId = snapshot.sessionId,
                        executionDisplayId = snapshot.executionDisplayId,
                        virtualDisplayId = snapshot.virtualDisplayId,
                        surfaceDisplayId = snapshot.surfaceDisplayId,
                        sessionTaskId = snapshot.sessionTaskId,
                        step = step,
                        actionType = actionType,
                        code = "PLANNER_ACTION"
                    )
                    log(
                        event = "action_parsed",
                        sessionId = snapshot.sessionId,
                        executionDisplayId = snapshot.executionDisplayId,
                        virtualDisplayId = snapshot.virtualDisplayId,
                        surfaceDisplayId = snapshot.surfaceDisplayId,
                        sessionTaskId = snapshot.sessionTaskId,
                        step = step,
                        actionType = actionType,
                        code = "ACTION_READY"
                    )

                    when (action) {
                        is GuiAction.Done -> {
                            history.recordAction(
                                sessionId = sessionId,
                                step = step,
                                actionType = actionType,
                                code = "DONE",
                                summary = action.reason
                            )
                            history.setPendingQuestion(sessionId, null)
                            log(
                                event = "session_completed",
                                sessionId = snapshot.sessionId,
                                executionDisplayId = snapshot.executionDisplayId,
                                virtualDisplayId = snapshot.virtualDisplayId,
                                surfaceDisplayId = snapshot.surfaceDisplayId,
                                sessionTaskId = snapshot.sessionTaskId,
                                step = step,
                                actionType = actionType,
                                code = "DONE",
                                extras = "reason=${action.reason}"
                            )
                            callbacks.onSessionCompleted(
                                sessionId = snapshot.sessionId,
                                step = step,
                                reason = action.reason
                            )
                            return
                        }

                        is GuiAction.AskUser -> {
                            history.setPendingQuestion(sessionId, action.question)
                            history.recordAction(
                                sessionId = sessionId,
                                step = step,
                                actionType = actionType,
                                code = "ASK_USER",
                                summary = action.question
                            )
                            log(
                                event = "session_waiting_user",
                                sessionId = snapshot.sessionId,
                                executionDisplayId = snapshot.executionDisplayId,
                                virtualDisplayId = snapshot.virtualDisplayId,
                                surfaceDisplayId = snapshot.surfaceDisplayId,
                                sessionTaskId = snapshot.sessionTaskId,
                                step = step,
                                actionType = actionType,
                                code = "ASK_USER",
                                extras = "question=${action.question}"
                            )
                            callbacks.onSessionWaitingUser(
                                sessionId = snapshot.sessionId,
                                step = step,
                                question = action.question
                            )
                            return
                        }

                        is GuiAction.Wait -> {
                            history.recordAction(
                                sessionId = sessionId,
                                step = step,
                                actionType = actionType,
                                code = "WAIT",
                                summary = "durationMs=${action.durationMs}"
                            )
                            val slept = sleepInterruptibly(action.durationMs, control.stopRequested)
                            if (!slept) {
                                log(
                                    event = "automation_stopped",
                                    sessionId = snapshot.sessionId,
                                    executionDisplayId = snapshot.executionDisplayId,
                                    virtualDisplayId = snapshot.virtualDisplayId,
                                    surfaceDisplayId = snapshot.surfaceDisplayId,
                                    sessionTaskId = snapshot.sessionTaskId,
                                    step = step,
                                    actionType = actionType,
                                    code = "STOP_DURING_WAIT"
                                )
                                return
                            }
                            log(
                                event = "action_executed",
                                sessionId = snapshot.sessionId,
                                executionDisplayId = snapshot.executionDisplayId,
                                virtualDisplayId = snapshot.virtualDisplayId,
                                surfaceDisplayId = snapshot.surfaceDisplayId,
                                sessionTaskId = snapshot.sessionTaskId,
                                step = step,
                                actionType = actionType,
                                code = "WAIT_ELAPSED",
                                extras = "durationMs=${action.durationMs}"
                            )
                        }

                        else -> {
                            val execution = executeWithRetry(
                                snapshot = snapshot,
                                step = step,
                                action = action,
                                control = control
                            )
                            if (!execution.ok) {
                                if (execution.code == "AUTOMATION_STOPPED") {
                                    log(
                                        event = "automation_stopped",
                                        sessionId = snapshot.sessionId,
                                        executionDisplayId = snapshot.executionDisplayId,
                                        virtualDisplayId = snapshot.virtualDisplayId,
                                        surfaceDisplayId = snapshot.surfaceDisplayId,
                                        sessionTaskId = snapshot.sessionTaskId,
                                        step = step,
                                        actionType = actionType,
                                        code = "STOP_REQUESTED"
                                    )
                                    return
                                }
                                history.setLastFailureCode(sessionId, execution.code)
                                callbacks.onSessionFailed(
                                    sessionId = snapshot.sessionId,
                                    step = step,
                                    code = execution.code,
                                    details = execution.details
                                )
                                log(
                                    event = "session_failed",
                                    sessionId = snapshot.sessionId,
                                    executionDisplayId = snapshot.executionDisplayId,
                                    virtualDisplayId = snapshot.virtualDisplayId,
                                    surfaceDisplayId = snapshot.surfaceDisplayId,
                                    sessionTaskId = snapshot.sessionTaskId,
                                    step = step,
                                    actionType = actionType,
                                    code = execution.code,
                                    extras = "details=${execution.details}"
                                )
                                return
                            }
                            history.recordAction(
                                sessionId = sessionId,
                                step = step,
                                actionType = actionType,
                                code = execution.code,
                                summary = execution.details
                            )
                            history.setLastFailureCode(sessionId, null)
                            log(
                                event = "action_executed",
                                sessionId = snapshot.sessionId,
                                executionDisplayId = snapshot.executionDisplayId,
                                virtualDisplayId = snapshot.virtualDisplayId,
                                surfaceDisplayId = snapshot.surfaceDisplayId,
                                sessionTaskId = snapshot.sessionTaskId,
                                step = step,
                                actionType = actionType,
                                code = execution.code,
                                extras = "details=${execution.details}"
                            )
                        }
                    }
                }
            }
        }

        val snapshot = callbacks.getSnapshot(sessionId)
        callbacks.onSessionFailed(
            sessionId = sessionId,
            step = maxSteps,
            code = "AUTOMATION_MAX_STEPS_EXCEEDED",
            details = "maxSteps=$maxSteps"
        )
        log(
            event = "session_failed",
            sessionId = snapshot?.sessionId ?: sessionId,
            executionDisplayId = snapshot?.executionDisplayId ?: -1,
            virtualDisplayId = snapshot?.virtualDisplayId,
            surfaceDisplayId = snapshot?.surfaceDisplayId,
            sessionTaskId = snapshot?.sessionTaskId ?: -1,
            step = maxSteps,
            actionType = "NONE",
            code = "AUTOMATION_MAX_STEPS_EXCEEDED",
            extras = "maxSteps=$maxSteps"
        )
    }

    private fun shouldAbortStep(
        control: LoopControl,
        snapshot: AutomationSessionSnapshot,
        step: Int,
        actionType: String
    ): Boolean {
        if (control.stopRequested.get()) {
            log(
                event = "automation_stopped",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = actionType,
                code = "STOP_REQUESTED"
            )
            return true
        }

        val currentSnapshot = callbacks.getSnapshot(snapshot.sessionId) ?: return false
        if (currentSnapshot.status == SessionStatus.RUNNING) {
            return false
        }

        log(
            event = "automation_stopped",
            sessionId = currentSnapshot.sessionId,
            executionDisplayId = currentSnapshot.executionDisplayId,
            virtualDisplayId = currentSnapshot.virtualDisplayId,
            surfaceDisplayId = currentSnapshot.surfaceDisplayId,
            sessionTaskId = currentSnapshot.sessionTaskId,
            step = step,
            actionType = actionType,
            code = "SESSION_NOT_RUNNING",
            extras = "status=${currentSnapshot.status.name}"
        )
        return true
    }

    private fun executeWithRetry(
        snapshot: AutomationSessionSnapshot,
        step: Int,
        action: GuiAction,
        control: LoopControl
    ): ActionExecutionResult {
        val actionType = action.actionType()
        val totalAttempts = maxActionRetries + 1
        var lastResult = ActionExecutionResult(
            ok = false,
            code = "ACTION_NOT_EXECUTED",
            details = "no attempt"
        )

        for (attempt in 1..totalAttempts) {
            if (control.stopRequested.get()) {
                return ActionExecutionResult(
                    ok = false,
                    code = "AUTOMATION_STOPPED",
                    details = "stop requested"
                )
            }

            val result = actionExecutor.execute(
                ActionExecutionRequest(
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    action = action
                )
            )
            if (result.ok) {
                return result
            }

            lastResult = result
            log(
                event = "action_failed",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = actionType,
                code = result.code,
                extras = "attempt=$attempt totalAttempts=$totalAttempts details=${result.details}"
            )

            if (result.code == "EXECUTOR_UNSUPPORTED" ||
                result.code == "EXECUTOR_ACTION_UNSUPPORTED" ||
                result.code == "UNSUPPORTED" ||
                result.code == "PERMISSION_DENIED" ||
                result.code == "DISPLAY_NOT_FOUND"
            ) {
                break
            }
        }

        return lastResult
    }

    private fun sleepInterruptibly(durationMs: Long, stopRequested: AtomicBoolean): Boolean {
        val sleepTarget = durationMs.coerceAtLeast(1L)
        var remaining = sleepTarget
        while (remaining > 0) {
            if (stopRequested.get()) {
                return false
            }
            val chunk = minOf(remaining, 200L)
            try {
                Thread.sleep(chunk)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= chunk
        }
        return true
    }

    private fun log(
        event: String,
        sessionId: String,
        executionDisplayId: Int,
        virtualDisplayId: Int?,
        surfaceDisplayId: Int?,
        sessionTaskId: Int,
        step: Int,
        actionType: String,
        code: String,
        extras: String = "{}"
    ) {
        val plannerMode = runCatching {
            planningAdapter.modeForSession(sessionId).name
        }.getOrDefault("UNKNOWN")
        AgentHostLog.i(
            TAG,
            buildString {
                append("event=").append(event)
                append(" sessionId=").append(sessionId)
                append(" executionDisplayId=").append(executionDisplayId)
                append(" virtualDisplayId=").append(virtualDisplayId ?: "null")
                append(" surfaceDisplayId=").append(surfaceDisplayId ?: "null")
                append(" sessionTaskId=").append(sessionTaskId)
                append(" step=").append(step)
                append(" plannerMode=").append(plannerMode)
                append(" actionType=").append(actionType)
                append(" code=").append(code)
                append(" extras=").append(extras)
                if (virtualDisplayId != null) {
                    append(" vdDisplayId=").append(virtualDisplayId)
                }
            }
        )
    }
}
