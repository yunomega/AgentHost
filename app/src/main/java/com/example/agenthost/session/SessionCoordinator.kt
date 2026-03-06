package com.example.agenthost.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.agenthost.debug.SessionAuditHooks
import com.example.agenthost.migration.TaskMigrationController
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

    fun startSession(command: String): SessionResult = synchronized(lock) {
        val sessionId = UUID.randomUUID().toString()
        logInfo(
            event = "start_session_begin",
            sessionId = sessionId,
            vdDisplayId = -1,
            sessionTaskId = INVALID_TASK_ID,
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
            lastError = null
        )
        activeRuntime = runtime
        activeSession = state
        SessionBridge.registerWaiter(sessionId)
        logInfo(
            event = "vd_created",
            state = state,
            code = "VD_CREATED",
            extras = "command=$command"
        )

        val debugStartInjection = SessionAuditHooks.consumeStartInjection()
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
            lastError = null
        )
        activeSession = state
        logInfo(
            event = "session_ready",
            state = state,
            code = "SESSION_READY",
            extras = "component=${ack.component}"
        )
        okResult(
            code = "SESSION_READY",
            state = state,
            extra = mapOf("component" to ack.component)
        )
    }

    fun markRunning(sessionId: String? = null): SessionResult = synchronized(lock) {
        val state = activeSession ?: return@synchronized failResult("NO_ACTIVE_SESSION")
        if (sessionId != null && state.sessionId != sessionId) {
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
        val running = state.copy(status = SessionStatus.RUNNING, lastError = null)
        activeSession = running
        logInfo(
            event = "mark_running",
            state = running,
            code = "RUNNING"
        )
        okResult(code = "RUNNING", state = running)
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
                code = "NO_ACTIVE_SESSION"
            )
            failResult(code = "NO_ACTIVE_SESSION")
        } else {
            logInfo(
                event = "dump_active_session",
                state = state,
                code = "DUMP_ACTIVE_SESSION"
            )
            okResult(code = "DUMP_ACTIVE_SESSION", state = state)
        }
    }

    fun getActiveSession(): SessionState? = synchronized(lock) { activeSession }

    private fun stopSessionLocked(migrate: Boolean, preemptive: Boolean): SessionResult {
        val state = activeSession ?: return failResult("NO_ACTIVE_SESSION")
        val stopping = state.copy(status = SessionStatus.STOPPING, lastError = null)
        activeSession = stopping
        logInfo(
            event = "stop_session_begin",
            state = stopping,
            code = "STOP_BEGIN",
            extras = "migrate=$migrate preemptive=$preemptive"
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
            activeSession = stopping.copy(status = finalStatus, lastError = null)
            logInfo(
                event = if (migrate) "session_migrated" else "session_closed",
                sessionId = stopping.sessionId,
                vdDisplayId = stopping.vdDisplayId,
                sessionTaskId = taskId,
                code = finalCode
            )
            val result = okResult(
                code = finalCode,
                state = activeSession,
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
            val failedState = state.copy(status = SessionStatus.FAILED, lastError = code)
            activeSession = failedState
            logError(
                event = "session_failed",
                state = failedState,
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
            code = code
        )
        return failResult(code = code)
    }

    private fun cleanupLocked(sessionId: String?) {
        sessionId?.let { SessionBridge.clear(it) }
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
            "lastError" to state.lastError
        )
    }

    private fun logInfo(
        event: String,
        state: SessionState? = null,
        sessionId: String? = null,
        vdDisplayId: Int? = null,
        sessionTaskId: Int? = null,
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
        code: String,
        extras: String?
    ): String {
        val sid = sessionId ?: state?.sessionId ?: "null"
        val did = vdDisplayId ?: state?.vdDisplayId ?: -1
        val tid = sessionTaskId ?: state?.sessionTaskId ?: INVALID_TASK_ID
        val ext = extras ?: "{}"
        return "event=$event sessionId=$sid vdDisplayId=$did sessionTaskId=$tid code=$code extras=$ext"
    }
}
