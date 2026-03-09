package com.example.agenthost.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.example.agenthost.automation.AutomationHistory
import com.example.agenthost.automation.AutomationSessionSnapshot
import com.example.agenthost.automation.AutomationStateCallbacks
import com.example.agenthost.automation.DisplayActionExecutor
import com.example.agenthost.automation.ObservationProvider
import com.example.agenthost.automation.PlanningAdapter
import com.example.agenthost.automation.PlannerSelector
import com.example.agenthost.automation.PlatformDisplayActionExecutor
import com.example.agenthost.automation.PlatformObservationProvider
import com.example.agenthost.automation.SessionAutomationEngine
import com.example.agenthost.config.PlannerConfig
import com.example.agenthost.debug.SessionAuditHooks
import com.example.agenthost.migration.TaskMigrationController
import com.example.agenthost.platform.AgentHostPlatformClient
import com.example.agenthost.runtime.VirtualDisplayRuntime
import java.util.UUID

class SessionCoordinator(
    private val appContext: Context,
    private val virtualDisplayRuntime: VirtualDisplayRuntime,
    private val taskMigrationController: TaskMigrationController,
    private val attachTimeoutMs: Long = 8_000L
) {
    companion object {
        private const val TAG = "SessionCoordinator"
        private const val INVALID_TASK_ID = -1
    }

    private val lock = Any()
    private var activeSession: SessionState? = null
    private var activeRuntime: VirtualDisplayRuntime.RuntimeHandle? = null

    private val automationHistory = AutomationHistory()
    private var activePlannerConfig: PlannerConfig = PlannerConfig()
    private val planningAdapter: PlanningAdapter = PlannerSelector(automationHistory) {
        synchronized(lock) { activePlannerConfig }
    }
    private val platformGateway = AgentHostPlatformClient(appContext)
    private val observationProvider: ObservationProvider = PlatformObservationProvider(platformGateway)
    private val actionExecutor: DisplayActionExecutor = PlatformDisplayActionExecutor(platformGateway)
    private val automationEngine: SessionAutomationEngine = SessionAutomationEngine(
        callbacks = object : AutomationStateCallbacks {
            override fun getSnapshot(sessionId: String): AutomationSessionSnapshot? {
                return synchronized(lock) {
                    val state = activeSession ?: return@synchronized null
                    if (state.sessionId != sessionId) {
                        return@synchronized null
                    }
                    AutomationSessionSnapshot(
                        sessionId = state.sessionId,
                        command = state.command,
                        executionDisplayId = state.executionDisplayId,
                        virtualDisplayId = state.virtualDisplayId,
                        surfaceDisplayId = state.surfaceDisplayId,
                        sessionTaskId = state.sessionTaskId,
                        status = state.status,
                        runtimeHandle = activeRuntime
                    )
                }
            }

            override fun onSessionWaitingUser(sessionId: String, step: Int, question: String) {
                synchronized(lock) {
                    val state = activeSession ?: return
                    if (state.sessionId != sessionId || state.status != SessionStatus.RUNNING) {
                        return
                    }
                    var waiting = state.copy(
                        status = SessionStatus.WAITING_USER,
                        waitingQuestion = question,
                        automationStep = step,
                        lastError = null,
                        pendingReturnToVirtualDisplay = false
                    )
                    activeSession = waiting
                    logInfo(
                        event = "session_waiting_user",
                        state = waiting,
                        step = step,
                        actionType = "ASK_USER",
                        code = "WAITING_USER",
                        extras = "question=$question"
                    )
                    if (waiting.backgroundPreferred && !waiting.visibleToUser) {
                        val surfaced = showSessionOnMainLocked(waiting, reason = "waiting_user")
                        waiting = if (surfaced.visibleToUser) {
                            surfaced.copy(
                                pendingReturnToVirtualDisplay = surfaced.returnToVirtualAfterUser
                            )
                        } else {
                            surfaced
                        }
                        activeSession = waiting
                    }
                }
            }

            override fun onSessionCompleted(sessionId: String, step: Int, reason: String) {
                synchronized(lock) {
                    val state = activeSession ?: return
                    if (state.sessionId != sessionId || state.status != SessionStatus.RUNNING) {
                        return
                    }
                    val completed = dismissMirrorSurfaceLocked(
                        state.copy(
                            status = SessionStatus.COMPLETED,
                            waitingQuestion = null,
                            automationStep = step,
                            lastError = null,
                            pendingReturnToVirtualDisplay = false
                        ),
                        reason = "session_completed"
                    )
                    activeSession = completed
                    logInfo(
                        event = "session_completed",
                        state = completed,
                        step = step,
                        actionType = "DONE",
                        code = "COMPLETED",
                        extras = "reason=$reason"
                    )
                }
            }

            override fun onSessionFailed(sessionId: String, step: Int, code: String, details: String) {
                synchronized(lock) {
                    val state = activeSession ?: return
                    if (state.sessionId != sessionId) {
                        return
                    }
                    if (state.status == SessionStatus.STOPPING ||
                        state.status == SessionStatus.MIGRATED ||
                        state.status == SessionStatus.CLOSED
                    ) {
                        return
                    }
                    val failed = dismissMirrorSurfaceLocked(
                        state.copy(
                            status = SessionStatus.FAILED,
                            waitingQuestion = null,
                            automationStep = step,
                            lastError = code,
                            pendingReturnToVirtualDisplay = false
                        ),
                        reason = "session_failed"
                    )
                    activeSession = failed
                    logError(
                        event = "session_failed",
                        state = failed,
                        step = step,
                        actionType = "NONE",
                        code = code,
                        extras = "details=$details"
                    )
                }
            }
        },
        observationProvider = observationProvider,
        planningAdapter = planningAdapter,
        actionExecutor = actionExecutor,
        history = automationHistory
    )

    fun startSession(
        command: String,
        options: SessionStartOptions = SessionStartOptions()
    ): SessionResult = synchronized(lock) {
        val sessionId = UUID.randomUUID().toString()
        val resolvedMode = options.resolvedDisplayMode()
        logInfo(
            event = "start_session_begin",
            sessionId = sessionId,
            executionDisplayId = null,
            virtualDisplayId = null,
            surfaceDisplayId = null,
            sessionTaskId = INVALID_TASK_ID,
            step = 0,
            actionType = "NONE",
            code = "START_BEGIN",
            extras = "command=$command requestedDisplayMode=${options.displayMode.name} " +
                "resolvedDisplayMode=${resolvedMode.name} visibleToUser=${options.visibleToUser} " +
                "returnToVirtualAfterUser=${options.returnToVirtualAfterUser} " +
                "bootstrapTarget=${options.bootstrapTarget.name}"
        )

        if (resolvedMode == SessionDisplayMode.VIRTUAL_DISPLAY && !supportsSecondaryDisplays()) {
            return@synchronized failResult("SECONDARY_DISPLAY_UNSUPPORTED")
        }

        if (activeSession != null) {
            Log.i(TAG, "startSession preempt existing sessionId=${activeSession?.sessionId}")
            stopSessionLocked(migrate = false, preemptive = true)
        }

        val runtime = if (resolvedMode == SessionDisplayMode.VIRTUAL_DISPLAY) {
            virtualDisplayRuntime.create()
                ?: return@synchronized failAndCleanupLocked(
                    code = "VD_CREATE_FAILED",
                    state = null
                )
        } else {
            null
        }
        val executionDisplayId = runtime?.vdDisplayId ?: Display.DEFAULT_DISPLAY
        var state = SessionState(
            sessionId = sessionId,
            command = command,
            executionDisplayId = executionDisplayId,
            virtualDisplayId = runtime?.vdDisplayId,
            surfaceDisplayId = if (resolvedMode == SessionDisplayMode.MAIN_DISPLAY) {
                Display.DEFAULT_DISPLAY
            } else {
                null
            },
            sessionTaskId = INVALID_TASK_ID,
            status = SessionStatus.STARTING,
            lastError = null,
            waitingQuestion = null,
            automationStep = 0,
            displayMode = resolvedMode,
            visibleToUser = resolvedMode == SessionDisplayMode.MAIN_DISPLAY,
            backgroundPreferred = resolvedMode != SessionDisplayMode.MAIN_DISPLAY,
            returnToVirtualAfterUser = options.returnToVirtualAfterUser,
            pendingReturnToVirtualDisplay = false,
            bootstrapTarget = options.bootstrapTarget,
            lastBootstrapCode = null
        )
        activeRuntime = runtime
        activeSession = state

        runtime?.let {
            logInfo(
                event = "virtual_display_created",
                state = state,
                step = 0,
                actionType = "NONE",
                code = "VD_CREATED",
                extras = "virtualDisplayId=${it.vdDisplayId} command=$command"
            )
        }
        logInfo(
            event = "session_display_mode_selected",
            state = state,
            step = 0,
            actionType = "NONE",
            code = "DISPLAY_MODE_SELECTED",
            extras = "displayMode=${resolvedMode.name} executionDisplayId=$executionDisplayId"
        )

        val debugStartInjection = SessionAuditHooks.consumeStartInjection()
        activePlannerConfig = debugStartInjection.plannerConfigOverride ?: PlannerConfig()
        val launch = launchPrimaryRootLocked(state, debugStartInjection)
        if (!launch.ok) {
            return@synchronized failAndCleanupLocked(code = launch.code, state = state)
        }

        val ack = SessionBridge.await(sessionId, attachTimeoutMs)
            ?: return@synchronized failAndCleanupLocked(
                code = "SESSION_ATTACH_TIMEOUT",
                state = state
            )
        if (ack.displayId != executionDisplayId) {
            logError(
                event = "session_display_mismatch",
                state = state,
                step = 0,
                actionType = "NONE",
                code = "SESSION_DISPLAY_MISMATCH",
                extras = "reportedDisplayId=${ack.displayId}"
            )
            return@synchronized failAndCleanupLocked(
                code = "SESSION_DISPLAY_MISMATCH",
                state = state
            )
        }
        if (ack.taskId <= 0) {
            return@synchronized failAndCleanupLocked(
                code = "SESSION_ROOT_LAUNCH_FAILED",
                state = state
            )
        }

        state = state.copy(
            sessionTaskId = ack.taskId,
            status = SessionStatus.READY,
            lastError = null,
            waitingQuestion = null,
            automationStep = 0
        )
        activeSession = state
        logInfo(
            event = "session_ready",
            state = state,
            step = 0,
            actionType = "NONE",
            code = "SESSION_READY",
            extras = "component=${ack.component}"
        )

        state = bootstrapExecutionDisplayLocked(state)
        activeSession = state

        if (resolvedMode == SessionDisplayMode.VIRTUAL_DISPLAY && options.visibleToUser) {
            state = showSessionOnMainLocked(state, reason = "start_visible")
            activeSession = state
        }

        okResult(
            code = "SESSION_READY",
            state = state,
            extra = mapOf("component" to ack.component)
        )
    }

    fun startAutomation(sessionId: String): SessionResult = synchronized(lock) {
        val state = activeSession ?: return@synchronized failResult("NO_ACTIVE_SESSION")
        if (state.sessionId != sessionId) {
            return@synchronized failResult(
                code = "SESSION_ID_MISMATCH",
                data = mapOf("expected" to state.sessionId, "actual" to sessionId)
            )
        }
        if (state.status != SessionStatus.READY) {
            return@synchronized failResult(
                code = "SESSION_NOT_READY",
                data = stateData(state)
            )
        }
        if (state.sessionTaskId <= 0) {
            return@synchronized failResult(
                code = "SESSION_TASK_INVALID",
                data = stateData(state)
            )
        }

        val running = state.copy(
            status = SessionStatus.RUNNING,
            waitingQuestion = null,
            automationStep = 0,
            lastError = null
        )
        activeSession = running
        if (running.bootstrapTarget != SessionBootstrapTarget.NONE) {
            logInfo(
                event = "automation_begin_after_bootstrap",
                state = running,
                step = 0,
                actionType = "NONE",
                code = "AUTOMATION_BEGIN_AFTER_BOOTSTRAP",
                extras = "bootstrapTarget=${running.bootstrapTarget.name} " +
                    "bootstrapCode=${running.lastBootstrapCode ?: "NOT_ATTEMPTED"}"
            )
        }
        val result = automationEngine.startAutomation(sessionId)
        return@synchronized if (result.ok) {
            logInfo(
                event = "automation_start",
                state = running,
                step = 0,
                actionType = "NONE",
                code = result.code,
                extras = "details=${result.details}"
            )
            okResult(
                code = result.code,
                state = running,
                extra = mapOf("details" to result.details)
            )
        } else {
            val failed = running.copy(
                status = SessionStatus.FAILED,
                lastError = result.code
            )
            activeSession = failed
            logError(
                event = "session_failed",
                state = failed,
                step = failed.automationStep,
                actionType = "NONE",
                code = result.code,
                extras = "details=${result.details}"
            )
            failResult(result.code, stateData(failed) + mapOf("details" to result.details))
        }
    }

    fun replyToSession(sessionId: String, userText: String): SessionResult = synchronized(lock) {
        val state = activeSession ?: return@synchronized failResult("NO_ACTIVE_SESSION")
        if (state.sessionId != sessionId) {
            return@synchronized failResult(
                code = "SESSION_ID_MISMATCH",
                data = mapOf("expected" to state.sessionId, "actual" to sessionId)
            )
        }
        if (state.status != SessionStatus.WAITING_USER) {
            return@synchronized failResult(
                code = "SESSION_NOT_WAITING_USER",
                data = stateData(state)
            )
        }
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            return@synchronized failResult(
                code = "USER_REPLY_EMPTY",
                data = stateData(state)
            )
        }

        val preResume = if (state.pendingReturnToVirtualDisplay) {
            dismissMirrorSurfaceLocked(state, reason = "reply_received")
        } else {
            state
        }
        val running = preResume.copy(
            status = SessionStatus.RUNNING,
            waitingQuestion = null,
            lastError = null,
            pendingReturnToVirtualDisplay = false
        )
        activeSession = running
        val result = automationEngine.replyToSession(sessionId, trimmed)
        return@synchronized if (result.ok) {
            logInfo(
                event = "automation_resumed_after_reply",
                state = running,
                step = running.automationStep,
                actionType = "NONE",
                code = "AUTOMATION_RESUMED",
                extras = "details=${result.details}"
            )
            okResult(
                code = "AUTOMATION_RESUMED",
                state = running,
                extra = mapOf("details" to result.details)
            )
        } else {
            val failed = running.copy(
                status = SessionStatus.FAILED,
                lastError = result.code
            )
            activeSession = failed
            logError(
                event = "session_failed",
                state = failed,
                step = failed.automationStep,
                actionType = "NONE",
                code = result.code,
                extras = "details=${result.details}"
            )
            failResult(result.code, stateData(failed) + mapOf("details" to result.details))
        }
    }

    fun showSessionOnMain(sessionId: String? = null): SessionResult = synchronized(lock) {
        val state = requireActiveSessionLocked(sessionId) ?: return@synchronized failResult("NO_ACTIVE_SESSION")
        if (state.displayMode == SessionDisplayMode.MAIN_DISPLAY) {
            return@synchronized okResult("SESSION_ALREADY_ON_MAIN_DISPLAY", state)
        }
        if (state.visibleToUser && state.surfaceDisplayId == Display.DEFAULT_DISPLAY) {
            return@synchronized okResult("SESSION_ALREADY_SURFACED_ON_MAIN", state)
        }
        val updated = showSessionOnMainLocked(state, reason = "debug_request")
        activeSession = updated
        return@synchronized if (updated.visibleToUser) {
            okResult("SESSION_SURFACED_ON_MAIN", updated)
        } else {
            failResult("SESSION_SURFACE_LAUNCH_FAILED", stateData(updated))
        }
    }

    fun returnSessionToVirtual(sessionId: String? = null): SessionResult = synchronized(lock) {
        val state = requireActiveSessionLocked(sessionId) ?: return@synchronized failResult("NO_ACTIVE_SESSION")
        if (state.virtualDisplayId == null) {
            return@synchronized failResult("SESSION_NOT_VIRTUAL_DISPLAY", stateData(state))
        }
        if (!state.visibleToUser || state.surfaceDisplayId == null) {
            val background = state.copy(pendingReturnToVirtualDisplay = false)
            activeSession = background
            return@synchronized okResult("SESSION_ALREADY_ON_VIRTUAL_DISPLAY", background)
        }
        val updated = dismissMirrorSurfaceLocked(state, reason = "debug_request").copy(
            pendingReturnToVirtualDisplay = false
        )
        activeSession = updated
        return@synchronized okResult("SESSION_RETURNED_TO_VIRTUAL", updated)
    }

    fun markRunning(sessionId: String? = null): SessionResult = synchronized(lock) {
        val state = activeSession ?: return@synchronized failResult("NO_ACTIVE_SESSION")
        val targetSessionId = sessionId ?: state.sessionId
        startAutomation(targetSessionId)
    }

    fun stopSession(migrate: Boolean): SessionResult = synchronized(lock) {
        stopSessionLocked(migrate = migrate, preemptive = false)
    }

    fun dumpActiveSession(): SessionResult = synchronized(lock) {
        val state = activeSession
        return@synchronized if (state == null) {
            logInfo(
                event = "dump_active_session",
                sessionId = null,
                executionDisplayId = null,
                virtualDisplayId = null,
                surfaceDisplayId = null,
                sessionTaskId = INVALID_TASK_ID,
                step = 0,
                actionType = "NONE",
                code = "NO_ACTIVE_SESSION"
            )
            failResult(code = "NO_ACTIVE_SESSION")
        } else {
            logInfo(
                event = "dump_active_session",
                state = state,
                step = state.automationStep,
                actionType = "NONE",
                code = "DUMP_ACTIVE_SESSION",
                extras = "displayMode=${state.displayMode.name} visibleToUser=${state.visibleToUser} " +
                    "pendingReturnToVirtualDisplay=${state.pendingReturnToVirtualDisplay}"
            )
            okResult(code = "DUMP_ACTIVE_SESSION", state = state)
        }
    }

    fun getActiveSession(): SessionState? = synchronized(lock) { activeSession }

    private fun stopSessionLocked(migrate: Boolean, preemptive: Boolean): SessionResult {
        val state = activeSession ?: return failResult("NO_ACTIVE_SESSION")
        var stopping = state.copy(
            status = SessionStatus.STOPPING,
            lastError = null,
            waitingQuestion = null,
            pendingReturnToVirtualDisplay = false
        )
        activeSession = stopping
        logInfo(
            event = "stop_session_begin",
            state = stopping,
            step = stopping.automationStep,
            actionType = "NONE",
            code = "STOP_BEGIN",
            extras = "migrate=$migrate preemptive=$preemptive"
        )

        val stopResult = automationEngine.stopAutomation(stopping.sessionId)
        logInfo(
            event = "automation_stopped",
            state = stopping,
            step = stopping.automationStep,
            actionType = "NONE",
            code = stopResult.code,
            extras = "details=${stopResult.details}"
        )

        stopping = dismissMirrorSurfaceLocked(stopping, reason = "stop_session")
        activeSession = stopping

        val taskId = stopping.sessionTaskId
        if (taskId <= 0) {
            val code = when {
                preemptive -> "PREEMPTIVE_CLEANUP"
                migrate -> "MIGRATION_FAILED"
                else -> "CLOSE_FAILED"
            }
            return failAndCleanupLocked(
                code = code,
                state = stopping
            )
        }

        val forceMigrationFail = migrate && SessionAuditHooks.consumeForceMigrationFail()
        val success = if (migrate) {
            if (forceMigrationFail) {
                logInfo(
                    event = "migration_forced_failure",
                    state = stopping,
                    step = stopping.automationStep,
                    actionType = "NONE",
                    code = "FORCED_MIGRATION_FAILURE"
                )
                false
            } else {
                taskMigrationController.moveTaskToDefaultDisplay(taskId)
            }
        } else {
            taskMigrationController.closeTask(taskId)
        }

        return if (success) {
            val finalStatus = if (migrate) SessionStatus.MIGRATED else SessionStatus.CLOSED
            val finalCode = if (migrate) "MIGRATED_TO_DEFAULT_DISPLAY" else "CLOSED"
            val finalState = stopping.copy(
                executionDisplayId = if (migrate) Display.DEFAULT_DISPLAY else stopping.executionDisplayId,
                surfaceDisplayId = if (migrate && stopping.displayMode == SessionDisplayMode.MAIN_DISPLAY) {
                    Display.DEFAULT_DISPLAY
                } else {
                    null
                },
                status = finalStatus,
                lastError = null,
                waitingQuestion = null,
                visibleToUser = migrate && stopping.displayMode == SessionDisplayMode.MAIN_DISPLAY,
                pendingReturnToVirtualDisplay = false
            )
            activeSession = finalState
            logInfo(
                event = if (migrate) "session_migrated" else "session_closed",
                state = finalState,
                step = finalState.automationStep,
                actionType = "NONE",
                code = finalCode
            )
            val result = okResult(
                code = finalCode,
                state = finalState,
                extra = mapOf("migrate" to migrate)
            )
            cleanupLocked(stopping.sessionId)
            result
        } else {
            failAndCleanupLocked(
                code = if (migrate) "MIGRATION_FAILED" else "CLOSE_FAILED",
                state = stopping
            )
        }
    }

    private fun launchPrimaryRootLocked(
        state: SessionState,
        debugStartInjection: SessionAuditHooks.StartInjection
    ): SessionResult {
        SessionBridge.registerWaiter(state.sessionId)
        val intent = buildSessionRootIntent(
            state = state,
            rootRole = SessionRootActivity.ROOT_ROLE_PRIMARY
        ).apply {
            if (debugStartInjection.skipAttach) {
                putExtra(SessionRootActivity.EXTRA_DEBUG_SKIP_ATTACH, true)
            }
            debugStartInjection.forceReportDisplayId?.let {
                putExtra(SessionRootActivity.EXTRA_DEBUG_FORCE_REPORT_DISPLAY_ID, it)
            }
        }
        val options = LaunchOptionsCompat.build(state.executionDisplayId)
            ?: return failResult("SESSION_ROOT_INFEASIBLE", stateData(state))

        return try {
            appContext.startActivity(intent, options.toBundle())
            okResult("SESSION_ROOT_LAUNCHED", state)
        } catch (t: Throwable) {
            val infeasible = t is IllegalArgumentException ||
                t is IllegalStateException ||
                t is UnsupportedOperationException
            val code = if (infeasible) "SESSION_ROOT_INFEASIBLE" else "SESSION_ROOT_LAUNCH_FAILED"
            logError(
                event = "session_root_launch_failed",
                state = state,
                step = 0,
                actionType = "NONE",
                code = code,
                extras = "command=${state.command} displayMode=${state.displayMode.name}",
                error = t
            )
            failResult(code, stateData(state))
        }
    }

    private fun bootstrapExecutionDisplayLocked(state: SessionState): SessionState {
        if (state.bootstrapTarget == SessionBootstrapTarget.NONE) {
            return state
        }
        val intentAction = when (state.bootstrapTarget) {
            SessionBootstrapTarget.SETTINGS -> Settings.ACTION_SETTINGS
            SessionBootstrapTarget.NONE -> return state
        }
        logInfo(
            event = "execution_display_bootstrap_begin",
            state = state,
            step = 0,
            actionType = "NONE",
            code = "EXECUTION_DISPLAY_BOOTSTRAP_BEGIN",
            extras = "bootstrapTarget=${state.bootstrapTarget.name} intentAction=$intentAction"
        )

        val options = LaunchOptionsCompat.build(state.executionDisplayId)
        if (options == null) {
            val updated = state.copy(lastBootstrapCode = "EXECUTION_DISPLAY_BOOTSTRAP_INFEASIBLE")
            logError(
                event = "execution_display_bootstrap_failed",
                state = updated,
                step = 0,
                actionType = "NONE",
                code = "EXECUTION_DISPLAY_BOOTSTRAP_INFEASIBLE",
                extras = "bootstrapTarget=${state.bootstrapTarget.name} reason=launch_options_unavailable"
            )
            return updated
        }

        val intent = Intent(intentAction).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent, options.toBundle())
            val updated = state.copy(lastBootstrapCode = "EXECUTION_DISPLAY_BOOTSTRAP_OK")
            logInfo(
                event = "execution_display_bootstrap_success",
                state = updated,
                step = 0,
                actionType = "NONE",
                code = "EXECUTION_DISPLAY_BOOTSTRAP_OK",
                extras = "bootstrapTarget=${state.bootstrapTarget.name} intentAction=$intentAction"
            )
            updated
        } catch (t: Throwable) {
            val infeasible = t is IllegalArgumentException ||
                t is IllegalStateException ||
                t is UnsupportedOperationException
            val code = if (infeasible) {
                "EXECUTION_DISPLAY_BOOTSTRAP_INFEASIBLE"
            } else {
                "EXECUTION_DISPLAY_BOOTSTRAP_FAILED"
            }
            val updated = state.copy(lastBootstrapCode = code)
            logError(
                event = "execution_display_bootstrap_failed",
                state = updated,
                step = 0,
                actionType = "NONE",
                code = code,
                extras = "bootstrapTarget=${state.bootstrapTarget.name} intentAction=$intentAction",
                error = t
            )
            updated
        }
    }

    private fun showSessionOnMainLocked(state: SessionState, reason: String): SessionState {
        if (state.displayMode == SessionDisplayMode.MAIN_DISPLAY) {
            return state.copy(
                visibleToUser = true,
                surfaceDisplayId = Display.DEFAULT_DISPLAY
            )
        }
        if (state.visibleToUser && state.surfaceDisplayId == Display.DEFAULT_DISPLAY) {
            return state
        }

        SessionSurfaceRegistry.finish(state.sessionId)
        val surfaced = state.copy(
            visibleToUser = true,
            surfaceDisplayId = Display.DEFAULT_DISPLAY
        )
        val intent = buildSessionRootIntent(
            state = surfaced,
            rootRole = SessionRootActivity.ROOT_ROLE_MIRROR
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val options = LaunchOptionsCompat.build(Display.DEFAULT_DISPLAY)
        if (options == null) {
            logError(
                event = "session_surface_launch_failed",
                state = state,
                code = "SESSION_SURFACE_INFEASIBLE",
                extras = "reason=$reason"
            )
            return state
        }

        return try {
            appContext.startActivity(intent, options.toBundle())
            logInfo(
                event = "session_surface_on_main",
                state = surfaced,
                code = "SESSION_SURFACED_ON_MAIN",
                extras = "reason=$reason status=${state.status.name}"
            )
            surfaced
        } catch (t: Throwable) {
            logError(
                event = "session_surface_launch_failed",
                state = state,
                code = "SESSION_SURFACE_LAUNCH_FAILED",
                extras = "reason=$reason",
                error = t
            )
            state
        }
    }

    private fun dismissMirrorSurfaceLocked(state: SessionState, reason: String): SessionState {
        if (state.displayMode == SessionDisplayMode.MAIN_DISPLAY) {
            return state.copy(pendingReturnToVirtualDisplay = false)
        }
        if (!state.visibleToUser || state.surfaceDisplayId == null) {
            return state.copy(
                visibleToUser = false,
                surfaceDisplayId = null,
                pendingReturnToVirtualDisplay = false
            )
        }
        SessionSurfaceRegistry.finish(state.sessionId)
        val updated = state.copy(
            visibleToUser = false,
            surfaceDisplayId = null,
            pendingReturnToVirtualDisplay = false
        )
        logInfo(
            event = "session_surface_dismissed",
            state = updated,
            code = "SESSION_RETURNED_TO_VIRTUAL",
            extras = "reason=$reason status=${state.status.name}"
        )
        return updated
    }

    private fun buildSessionRootIntent(state: SessionState, rootRole: String): Intent {
        return Intent(appContext, SessionRootActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SessionRootActivity.EXTRA_SESSION_ID, state.sessionId)
            putExtra(SessionRootActivity.EXTRA_EXECUTION_DISPLAY_ID, state.executionDisplayId)
            state.virtualDisplayId?.let {
                putExtra(SessionRootActivity.EXTRA_VIRTUAL_DISPLAY_ID, it)
            }
            state.surfaceDisplayId?.let {
                putExtra(SessionRootActivity.EXTRA_SURFACE_DISPLAY_ID, it)
            }
            putExtra(SessionRootActivity.EXTRA_COMMAND, state.command)
            putExtra(SessionRootActivity.EXTRA_ROOT_ROLE, rootRole)
            putExtra(SessionRootActivity.EXTRA_STATUS, state.status.name)
            putExtra(SessionRootActivity.EXTRA_WAITING_QUESTION, state.waitingQuestion)
            putExtra(SessionRootActivity.EXTRA_DISPLAY_MODE, state.displayMode.name)
        }
    }

    private fun failAndCleanupLocked(code: String, state: SessionState?): SessionResult {
        if (state != null) {
            val failedBase = dismissMirrorSurfaceLocked(state, reason = "fail_cleanup")
            val failedState = failedBase.copy(
                status = SessionStatus.FAILED,
                lastError = code,
                waitingQuestion = null,
                pendingReturnToVirtualDisplay = false
            )
            activeSession = failedState
            logError(
                event = "session_failed",
                state = failedState,
                step = failedState.automationStep,
                actionType = "NONE",
                code = code
            )
            cleanupLocked(state.sessionId)
            return failResult(code = code, data = stateData(failedState))
        }
        cleanupLocked(sessionId = null)
        logError(
            event = "session_failed",
            sessionId = null,
            executionDisplayId = null,
            virtualDisplayId = null,
            surfaceDisplayId = null,
            sessionTaskId = INVALID_TASK_ID,
            step = 0,
            actionType = "NONE",
            code = code
        )
        return failResult(code = code)
    }

    private fun cleanupLocked(sessionId: String?) {
        sessionId?.let {
            SessionSurfaceRegistry.finish(it)
            SessionBridge.clear(it)
            automationEngine.clearSession(it)
        }
        activePlannerConfig = PlannerConfig()
        activeRuntime?.release()
        activeRuntime = null
        activeSession = null
    }

    private fun supportsSecondaryDisplays(): Boolean {
        return appContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
        )
    }

    private fun requireActiveSessionLocked(sessionId: String?): SessionState? {
        val state = activeSession ?: return null
        return if (sessionId.isNullOrBlank() || sessionId == state.sessionId) {
            state
        } else {
            null
        }
    }

    private fun okResult(
        code: String,
        state: SessionState?,
        extra: Map<String, Any?> = emptyMap()
    ): SessionResult {
        val merged = if (state == null) {
            extra
        } else {
            stateData(state) + extra
        }
        return SessionResult(ok = true, code = code, data = merged)
    }

    private fun failResult(code: String, data: Map<String, Any?> = emptyMap()): SessionResult {
        return SessionResult(ok = false, code = code, data = data)
    }

    private fun stateData(state: SessionState): Map<String, Any?> {
        return mapOf(
            "sessionId" to state.sessionId,
            "displayId" to state.executionDisplayId,
            "executionDisplayId" to state.executionDisplayId,
            "virtualDisplayId" to state.virtualDisplayId,
            "surfaceDisplayId" to state.surfaceDisplayId,
            "vdDisplayId" to state.virtualDisplayId,
            "taskId" to state.sessionTaskId,
            "status" to state.status.name,
            "command" to state.command,
            "displayMode" to state.displayMode.name,
            "visibleToUser" to state.visibleToUser,
            "backgroundPreferred" to state.backgroundPreferred,
            "returnToVirtualAfterUser" to state.returnToVirtualAfterUser,
            "pendingReturnToVirtualDisplay" to state.pendingReturnToVirtualDisplay,
            "bootstrapTarget" to state.bootstrapTarget.name,
            "lastBootstrapCode" to state.lastBootstrapCode,
            "lastError" to state.lastError,
            "waitingQuestion" to state.waitingQuestion,
            "automationStep" to state.automationStep,
            "plannerMode" to activePlannerConfig.mode.name,
            "actionType" to "NONE"
        )
    }

    private fun logInfo(
        event: String,
        state: SessionState? = null,
        sessionId: String? = null,
        executionDisplayId: Int? = null,
        virtualDisplayId: Int? = null,
        surfaceDisplayId: Int? = null,
        sessionTaskId: Int? = null,
        step: Int? = null,
        actionType: String? = null,
        code: String,
        extras: String? = null
    ) {
        Log.i(
            TAG,
            buildLogLine(
                event = event,
                state = state,
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                virtualDisplayId = virtualDisplayId,
                surfaceDisplayId = surfaceDisplayId,
                sessionTaskId = sessionTaskId,
                step = step,
                actionType = actionType,
                code = code,
                extras = extras
            )
        )
    }

    private fun logError(
        event: String,
        state: SessionState? = null,
        sessionId: String? = null,
        executionDisplayId: Int? = null,
        virtualDisplayId: Int? = null,
        surfaceDisplayId: Int? = null,
        sessionTaskId: Int? = null,
        step: Int? = null,
        actionType: String? = null,
        code: String,
        extras: String? = null,
        error: Throwable? = null
    ) {
        val logLine = buildLogLine(
            event = event,
            state = state,
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            virtualDisplayId = virtualDisplayId,
            surfaceDisplayId = surfaceDisplayId,
            sessionTaskId = sessionTaskId,
            step = step,
            actionType = actionType,
            code = code,
            extras = extras
        )
        if (error == null) {
            Log.e(TAG, logLine)
        } else {
            Log.e(TAG, logLine, error)
        }
    }

    private fun buildLogLine(
        event: String,
        state: SessionState?,
        sessionId: String?,
        executionDisplayId: Int?,
        virtualDisplayId: Int?,
        surfaceDisplayId: Int?,
        sessionTaskId: Int?,
        step: Int?,
        actionType: String?,
        code: String,
        extras: String?
    ): String {
        val sid = sessionId ?: state?.sessionId ?: "null"
        val executionId = executionDisplayId ?: state?.executionDisplayId ?: -1
        val virtualId = virtualDisplayId ?: state?.virtualDisplayId
        val surfaceId = surfaceDisplayId ?: state?.surfaceDisplayId
        val tid = sessionTaskId ?: state?.sessionTaskId ?: INVALID_TASK_ID
        val stp = step ?: state?.automationStep ?: 0
        val at = actionType ?: "NONE"
        val ext = extras ?: "{}"
        return buildString {
            append("event=").append(event)
            append(" sessionId=").append(sid)
            append(" executionDisplayId=").append(executionId)
            append(" virtualDisplayId=").append(virtualId ?: "null")
            append(" surfaceDisplayId=").append(surfaceId ?: "null")
            append(" sessionTaskId=").append(tid)
            append(" step=").append(stp)
            append(" actionType=").append(at)
            append(" code=").append(code)
            append(" extras=").append(ext)
            if (virtualId != null) {
                append(" vdDisplayId=").append(virtualId)
            }
        }
    }
}
