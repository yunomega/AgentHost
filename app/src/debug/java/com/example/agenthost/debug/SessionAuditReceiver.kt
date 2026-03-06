package com.example.agenthost.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.agenthost.core.AgentHostRuntime
import com.example.agenthost.session.SessionResult
import java.util.concurrent.Executors

class SessionAuditReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SessionAuditReceiver"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_SKIP_ATTACH = "skip_attach"
        private const val EXTRA_FORCE_REPORT_DISPLAY_ID = "force_report_display_id"
        private const val EXTRA_MIGRATE = "migrate"
        private const val EXTRA_FORCE_MIGRATION_FAIL = "force_migration_fail"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_USER_TEXT = "user_text"

        private val worker = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        worker.execute {
            try {
                handleAction(context.applicationContext, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleAction(context: Context, intent: Intent?) {
        val pkg = context.packageName
        when (intent?.action) {
            "$pkg.debug.START_SESSION" -> {
                val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                val skipAttach = intent.getBooleanExtra(EXTRA_SKIP_ATTACH, false)
                val forceReportDisplayId = if (intent.hasExtra(EXTRA_FORCE_REPORT_DISPLAY_ID)) {
                    intent.getIntExtra(EXTRA_FORCE_REPORT_DISPLAY_ID, -1)
                } else {
                    null
                }
                SessionAuditHooks.configureNextStartInjection(
                    skipAttach = skipAttach,
                    forceReportDisplayId = forceReportDisplayId
                )
                val result = startWithAutomation(context, command)
                logResult(
                    event = "debug_start_session",
                    result = result,
                    extras = "skipAttach=$skipAttach forceReportDisplayId=$forceReportDisplayId"
                )
            }

            "$pkg.debug.STOP_SESSION" -> {
                val migrate = intent.getBooleanExtra(EXTRA_MIGRATE, true)
                val forceMigrationFail = intent.getBooleanExtra(EXTRA_FORCE_MIGRATION_FAIL, false)
                SessionAuditHooks.armForceMigrationFailOnce(forceMigrationFail)
                val result = AgentHostRuntime.sessionCoordinator(context).stopSession(migrate)
                logResult(
                    event = "debug_stop_session",
                    result = result,
                    extras = "migrate=$migrate forceMigrationFail=$forceMigrationFail"
                )
            }

            "$pkg.debug.REPLY_SESSION" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val userText = intent.getStringExtra(EXTRA_USER_TEXT).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context)
                    .replyToSession(sessionId, userText)
                logResult(
                    event = "debug_reply_session",
                    result = result,
                    extras = "sessionId=$sessionId userTextLength=${userText.length}"
                )
            }

            "$pkg.debug.DUMP_ACTIVE_SESSION" -> {
                val result = AgentHostRuntime.sessionCoordinator(context).dumpActiveSession()
                logResult(
                    event = "debug_dump_active_session",
                    result = result,
                    extras = "{}"
                )
            }

            else -> {
                Log.w(TAG, "event=debug_unknown_action code=UNKNOWN_ACTION extras=action=${intent?.action}")
            }
        }
    }

    private fun startWithAutomation(context: Context, command: String): SessionResult {
        val coordinator = AgentHostRuntime.sessionCoordinator(context)
        val startResult = coordinator.startSession(command)
        if (!startResult.ok) {
            return startResult
        }
        val sessionId = startResult.data["sessionId"] as? String
            ?: return SessionResult(
                ok = false,
                code = "SESSION_ID_MISSING",
                data = startResult.data
            )
        return coordinator.startAutomation(sessionId)
    }

    private fun logResult(event: String, result: SessionResult, extras: String) {
        val sessionId = result.data["sessionId"] ?: "null"
        val vdDisplayId = result.data["displayId"] ?: -1
        val sessionTaskId = result.data["taskId"] ?: -1
        val step = result.data["automationStep"] ?: 0
        val actionType = result.data["actionType"] ?: "NONE"
        Log.i(
            TAG,
            "event=$event sessionId=$sessionId vdDisplayId=$vdDisplayId " +
                "sessionTaskId=$sessionTaskId step=$step actionType=$actionType " +
                "code=${result.code} extras=ok=${result.ok} $extras"
        )
    }
}
