package com.example.agenthost.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
                        vdDisplayId = state.vdDisplayId,
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
                    val waiting = state.copy(
                        status = SessionStatus.WAITING_USER,
                        waitingQuestion = question,
                        automationStep = step,
                        lastError = null
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
                }
            }

            override fun onSessionCompleted(sessionId: String, step: Int, reason: String) {
                synchronized(lock) {
                    val state = activeSession ?: return
                    if (state.sessionId != sessionId || state.status != SessionStatus.RUNNING) {
                        return
                    }
                    val completed = state.copy(
                        status = SessionStatus.COMPLETED,
                        waitingQuestion = null,
                        automationStep = step,
                        lastError = null
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
                    val failed = state.copy(
                        status = SessionStatus.FAILED,
                        waitingQuestion = null,
                        automationStep = step,
                        lastError = code
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

    fun startSession(command: String): SessionResult = synchronized(lock) {
        val sessionId = UUID.randomUUID().toString()
        logInfo(
            event = "start_session_begin",
            sessionId = sessionId,
            vdDisplayId = -1,
            sessionTaskId = INVALID_TASK_ID,
            step = 0,
            actionType = "NONE",
            code = "START_BEGIN",
            extras = "command=$command"
        )
        if (!supportsSecondaryDisplays()) {
            return@synchronized failResult("SECONDARY_DISPLAY_UNSUPPORTED")
        }

        if (activeSession != null) {
            Log.i(TAG, "startSession preempt existing sessionId=${activeSession?.sessionId}")
            stopSessionLocked(migrate = false, preemptive = true)
        }

        val runtime = virtualDisplayRuntime.create()
            ?: return@synchronized failAndCleanupLocked(
                code = "VD_CREATE_FAILED",
                state = null
            )

        val vdDisplayId = runtime.vdDisplayId
        var state = SessionState(
            sessionId = sessionId,
            command = command,
            vdDisplayId = vdDisplayId,
            sessionTaskId = INVALID_TASK_ID,
            status = SessionStatus.STARTING,
            lastError = null,
            waitingQuestion = null,
            automationStep = 0
        )
        activeRuntime = runtime
        activeSession = state
        SessionBridge.registerWaiter(sessionId)
        logInfo(
            event = "vd_created",
            state = state,
            step = 0,
            actionType = "NONE",
            code = "VD_CREATED",
            extras = "command=$command"
        )

        val debugStartInjection = SessionAuditHooks.consumeStartInjection()
        activePlannerConfig = debugStartInjection.plannerConfigOverride ?: PlannerConfig()
        val intent = Intent(appContext, SessionRootActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SessionRootActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(SessionRootActivity.EXTRA_VD_DISPLAY_ID, vdDisplayId)
            putExtra(SessionRootActivity.EXTRA_COMMAND, command)
            if (debugStartInjection.skipAttach) {
                putExtra(SessionRootActivity.EXTRA_DEBUG_SKIP_ATTACH, true)
            }
            debugStartInjection.forceReportDisplayId?.let {
                putExtra(SessionRootActivity.EXTRA_DEBUG_FORCE_REPORT_DISPLAY_ID, it)
            }
        }

        val options = LaunchOptionsCompat.build(vdDisplayId)
            ?: return@synchronized failAndCleanupLocked(
                code = "SESSION_ROOT_INFEASIBLE",
                state = state
            )

        try {
            appContext.startActivity(intent, options.toBundle())
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
                extras = "command=$command",
                error = t
            )
            return@synchronized failAndCleanupLocked(code = code, state = state)
        }

        val ack = SessionBridge.await(sessionId, attachTimeoutMs)
            ?: return@synchronized failAndCleanupLocked(
                code = "SESSION_ATTACH_TIMEOUT",
                state = state
            )

        if (ack.displayId != vdDisplayId) {
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

        val running = state.copy(
            status = SessionStatus.RUNNING,
            waitingQuestion = null,
            lastError = null
        )
        activeSession = running
        val result = automationEngine.replyToSession(sessionId, trimmed)
        return@synchronized if (result.ok) {
            logInfo(
                event = "automation_start",
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
                vdDisplayId = -1,
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
                code = "DUMP_ACTIVE_SESSION"
            )
            okResult(code = "DUMP_ACTIVE_SESSION", state = state)
        }
    }

    fun getActiveSession(): SessionState? = synchronized(lock) { activeSession }

    private fun stopSessionLocked(migrate: Boolean, preemptive: Boolean): SessionResult {
        val state = activeSession ?: return failResult("NO_ACTIVE_SESSION")
        val stopping = state.copy(
            status = SessionStatus.STOPPING,
            lastError = null,
            waitingQuestion = null
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
                status = finalStatus,
                lastError = null,
                waitingQuestion = null
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

    private fun failAndCleanupLocked(code: String, state: SessionState?): SessionResult {
        if (state != null) {
            val failedState = state.copy(
                status = SessionStatus.FAILED,
                lastError = code,
                waitingQuestion = null
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
            vdDisplayId = -1,
            sessionTaskId = INVALID_TASK_ID,
            step = 0,
            actionType = "NONE",
            code = code
        )
        return failResult(code = code)
    }

    private fun cleanupLocked(sessionId: String?) {
        sessionId?.let {
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
            "displayId" to state.vdDisplayId,
            "taskId" to state.sessionTaskId,
            "status" to state.status.name,
            "command" to state.command,
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
        vdDisplayId: Int? = null,
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
                vdDisplayId = vdDisplayId,
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
        vdDisplayId: Int? = null,
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
            vdDisplayId = vdDisplayId,
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
        vdDisplayId: Int?,
        sessionTaskId: Int?,
        step: Int?,
        actionType: String?,
        code: String,
        extras: String?
    ): String {
        val sid = sessionId ?: state?.sessionId ?: "null"
        val did = vdDisplayId ?: state?.vdDisplayId ?: -1
        val tid = sessionTaskId ?: state?.sessionTaskId ?: INVALID_TASK_ID
        val stp = step ?: state?.automationStep ?: 0
        val at = actionType ?: "NONE"
        val ext = extras ?: "{}"
        return "event=$event sessionId=$sid vdDisplayId=$did sessionTaskId=$tid " +
            "step=$stp actionType=$at code=$code extras=$ext"
    }
}
