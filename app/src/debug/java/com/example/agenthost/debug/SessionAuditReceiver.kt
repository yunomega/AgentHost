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
                val result = AgentHostRuntime.sessionCoordinator(context).startSession(command)
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

    private fun logResult(event: String, result: SessionResult, extras: String) {
        val sessionId = result.data["sessionId"] ?: "null"
        val vdDisplayId = result.data["displayId"] ?: -1
        val sessionTaskId = result.data["taskId"] ?: -1
        Log.i(
            TAG,
            "event=$event sessionId=$sessionId vdDisplayId=$vdDisplayId " +
                "sessionTaskId=$sessionTaskId code=${result.code} extras=ok=${result.ok} $extras"
        )
    }
}
