package com.example.agenthost.automation

import com.example.agenthost.core.AgentHostLog
import com.example.agenthost.debug.TaskRunReportHooks
import com.example.agenthost.runtime.VirtualDisplayRuntime
import com.example.agenthost.session.SessionStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

private data class LoopControl(
    val stopRequested: AtomicBoolean,
    var future: Future<*>?
)

private data class ClickObservationOutcome(
    val observation: ObservationResult?,
    val localChangeConfirmedPoint: CanonicalPoint? = null
)

fun interface InitialObservationRetryPolicy {
    fun shouldRetry(
        snapshot: AutomationSessionSnapshot,
        step: Int,
        observation: ObservationResult
    ): Boolean
}

private object ObservationBitmapUtils {
    fun isLikelyNearBlackSingleColorFrame(observation: ObservationResult.Captured): Boolean {
        return ObservationChangeDetector.isLikelyNearBlackSingleColorFrame(observation)
    }

    fun areNearlyUnchanged(
        before: ObservationResult.Captured,
        after: ObservationResult.Captured
    ): Boolean {
        return ObservationChangeDetector.areNearlyUnchanged(before, after)
    }
}

private object DefaultInitialObservationRetryPolicy : InitialObservationRetryPolicy {
    override fun shouldRetry(
        snapshot: AutomationSessionSnapshot,
        step: Int,
        observation: ObservationResult
    ): Boolean {
        if (step != 1 || snapshot.virtualDisplayId == null) {
            return false
        }
        val captured = observation as? ObservationResult.Captured ?: return false
        return ObservationBitmapUtils.isLikelyNearBlackSingleColorFrame(captured)
    }
}

data class EngineResult(
    val ok: Boolean,
    val code: String,
    val details: String = ""
)

data class AutomationSessionSnapshot(
    val sessionId: String,
    val command: String,
    val executionDisplayId: Int,
    val currentVisibleDisplayId: Int? = null,
    val virtualDisplayId: Int? = null,
    val surfaceDisplayId: Int? = null,
    val sessionTaskId: Int,
    val status: SessionStatus,
    val lastKnownTopActivity: String? = null,
    val lastKnownTopPackage: String? = null,
    val runtimeHandle: VirtualDisplayRuntime.RuntimeHandle? = null
)

interface AutomationStateCallbacks {
    fun getSnapshot(sessionId: String): AutomationSessionSnapshot?

    fun onSessionWaitingUser(
        sessionId: String,
        step: Int,
        question: String,
        completionConfirmationPending: Boolean
    )

    fun onSessionCompleted(sessionId: String, step: Int, reason: String)

    fun onSessionFailed(sessionId: String, step: Int, code: String, details: String)
}

