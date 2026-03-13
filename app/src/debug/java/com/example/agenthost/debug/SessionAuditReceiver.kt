package com.example.agenthost.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.agenthost.automation.PlanningMode
import com.example.agenthost.config.ModelConfig
import com.example.agenthost.config.PlannerConfig
import com.example.agenthost.core.AgentHostRuntime
import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionStartOptions
import com.example.agenthost.session.SessionResult
import java.util.concurrent.Executors
import org.json.JSONObject

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
        private const val EXTRA_SESSION_DISPLAY_MODE = "session_display_mode"
        private const val EXTRA_VISIBLE_TO_USER = "visible_to_user"
        private const val EXTRA_RETURN_TO_VIRTUAL_AFTER_USER = "return_to_virtual_after_user"
        private const val EXTRA_DEBUG_FORCE_MAIN_DISPLAY = "debug_force_main_display"
        private const val EXTRA_BOOTSTRAP_TARGET = "bootstrap_target"
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
                handleAction(context.applicationContext, intent, pendingResult)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleAction(
        context: Context,
        intent: Intent?,
        pendingResult: PendingResult
    ) {
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
                val startOptions = buildStartOptions(intent)
                val plannerConfig = buildPlannerConfigOverride(intent)
                SessionAuditHooks.configureNextStartInjection(
                    skipAttach = skipAttach,
                    forceReportDisplayId = forceReportDisplayId,
                    plannerConfigOverride = plannerConfig
                )
                val result = startWithAutomation(context, command, startOptions)
                logResult(
                    event = "debug_start_session",
                    result = result,
                    extras = "skipAttach=$skipAttach forceReportDisplayId=$forceReportDisplayId " +
                        "sessionDisplayMode=${startOptions.displayMode.name} " +
                        "visibleToUser=${startOptions.visibleToUser} " +
                        "returnToVirtualAfterUser=${startOptions.returnToVirtualAfterUser} " +
                        "debugForceMainDisplay=${startOptions.debugForceMainDisplay} " +
                        "bootstrapTarget=${startOptions.bootstrapTarget.name} " +
                        "plannerMode=${plannerConfig?.mode?.name ?: "DEFAULT"} " +
                        "modelConfig=${plannerConfig?.modelConfig?.sanitizedSummary() ?: "default"}"
                )
                writeBroadcastResult(pendingResult, "debug_start_session", result)
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
                writeBroadcastResult(pendingResult, "debug_stop_session", result)
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
                writeBroadcastResult(pendingResult, "debug_reply_session", result)
            }

            "$pkg.debug.MIGRATE_TO_MAIN" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).migrateTaskToMain(sessionId)
                logResult(
                    event = "debug_migrate_to_main",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                writeBroadcastResult(pendingResult, "debug_migrate_to_main", result)
            }

            "$pkg.debug.MIGRATE_TO_EXECUTION" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).migrateTaskToExecution(sessionId)
                logResult(
                    event = "debug_migrate_to_execution",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                writeBroadcastResult(pendingResult, "debug_migrate_to_execution", result)
            }

            "$pkg.debug.DUMP_ACTIVE_SESSION" -> {
                val result = AgentHostRuntime.sessionCoordinator(context).dumpActiveSession()
                logResult(
                    event = "debug_dump_active_session",
                    result = result,
                    extras = "{}"
                )
                writeBroadcastResult(pendingResult, "debug_dump_active_session", result)
            }

            "$pkg.debug.SHOW_SESSION_ON_MAIN" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).showSessionOnMain(sessionId)
                logResult(
                    event = "debug_show_session_on_main",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                writeBroadcastResult(pendingResult, "debug_show_session_on_main", result)
            }

            "$pkg.debug.RETURN_SESSION_TO_VIRTUAL" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).returnSessionToVirtual(sessionId)
                logResult(
                    event = "debug_return_session_to_virtual",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                writeBroadcastResult(pendingResult, "debug_return_session_to_virtual", result)
            }

            else -> {
                Log.w(TAG, "event=debug_unknown_action code=UNKNOWN_ACTION extras=action=${intent?.action}")
                val result = SessionResult(
                    ok = false,
                    code = "UNKNOWN_ACTION",
                    data = mapOf("action" to intent?.action)
                )
                writeBroadcastResult(pendingResult, "debug_unknown_action", result)
            }
        }
    }

    private fun startWithAutomation(
        context: Context,
        command: String,
        options: SessionStartOptions
    ): SessionResult {
        val coordinator = AgentHostRuntime.sessionCoordinator(context)
        val startResult = coordinator.startSession(command, options)
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

    private fun buildStartOptions(intent: Intent): SessionStartOptions {
        val rawMode = intent.getStringExtra(EXTRA_SESSION_DISPLAY_MODE)?.trim().orEmpty()
        val displayMode = runCatching {
            if (rawMode.isBlank()) {
                SessionDisplayMode.AUTO
            } else {
                SessionDisplayMode.valueOf(rawMode.uppercase())
            }
        }.getOrElse {
            Log.w(
                TAG,
                "event=debug_invalid_session_display_mode code=INVALID_SESSION_DISPLAY_MODE " +
                    "extras=value=$rawMode"
            )
            SessionDisplayMode.AUTO
        }
        return SessionStartOptions(
            displayMode = displayMode,
            visibleToUser = intent.getBooleanExtra(EXTRA_VISIBLE_TO_USER, false),
            returnToVirtualAfterUser = intent.getBooleanExtra(EXTRA_RETURN_TO_VIRTUAL_AFTER_USER, true),
            debugForceMainDisplay = intent.getBooleanExtra(EXTRA_DEBUG_FORCE_MAIN_DISPLAY, false),
            bootstrapTarget = parseBootstrapTarget(intent.getStringExtra(EXTRA_BOOTSTRAP_TARGET))
        )
    }

    private fun parseBootstrapTarget(rawValue: String?): SessionBootstrapTarget {
        val raw = rawValue?.trim().orEmpty()
        if (raw.isBlank()) {
            return SessionBootstrapTarget.NONE
        }
        return runCatching { SessionBootstrapTarget.valueOf(raw.uppercase()) }.getOrElse {
            Log.w(
                TAG,
                "event=debug_invalid_bootstrap_target code=INVALID_BOOTSTRAP_TARGET extras=value=$raw"
            )
            SessionBootstrapTarget.NONE
        }
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
        val executionDisplayId = result.data["executionDisplayId"] ?: result.data["displayId"] ?: -1
        val currentExecutionDisplayId = result.data["currentExecutionDisplayId"] ?: executionDisplayId
        val currentVisibleDisplayId = result.data["currentVisibleDisplayId"] ?: "null"
        val virtualDisplayId = result.data["virtualDisplayId"] ?: "null"
        val surfaceDisplayId = result.data["surfaceDisplayId"] ?: "null"
        val sessionTaskId = result.data["taskId"] ?: -1
        val lastKnownTaskId = result.data["lastKnownTaskId"] ?: -1
        val lastKnownTaskAlive = result.data["lastKnownTaskAlive"] ?: "null"
        val lastKnownTaskDisplayId = result.data["lastKnownTaskDisplayId"] ?: "null"
        val lastMigrationCode = result.data["lastMigrationCode"] ?: "null"
        val lastMigrationDirection = result.data["lastMigrationDirection"] ?: "null"
        val lastMigrationConflictCode = result.data["lastMigrationConflictCode"] ?: "null"
        val lastMigrationConflictReason = result.data["lastMigrationConflictReason"] ?: "null"
        val migratedToMain = result.data["migratedToMain"] ?: "null"
        val userForegroundTaskId = result.data["userForegroundTaskId"] ?: "null"
        val recoveryNeeded = result.data["recoveryNeeded"] ?: "null"
        val lastKnownTopActivity = result.data["lastKnownTopActivity"] ?: "null"
        val lastKnownTopPackage = result.data["lastKnownTopPackage"] ?: "null"
        val step = result.data["automationStep"] ?: 0
        val plannerMode = result.data["plannerMode"] ?: "UNKNOWN"
        val actionType = result.data["actionType"] ?: "NONE"
        val status = result.data["status"] ?: "null"
        val waitingQuestion = (result.data["waitingQuestion"] ?: "null").toString()
            .replace("\n", "\\n")
        val displayMode = result.data["displayMode"] ?: "null"
        val bootstrapTarget = result.data["bootstrapTarget"] ?: "null"
        val bootstrapCode = result.data["lastBootstrapCode"] ?: "null"
        val visibleToUser = result.data["visibleToUser"] ?: "null"
        val pendingReturn = result.data["pendingReturnToVirtualDisplay"] ?: "null"
        Log.i(
            TAG,
            "event=$event sessionId=$sessionId executionDisplayId=$executionDisplayId " +
                "currentExecutionDisplayId=$currentExecutionDisplayId " +
                "currentVisibleDisplayId=$currentVisibleDisplayId " +
                "virtualDisplayId=$virtualDisplayId surfaceDisplayId=$surfaceDisplayId " +
                "sessionTaskId=$sessionTaskId step=$step plannerMode=$plannerMode actionType=$actionType " +
                "status=$status displayMode=$displayMode bootstrapTarget=$bootstrapTarget " +
                "bootstrapCode=$bootstrapCode visibleToUser=$visibleToUser " +
                "pendingReturnToVirtualDisplay=$pendingReturn waitingQuestion=$waitingQuestion " +
                "lastKnownTaskId=$lastKnownTaskId lastKnownTaskAlive=$lastKnownTaskAlive " +
                "lastKnownTaskDisplayId=$lastKnownTaskDisplayId " +
                "lastKnownTopActivity=$lastKnownTopActivity lastKnownTopPackage=$lastKnownTopPackage " +
                "migratedToMain=$migratedToMain userForegroundTaskId=$userForegroundTaskId " +
                "recoveryNeeded=$recoveryNeeded lastMigrationDirection=$lastMigrationDirection " +
                "lastMigrationCode=$lastMigrationCode lastMigrationConflictCode=$lastMigrationConflictCode " +
                "lastMigrationConflictReason=$lastMigrationConflictReason " +
                "code=${result.code} extras=ok=${result.ok} $extras"
        )
    }

    private fun writeBroadcastResult(
        pendingResult: PendingResult,
        event: String,
        result: SessionResult
    ) {
        fun JSONObject.putNullable(key: String, value: Any?) {
            put(key, value ?: JSONObject.NULL)
        }

        val payload = JSONObject().apply {
            put("event", event)
            put("ok", result.ok)
            put("code", result.code)
            putNullable("sessionId", result.data["sessionId"])
            putNullable("status", result.data["status"])
            putNullable("displayMode", result.data["displayMode"])
            putNullable("executionDisplayId", result.data["executionDisplayId"] ?: result.data["displayId"])
            putNullable("currentExecutionDisplayId", result.data["currentExecutionDisplayId"])
            putNullable("currentVisibleDisplayId", result.data["currentVisibleDisplayId"])
            putNullable("virtualDisplayId", result.data["virtualDisplayId"])
            putNullable("surfaceDisplayId", result.data["surfaceDisplayId"])
            putNullable("taskId", result.data["taskId"])
            putNullable("lastKnownTaskId", result.data["lastKnownTaskId"])
            putNullable("lastKnownTaskAlive", result.data["lastKnownTaskAlive"])
            putNullable("lastKnownTaskDisplayId", result.data["lastKnownTaskDisplayId"])
            putNullable("lastKnownTopActivity", result.data["lastKnownTopActivity"])
            putNullable("lastKnownTopPackage", result.data["lastKnownTopPackage"])
            putNullable("migratedToMain", result.data["migratedToMain"])
            putNullable("lastMigrationCode", result.data["lastMigrationCode"])
            putNullable("lastMigrationDirection", result.data["lastMigrationDirection"])
            putNullable("lastMigrationConflictCode", result.data["lastMigrationConflictCode"])
            putNullable("lastMigrationConflictReason", result.data["lastMigrationConflictReason"])
            putNullable("userForegroundTaskId", result.data["userForegroundTaskId"])
            putNullable("recoveryNeeded", result.data["recoveryNeeded"])
            putNullable("visibleToUser", result.data["visibleToUser"])
            putNullable("pendingReturnToVirtualDisplay", result.data["pendingReturnToVirtualDisplay"])
            putNullable("bootstrapTarget", result.data["bootstrapTarget"])
            putNullable("bootstrapCode", result.data["lastBootstrapCode"])
            putNullable("waitingQuestion", result.data["waitingQuestion"])
            putNullable("lastError", result.data["lastError"])
        }.toString()
        pendingResult.setResultCode(if (result.ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        pendingResult.setResultData(payload)
    }
}
