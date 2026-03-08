package com.example.agenthost.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.agenthost.automation.PlanningMode
import com.example.agenthost.config.ModelConfig
import com.example.agenthost.config.PlannerConfig
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
        private const val EXTRA_PLANNER_MODE = "planner_mode"
        private const val EXTRA_MODEL_ENDPOINT = "model_endpoint"
        private const val EXTRA_MODEL_API_KEY = "model_api_key"
        private const val EXTRA_MODEL_API_KEY_ENV = "model_api_key_env"
        private const val EXTRA_MODEL_TIMEOUT_MS = "model_timeout_ms"
        private const val EXTRA_MODEL_MAX_RETRIES = "model_max_retries"
        private const val EXTRA_MODEL_MAX_RESPONSE_CHARS = "model_max_response_chars"
        private const val EXTRA_MOCK_MODEL_RESPONSE = "mock_model_response"

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
                val plannerConfig = buildPlannerConfigOverride(intent)
                SessionAuditHooks.configureNextStartInjection(
                    skipAttach = skipAttach,
                    forceReportDisplayId = forceReportDisplayId,
                    plannerConfigOverride = plannerConfig
                )
                val result = startWithAutomation(context, command)
                logResult(
                    event = "debug_start_session",
                    result = result,
                    extras = "skipAttach=$skipAttach forceReportDisplayId=$forceReportDisplayId " +
                        "plannerMode=${plannerConfig?.mode?.name ?: "DEFAULT"} " +
                        "modelConfig=${plannerConfig?.modelConfig?.sanitizedSummary() ?: "default"}"
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

    private fun buildPlannerConfigOverride(intent: Intent): PlannerConfig? {
        val rawMode = intent.getStringExtra(EXTRA_PLANNER_MODE)?.trim().orEmpty()
        if (rawMode.isEmpty()) {
            return null
        }
        val mode = runCatching { PlanningMode.valueOf(rawMode.uppercase()) }.getOrElse {
            Log.w(TAG, "event=debug_invalid_planner_mode code=INVALID_PLANNER_MODE extras=value=$rawMode")
            return null
        }
        return PlannerConfig(
            mode = mode,
            modelConfig = ModelConfig(
                endpoint = intent.getStringExtra(EXTRA_MODEL_ENDPOINT),
                apiKey = intent.getStringExtra(EXTRA_MODEL_API_KEY),
                apiKeyEnvName = intent.getStringExtra(EXTRA_MODEL_API_KEY_ENV),
                timeoutMs = readLongExtra(intent, EXTRA_MODEL_TIMEOUT_MS, 10_000L),
                maxRetries = readIntExtra(intent, EXTRA_MODEL_MAX_RETRIES, 0),
                maxResponseChars = readIntExtra(intent, EXTRA_MODEL_MAX_RESPONSE_CHARS, 4_096),
                mockResponse = intent.getStringExtra(EXTRA_MOCK_MODEL_RESPONSE)
            )
        )
    }

    private fun readLongExtra(intent: Intent, key: String, defaultValue: Long): Long {
        intent.getStringExtra(key)?.toLongOrNull()?.let {
            return it
        }
        return if (intent.hasExtra(key)) intent.getLongExtra(key, defaultValue) else defaultValue
    }

    private fun readIntExtra(intent: Intent, key: String, defaultValue: Int): Int {
        intent.getStringExtra(key)?.toIntOrNull()?.let {
            return it
        }
        return if (intent.hasExtra(key)) intent.getIntExtra(key, defaultValue) else defaultValue
    }

    private fun logResult(event: String, result: SessionResult, extras: String) {
        val sessionId = result.data["sessionId"] ?: "null"
        val vdDisplayId = result.data["displayId"] ?: -1
        val sessionTaskId = result.data["taskId"] ?: -1
        val step = result.data["automationStep"] ?: 0
        val plannerMode = result.data["plannerMode"] ?: "UNKNOWN"
        val actionType = result.data["actionType"] ?: "NONE"
        val status = result.data["status"] ?: "null"
        val waitingQuestion = (result.data["waitingQuestion"] ?: "null").toString()
            .replace("\n", "\\n")
        Log.i(
            TAG,
            "event=$event sessionId=$sessionId vdDisplayId=$vdDisplayId " +
                "sessionTaskId=$sessionTaskId step=$step plannerMode=$plannerMode actionType=$actionType " +
                "status=$status waitingQuestion=$waitingQuestion " +
                "code=${result.code} extras=ok=${result.ok} $extras"
        )
    }
}