class SessionAutomationEngine(
    private val callbacks: AutomationStateCallbacks,
    private val observationProvider: ObservationProvider,
    private val planningAdapter: PlanningAdapter,
    private val actionExecutor: DisplayActionExecutor,
    private val history: AutomationHistory,
    private val controlCoordinator: AgentControlCoordinator = AgentControlCoordinator(),
    private val maxSteps: Int = 30,
    private val maxActionRetries: Int = 2,
    private val worker: ExecutorService = Executors.newCachedThreadPool(),
    private val postClickSettleDelayMs: Long = 250L,
    private val postClickSettleRetryDelayMs: Long = 250L,
    private val toggleExtraSettleDelayMs: Long = 125L,
    private val toggleExtraSettleFrames: Int = 2,
    private val initialObservationRetryDelayMs: Long = 250L,
    private val initialObservationRetryPolicy: InitialObservationRetryPolicy =
        DefaultInitialObservationRetryPolicy
) {
    companion object {
        private const val TAG = "SessionAutomationEngine"
    }

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
                    Thread.interrupted()
                    runLoop(sessionId, control)
                } catch (t: Throwable) {
                    if (control.stopRequested.get()) {
                        val snapshot = callbacks.getSnapshot(sessionId)
                        log(
                            event = "automation_stopped",
                            sessionId = snapshot?.sessionId ?: sessionId,
                            executionDisplayId = snapshot?.executionDisplayId ?: -1,
                            virtualDisplayId = snapshot?.virtualDisplayId,
                            surfaceDisplayId = snapshot?.surfaceDisplayId,
                            sessionTaskId = snapshot?.sessionTaskId ?: -1,
                            step = 0,
                            actionType = "NONE",
                            code = "STOP_REQUESTED",
                            extras = "details=${t.message ?: t.javaClass.simpleName}"
                        )
                        return@submit
                    }
                    val snapshot = callbacks.getSnapshot(sessionId)
                    log(
                        event = "automation_crashed",
                        sessionId = snapshot?.sessionId ?: sessionId,
                        executionDisplayId = snapshot?.executionDisplayId ?: -1,
                        virtualDisplayId = snapshot?.virtualDisplayId,
                        surfaceDisplayId = snapshot?.surfaceDisplayId,
                        sessionTaskId = snapshot?.sessionTaskId ?: -1,
                        step = 0,
                        actionType = "NONE",
                        code = "AUTOMATION_LOOP_CRASHED",
                        extras = "details=${t.message ?: t.javaClass.simpleName}"
                    )
                    snapshot?.let {
                        callbacks.onSessionFailed(
                            sessionId = it.sessionId,
                            step = 0,
                            code = "AUTOMATION_LOOP_CRASHED",
                            details = t.message ?: t.javaClass.simpleName
                        )
                    }
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

    fun finishSession(sessionId: String) {
        planningAdapter.clearSession(sessionId)
        history.clearSession(sessionId)
        controlCoordinator.clearSession(sessionId)
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
        controlCoordinator.clearSession(sessionId)
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
            TaskRunReportHooks.onStepBegin(
                sessionId = snapshot.sessionId,
                step = step,
                displayId = snapshot.currentVisibleDisplayId ?: snapshot.executionDisplayId,
                topActivity = snapshot.lastKnownTopActivity,
                topPackage = snapshot.lastKnownTopPackage
            )

            if (shouldAbortStep(control, snapshot, step, actionType = "NONE")) {
                return
            }

            val observation = captureObservationForPlanning(snapshot, step, control)

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
                        extras = "observationDisplayId=${snapshot.executionDisplayId} " +
                            "width=${observation.width} height=${observation.height}"
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
                        extras = "observationDisplayId=${snapshot.executionDisplayId} " +
                            "message=${observation.message}"
                    )
                }
            }
            TaskRunReportHooks.onObservation(
                sessionId = snapshot.sessionId,
                step = step,
                observation = observation,
                observationDisplayId = snapshot.executionDisplayId,
                displayId = snapshot.currentVisibleDisplayId ?: snapshot.executionDisplayId,
                topActivity = snapshot.lastKnownTopActivity,
                topPackage = snapshot.lastKnownTopPackage
            )

            if (shouldAbortStep(control, snapshot, step, actionType = "NONE")) {
                return
            }

            val capabilityProfile = CapabilityProfile.phase3PlatformReady(capabilities.supported)
            val controlSnapshot = controlCoordinator.buildPlanningSnapshot(
                sessionId = snapshot.sessionId,
                command = snapshot.command,
                capabilityProfile = capabilityProfile
            )
            val planResult = planningAdapter.planNext(
                request = PlanningRequest(
                    sessionId = snapshot.sessionId,
                    command = snapshot.command,
                    step = step,
                    executionDisplayId = snapshot.executionDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    planningMode = planningAdapter.modeForSession(snapshot.sessionId),
                    capabilityProfile = capabilityProfile,
                    observation = observation,
                    recentActions = history.recentActions(snapshot.sessionId),
                    lastFailureCode = history.lastFailureCode(snapshot.sessionId),
                    lastUserReply = history.lastUserReply(snapshot.sessionId),
                    controlSnapshot = controlSnapshot
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
                    TaskRunReportHooks.onPlanFailure(
                        sessionId = snapshot.sessionId,
                        step = step,
                        code = planResult.code,
                        details = planResult.message,
                        rawResponseText = planResult.rawResponseSnippet
                    )
                    return
                }

                is PlanResult.PlannedAction -> {
                    val proposedAction = planResult.action
                    val proposedCanonicalAction = planResult.canonicalAction
                    TaskRunReportHooks.onActionPlanned(snapshot.sessionId, step, proposedAction)
                    log(
                        event = "planner_result",
                        sessionId = snapshot.sessionId,
                        executionDisplayId = snapshot.executionDisplayId,
                        virtualDisplayId = snapshot.virtualDisplayId,
                        surfaceDisplayId = snapshot.surfaceDisplayId,
                        sessionTaskId = snapshot.sessionTaskId,
                        step = step,
                        actionType = proposedAction.actionType(),
                        code = "PLANNER_ACTION"
                    )
                    val controlResolution = controlCoordinator.resolveAction(
                        sessionId = snapshot.sessionId,
                        step = step,
                        command = snapshot.command,
                        beforeObservation = observation,
                        action = proposedCanonicalAction
                    )
                    val action = controlResolution.finalAction
                    val actionType = action.actionType()
                    TaskRunReportHooks.onControlActionResolved(
                        sessionId = snapshot.sessionId,
                        step = step,
                        proposedAction = controlResolution.proposedAction,
                        finalAction = controlResolution.finalCanonicalAction,
                        phaseBefore = controlResolution.phaseBefore.name,
                        guardrailRule = controlResolution.guardrailDecision.rule,
                        guardrailDecision = controlResolution.guardrailDecision.type.name
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
                        code = if (controlResolution.guardrailDecision.wasBlocked()) {
                            controlResolution.guardrailDecision.rule ?: "ACTION_GUARDRAIL_REPLACED"
                        } else {
                            "ACTION_READY"
                        },
                        extras = if (controlResolution.guardrailDecision.wasBlocked()) {
                            "proposedAction=${proposedAction.actionType()} finalAction=$actionType " +
                                "rule=${controlResolution.guardrailDecision.rule} " +
                                "message=${controlResolution.guardrailDecision.message}"
                        } else {
                            "{}"
                        }
                    )

                    var controlSyntheticCode: String? = null
                    var controlSyntheticDetails: String? = null
                    var executionResult: ActionExecutionResult? = null
                    var afterObservation: ObservationResult? = null
                    var localChangeConfirmedPoint: CanonicalPoint? = null

                    when (action) {
                        is GuiAction.Done -> {
                            controlSyntheticCode = "DONE"
                            controlSyntheticDetails = action.reason
                            TaskRunReportHooks.onSyntheticActionResult(
                                sessionId = snapshot.sessionId,
                                step = step,
                                action = action,
                                code = controlSyntheticCode,
                                details = controlSyntheticDetails
                            )
                        }

                        is GuiAction.AskUser -> {
                            controlSyntheticCode = "ASK_USER"
                            controlSyntheticDetails = action.question
                            TaskRunReportHooks.onSyntheticActionResult(
                                sessionId = snapshot.sessionId,
                                step = step,
                                action = action,
                                code = controlSyntheticCode,
                                details = controlSyntheticDetails
                            )
                        }

                        is GuiAction.Wait -> {
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
                            controlSyntheticCode = "WAIT_ELAPSED"
                            controlSyntheticDetails = "durationMs=${action.durationMs}"
                            log(
                                event = "action_executed",
                                sessionId = snapshot.sessionId,
                                executionDisplayId = snapshot.executionDisplayId,
                                virtualDisplayId = snapshot.virtualDisplayId,
                                surfaceDisplayId = snapshot.surfaceDisplayId,
                                sessionTaskId = snapshot.sessionTaskId,
                                step = step,
                                actionType = actionType,
                                code = controlSyntheticCode,
                                extras = controlSyntheticDetails
                            )
                            TaskRunReportHooks.onSyntheticActionResult(
                                sessionId = snapshot.sessionId,
                                step = step,
                                action = action,
                                code = controlSyntheticCode,
                                details = controlSyntheticDetails
                            )
                        }

                        else -> {
                            val execution = executeWithRetry(
                                snapshot = snapshot,
                                step = step,
                                action = action,
                                control = control
                            )
                            executionResult = execution
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
                                TaskRunReportHooks.onPlatformResult(
                                    sessionId = snapshot.sessionId,
                                    step = step,
                                    action = action,
                                    result = execution,
                                    injectDisplayId = snapshot.executionDisplayId
                                )
                            } else {
                                afterObservation = when (action) {
                                    is GuiAction.Click -> {
                                        val clickOutcome = captureClickAfterObservationWithSettle(
                                        snapshot = snapshot,
                                        step = step,
                                        beforeObservation = observation,
                                        action = action,
                                        control = control
                                    )
                                        localChangeConfirmedPoint = clickOutcome.localChangeConfirmedPoint
                                        clickOutcome.observation
                                    }

                                    else -> capturePostActionObservationWithSettle(
                                        snapshot = snapshot,
                                        step = step,
                                        actionType = actionType,
                                        control = control
                                    )
                                }
                                if (action is GuiAction.Click &&
                                    afterObservation != null &&
                                    TaskRunReportHooks.shouldCollectClickDiagnostics(snapshot.sessionId)
                                ) {
                                    TaskRunReportHooks.onClickAfterObservation(
                                        sessionId = snapshot.sessionId,
                                        step = step,
                                        observationDisplayId = snapshot.executionDisplayId,
                                        observation = afterObservation
                                    )
                                }
                                TaskRunReportHooks.onPlatformResult(
                                    sessionId = snapshot.sessionId,
                                    step = step,
                                    action = action,
                                    result = execution,
                                    injectDisplayId = snapshot.executionDisplayId
                                )
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
                                    extras = "injectDisplayId=${snapshot.executionDisplayId} details=${execution.details}"
                                )
                            }
                        }
                    }

                    val postResolution = controlCoordinator.finalizeStep(
                        sessionId = snapshot.sessionId,
                        step = step,
                        command = snapshot.command,
                        beforeObservation = observation,
                        afterObservation = afterObservation,
                        resolution = controlResolution,
                        executionResult = executionResult,
                        controlSyntheticCode = controlSyntheticCode,
                        controlSyntheticDetails = controlSyntheticDetails,
                        localChangeConfirmedPoint = localChangeConfirmedPoint
                    )
                    TaskRunReportHooks.onControlStepEvaluated(
                        sessionId = snapshot.sessionId,
                        step = step,
                        phaseAfter = postResolution.phaseAfter.name,
                        completionVerdict = postResolution.completionVerdict.name,
                        observationChanged = postResolution.observationChanged,
                        lowYield = postResolution.resultSummary.lowYield
                    )
                    history.recordAction(
                        sessionId = sessionId,
                        step = step,
                        actionType = actionType,
                        code = postResolution.resultSummary.code,
                        summary = postResolution.resultSummary.message
                    )
                    if (postResolution.resultSummary.category == StepResultCategory.PLATFORM_FAILED) {
                        history.setLastFailureCode(sessionId, postResolution.resultSummary.code)
                        callbacks.onSessionFailed(
                            sessionId = snapshot.sessionId,
                            step = step,
                            code = postResolution.resultSummary.code,
                            details = postResolution.resultSummary.message
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
                            code = postResolution.resultSummary.code,
                            extras = "details=${postResolution.resultSummary.message}"
                        )
                        return
                    }
                    history.setLastFailureCode(sessionId, null)

                    if (postResolution.requestSessionComplete) {
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
                            code = "COMPLETED",
                            extras = "reason=${postResolution.completionReason ?: postResolution.resultSummary.message}"
                        )
                        callbacks.onSessionCompleted(
                            sessionId = snapshot.sessionId,
                            step = step,
                            reason = postResolution.completionReason ?: postResolution.resultSummary.message
                        )
                        return
                    }

                    if (action is GuiAction.AskUser || postResolution.requestUserHandoff) {
                        val question = when {
                            action is GuiAction.AskUser -> action.question
                            else -> postResolution.handoffQuestion ?: "当前需要你接手确认下一步。"
                        }
                        history.setPendingQuestion(sessionId, question)
                        log(
                            event = "session_waiting_user",
                            sessionId = snapshot.sessionId,
                            executionDisplayId = snapshot.executionDisplayId,
                            virtualDisplayId = snapshot.virtualDisplayId,
                            surfaceDisplayId = snapshot.surfaceDisplayId,
                            sessionTaskId = snapshot.sessionTaskId,
                            step = step,
                            actionType = "ASK_USER",
                            code = "WAITING_USER",
                            extras = "question=$question"
                        )
                        callbacks.onSessionWaitingUser(
                            sessionId = snapshot.sessionId,
                            step = step,
                            question = question,
                            completionConfirmationPending = postResolution.completionConfirmationPending
                        )
                        return
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

    private fun captureClickAfterObservationWithSettle(
        snapshot: AutomationSessionSnapshot,
        step: Int,
        beforeObservation: ObservationResult,
        action: GuiAction.Click,
        control: LoopControl
    ): ClickObservationOutcome {
        if (postClickSettleDelayMs > 0L &&
            !sleepInterruptibly(postClickSettleDelayMs, control.stopRequested)
        ) {
            log(
                event = "click_settle",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "CLICK",
                code = "POST_CLICK_SETTLE_SKIPPED",
                extras = "reason=stop_requested"
            )
            return ClickObservationOutcome(observation = null)
        }

        val firstAfter = captureClickAfterObservation(snapshot, step)
        val beforeCaptured = beforeObservation as? ObservationResult.Captured
        val afterCaptured = firstAfter as? ObservationResult.Captured
        if (beforeCaptured == null || afterCaptured == null ||
            !ObservationBitmapUtils.areNearlyUnchanged(beforeCaptured, afterCaptured)
        ) {
            return ClickObservationOutcome(observation = firstAfter)
        }

        val clickPoint = CanonicalPoint(action.x, action.y)
        val firstLocalChange = ObservationChangeDetector.detectLocalRoiChangeNearPoint(
            before = beforeCaptured,
            after = afterCaptured,
            point = clickPoint
        )
        if (firstLocalChange.confirmed) {
            log(
                event = "click_settle",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "CLICK",
                code = "LOCAL_ROI_CHANGE_CONFIRMED",
                extras = "frame=0 x=${action.x} y=${action.y} meanDiff=${firstLocalChange.meanDiff} " +
                    "changedPixelRatio=${firstLocalChange.significantPixelRatio} " +
                    "changedPixels=${firstLocalChange.changedPixelCount}/${firstLocalChange.roiPixelCount}"
            )
            return ClickObservationOutcome(
                observation = firstAfter,
                localChangeConfirmedPoint = clickPoint
            )
        }
        log(
            event = "click_settle",
            sessionId = snapshot.sessionId,
            executionDisplayId = snapshot.executionDisplayId,
            virtualDisplayId = snapshot.virtualDisplayId,
            surfaceDisplayId = snapshot.surfaceDisplayId,
            sessionTaskId = snapshot.sessionTaskId,
            step = step,
            actionType = "CLICK",
            code = "LOCAL_ROI_CHANGE_NOT_CONFIRMED",
            extras = "frame=0 x=${action.x} y=${action.y} meanDiff=${firstLocalChange.meanDiff} " +
                "changedPixelRatio=${firstLocalChange.significantPixelRatio} " +
                "changedPixels=${firstLocalChange.changedPixelCount}/${firstLocalChange.roiPixelCount}"
        )

        log(
            event = "click_settle",
            sessionId = snapshot.sessionId,
            executionDisplayId = snapshot.executionDisplayId,
            virtualDisplayId = snapshot.virtualDisplayId,
            surfaceDisplayId = snapshot.surfaceDisplayId,
            sessionTaskId = snapshot.sessionTaskId,
            step = step,
            actionType = "CLICK",
            code = "POST_CLICK_UNCHANGED_RETRY_SCHEDULED",
            extras = "delayMs=$postClickSettleRetryDelayMs"
        )
        if (postClickSettleRetryDelayMs > 0L &&
            !sleepInterruptibly(postClickSettleRetryDelayMs, control.stopRequested)
        ) {
            log(
                event = "click_settle",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "CLICK",
                code = "POST_CLICK_UNCHANGED_RETRY_SKIPPED",
                extras = "reason=stop_requested"
            )
            return ClickObservationOutcome(observation = firstAfter)
        }
        var latestObservation: ObservationResult = captureClickAfterObservation(snapshot, step)
        val retryCaptured = latestObservation as? ObservationResult.Captured
        if (retryCaptured != null &&
            !ObservationBitmapUtils.areNearlyUnchanged(beforeCaptured, retryCaptured)
        ) {
            return ClickObservationOutcome(observation = latestObservation)
        }
        val retryLocalChange = retryCaptured?.let {
            ObservationChangeDetector.detectLocalRoiChangeNearPoint(
                before = beforeCaptured,
                after = it,
                point = clickPoint
            )
        }
        if (retryLocalChange?.confirmed == true) {
            log(
                event = "click_settle",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "CLICK",
                code = "LOCAL_ROI_CHANGE_CONFIRMED",
                extras = "frame=1 x=${action.x} y=${action.y} meanDiff=${retryLocalChange.meanDiff} " +
                    "changedPixelRatio=${retryLocalChange.significantPixelRatio} " +
                    "changedPixels=${retryLocalChange.changedPixelCount}/${retryLocalChange.roiPixelCount}"
            )
            return ClickObservationOutcome(
                observation = latestObservation,
                localChangeConfirmedPoint = clickPoint
            )
        }
        log(
            event = "click_settle",
            sessionId = snapshot.sessionId,
            executionDisplayId = snapshot.executionDisplayId,
            virtualDisplayId = snapshot.virtualDisplayId,
            surfaceDisplayId = snapshot.surfaceDisplayId,
            sessionTaskId = snapshot.sessionTaskId,
            step = step,
            actionType = "CLICK",
            code = "LOCAL_ROI_CHANGE_NOT_CONFIRMED",
            extras = "frame=1 x=${action.x} y=${action.y} meanDiff=${retryLocalChange?.meanDiff ?: 0.0} " +
                "changedPixelRatio=${retryLocalChange?.significantPixelRatio ?: 0.0} " +
                "changedPixels=${retryLocalChange?.changedPixelCount ?: 0}/" +
                "${retryLocalChange?.roiPixelCount ?: 0}"
        )
        for (index in 0 until toggleExtraSettleFrames) {
            log(
                event = "click_settle",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "CLICK",
                code = "TOGGLE_EXTRA_SETTLE_FRAME",
                extras = "frame=${index + 2} delayMs=$toggleExtraSettleDelayMs x=${action.x} y=${action.y}"
            )
            if (toggleExtraSettleDelayMs > 0L &&
                !sleepInterruptibly(toggleExtraSettleDelayMs, control.stopRequested)
            ) {
                return ClickObservationOutcome(observation = latestObservation)
            }
            latestObservation = captureClickAfterObservation(snapshot, step)
            val extraCaptured = latestObservation as? ObservationResult.Captured ?: continue
            if (!ObservationBitmapUtils.areNearlyUnchanged(beforeCaptured, extraCaptured)) {
                return ClickObservationOutcome(observation = latestObservation)
            }
            val extraLocalChange = ObservationChangeDetector.detectLocalRoiChangeNearPoint(
                before = beforeCaptured,
                after = extraCaptured,
                point = clickPoint
            )
            if (extraLocalChange.confirmed) {
                log(
                    event = "click_settle",
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    virtualDisplayId = snapshot.virtualDisplayId,
                    surfaceDisplayId = snapshot.surfaceDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    step = step,
                    actionType = "CLICK",
                    code = "LOCAL_ROI_CHANGE_CONFIRMED",
                    extras = "frame=${index + 2} x=${action.x} y=${action.y} meanDiff=${extraLocalChange.meanDiff} " +
                        "changedPixelRatio=${extraLocalChange.significantPixelRatio} " +
                        "changedPixels=${extraLocalChange.changedPixelCount}/${extraLocalChange.roiPixelCount}"
                )
                return ClickObservationOutcome(
                    observation = latestObservation,
                    localChangeConfirmedPoint = clickPoint
                )
            }
            log(
                event = "click_settle",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "CLICK",
                code = "LOCAL_ROI_CHANGE_NOT_CONFIRMED",
                extras = "frame=${index + 2} x=${action.x} y=${action.y} meanDiff=${extraLocalChange.meanDiff} " +
                    "changedPixelRatio=${extraLocalChange.significantPixelRatio} " +
                    "changedPixels=${extraLocalChange.changedPixelCount}/${extraLocalChange.roiPixelCount}"
            )
        }
        return ClickObservationOutcome(observation = latestObservation)
    }

    private fun capturePostActionObservationWithSettle(
        snapshot: AutomationSessionSnapshot,
        step: Int,
        actionType: String,
        control: LoopControl
    ): ObservationResult? {
        if (postClickSettleDelayMs > 0L &&
            !sleepInterruptibly(postClickSettleDelayMs, control.stopRequested)
        ) {
            log(
                event = "post_action_observation",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = actionType,
                code = "POST_ACTION_OBSERVATION_SKIPPED",
                extras = "reason=stop_requested"
            )
            return null
        }
        val observation = captureClickAfterObservation(snapshot, step)
        log(
            event = "post_action_observation",
            sessionId = snapshot.sessionId,
            executionDisplayId = snapshot.executionDisplayId,
            virtualDisplayId = snapshot.virtualDisplayId,
            surfaceDisplayId = snapshot.surfaceDisplayId,
            sessionTaskId = snapshot.sessionTaskId,
            step = step,
            actionType = actionType,
            code = when (observation) {
                is ObservationResult.Captured -> "OBSERVATION_OK"
                is ObservationResult.Failed -> observation.code
            }
        )
        return observation
    }

    private fun captureClickAfterObservation(
        snapshot: AutomationSessionSnapshot,
        step: Int
    ): ObservationResult {
        val observation = observationProvider.capture(
            ObservationRequest(
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                imageReader = snapshot.runtimeHandle?.imageReader
            )
        )
        when (observation) {
            is ObservationResult.Captured -> {
                log(
                    event = "click_after_observation",
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    virtualDisplayId = snapshot.virtualDisplayId,
                    surfaceDisplayId = snapshot.surfaceDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    step = step,
                    actionType = "CLICK",
                    code = "OBSERVATION_OK",
                    extras = "observationDisplayId=${snapshot.executionDisplayId} " +
                        "width=${observation.width} height=${observation.height}"
                )
            }

            is ObservationResult.Failed -> {
                log(
                    event = "click_after_observation",
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    virtualDisplayId = snapshot.virtualDisplayId,
                    surfaceDisplayId = snapshot.surfaceDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    step = step,
                    actionType = "CLICK",
                    code = observation.code,
                    extras = "observationDisplayId=${snapshot.executionDisplayId} " +
                        "message=${observation.message}"
                )
            }
        }
        return observation
    }

    private fun captureObservationForPlanning(
        snapshot: AutomationSessionSnapshot,
        step: Int,
        control: LoopControl
    ): ObservationResult {
        val request = ObservationRequest(
            sessionId = snapshot.sessionId,
            executionDisplayId = snapshot.executionDisplayId,
            sessionTaskId = snapshot.sessionTaskId,
            imageReader = snapshot.runtimeHandle?.imageReader
        )
        val firstObservation = observationProvider.capture(request)
        if (!initialObservationRetryPolicy.shouldRetry(snapshot, step, firstObservation)) {
            return firstObservation
        }
        log(
            event = "observation_retry",
            sessionId = snapshot.sessionId,
            executionDisplayId = snapshot.executionDisplayId,
            virtualDisplayId = snapshot.virtualDisplayId,
            surfaceDisplayId = snapshot.surfaceDisplayId,
            sessionTaskId = snapshot.sessionTaskId,
            step = step,
            actionType = "NONE",
            code = "INITIAL_FRAME_RETRY_SCHEDULED",
            extras = "delayMs=$initialObservationRetryDelayMs reason=likely_black_frame"
        )
        if (initialObservationRetryDelayMs > 0L &&
            !sleepInterruptibly(initialObservationRetryDelayMs, control.stopRequested)
        ) {
            log(
                event = "observation_retry",
                sessionId = snapshot.sessionId,
                executionDisplayId = snapshot.executionDisplayId,
                virtualDisplayId = snapshot.virtualDisplayId,
                surfaceDisplayId = snapshot.surfaceDisplayId,
                sessionTaskId = snapshot.sessionTaskId,
                step = step,
                actionType = "NONE",
                code = "INITIAL_FRAME_RETRY_SKIPPED",
                extras = "reason=stop_requested"
            )
            return firstObservation
        }
        val retriedObservation = observationProvider.capture(request)
        return when (retriedObservation) {
            is ObservationResult.Captured -> {
                log(
                    event = "observation_retry",
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    virtualDisplayId = snapshot.virtualDisplayId,
                    surfaceDisplayId = snapshot.surfaceDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    step = step,
                    actionType = "NONE",
                    code = "INITIAL_FRAME_RETRY_CAPTURED",
                    extras = "width=${retriedObservation.width} height=${retriedObservation.height}"
                )
                retriedObservation
            }

            is ObservationResult.Failed -> {
                log(
                    event = "observation_retry",
                    sessionId = snapshot.sessionId,
                    executionDisplayId = snapshot.executionDisplayId,
                    virtualDisplayId = snapshot.virtualDisplayId,
                    surfaceDisplayId = snapshot.surfaceDisplayId,
                    sessionTaskId = snapshot.sessionTaskId,
                    step = step,
                    actionType = "NONE",
                    code = "INITIAL_FRAME_RETRY_FALLBACK",
                    extras = "retryCode=${retriedObservation.code} details=${retriedObservation.message}"
                )
                firstObservation
            }
        }
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
