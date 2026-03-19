package com.example.agenthost.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.agenthost.automation.PlanningMode
import com.example.agenthost.config.ModelConfig
import com.example.agenthost.config.PlannerConfig
import com.example.agenthost.core.AgentHostRuntime
import com.example.agenthost.security.DebugEntryAccessPolicy
import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionResult
import com.example.agenthost.session.SessionStartOptions
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
        private const val EXTRA_FAULT_DIRECTION = "fault_direction"
        private const val EXTRA_FAULT_CODE = "fault_code"
        private const val EXTRA_ENABLED = "enabled"

        private val worker = Executors.newSingleThreadExecutor()
        private val supportedMigrationFaults = mapOf(
            "TO_MAIN" to setOf("TASK_ID_STALE", "TARGET_TASK_REMOVED", "TASK_CONFLICT_ON_MAIN"),
            "TO_EXECUTION" to setOf("TASK_ID_STALE", "TARGET_TASK_REMOVED", "TASK_MOVE_BACK_CONFLICT")
        )

        @JvmStatic
        fun emitAccessDeniedForTest(
            action: String?,
            decision: DebugEntryAccessPolicy.Decision
        ): SessionResult {
            return withAccessMetadata(buildAccessDeniedDispatch(action, decision).result, decision)
        }

        private fun buildAccessDeniedDispatch(
            action: String?,
            decision: DebugEntryAccessPolicy.Decision
        ): DispatchResult {
            Log.w(
                TAG,
                "event=debug_access_denied code=${decision.code} action=${action ?: "null"} " +
                    "callerUid=${decision.callerUid ?: -1} callerPackage=${decision.callerPackage ?: "null"} " +
                    "identitySource=${decision.identitySource} reason=${decision.reason}"
            )
            return DispatchResult(
                event = "debug_access_denied",
                result = SessionResult(
                    ok = false,
                    code = decision.code,
                    data = mapOf(
                        "action" to action,
                        "accessReason" to decision.reason
                    )
                )
            )
        }

        private fun withAccessMetadata(
            result: SessionResult,
            decision: DebugEntryAccessPolicy.Decision
        ): SessionResult {
            return result.copy(
                data = result.data + mapOf(
                    "accessCode" to decision.code,
                    "accessReason" to decision.reason,
                    "callerUid" to decision.callerUid,
                    "callerPackage" to decision.callerPackage,
                    "callerIdentitySource" to decision.identitySource
                )
            )
        }
    }

    private data class DispatchResult(
        val event: String,
        val result: SessionResult
    )

    private data class MethodCall<T>(
        val value: T?,
        val available: Boolean,
        val failureReason: String? = null
    )

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val callerIdentity = resolveCallerIdentity(appContext)
        val accessDecision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = callerIdentity,
            environment = DebugEntryAccessPolicy.Environment(
                appUid = appContext.applicationInfo.uid,
                appPackage = appContext.packageName,
                debugHooksEnabled = SessionAuditHooks.isEnabled(),
                deviceBuildType = Build.TYPE,
                permissionGatedFallbackAllowed = isPermissionGatedFallbackEnabled(appContext)
            )
        )
        val pendingResult = goAsync()
        worker.execute {
            try {
                val dispatch = if (accessDecision.allowed) {
                    handleAuthorizedAction(appContext, intent)
                } else {
                    buildAccessDeniedDispatch(intent?.action, accessDecision)
                }
                writeBroadcastResult(
                    pendingResult = pendingResult,
                    event = dispatch.event,
                    result = withAccessMetadata(dispatch.result, accessDecision)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleAuthorizedAction(context: Context, intent: Intent?): DispatchResult {
        val pkg = context.packageName
        return when (intent?.action) {
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
                DispatchResult(event = "debug_start_session", result = result)
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
                DispatchResult(event = "debug_stop_session", result = result)
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
                DispatchResult(event = "debug_reply_session", result = result)
            }

            "$pkg.debug.MIGRATE_TO_MAIN" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).migrateTaskToMain(sessionId)
                logResult(
                    event = "debug_migrate_to_main",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                DispatchResult(event = "debug_migrate_to_main", result = result)
            }

            "$pkg.debug.MIGRATE_TO_EXECUTION" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).migrateTaskToExecution(sessionId)
                logResult(
                    event = "debug_migrate_to_execution",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                DispatchResult(event = "debug_migrate_to_execution", result = result)
            }

            "$pkg.debug.DUMP_ACTIVE_SESSION" -> {
                val result = AgentHostRuntime.sessionCoordinator(context).dumpActiveSession()
                logResult(
                    event = "debug_dump_active_session",
                    result = result,
                    extras = "{}"
                )
                DispatchResult(event = "debug_dump_active_session", result = result)
            }

            "$pkg.debug.SET_TASK_REPORTING" -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                TaskRunReportHooks.setReportEnabled(context, enabled)
                val result = SessionResult(
                    ok = true,
                    code = "TASK_REPORTING_UPDATED",
                    data = TaskRunReportHooks.dumpConfig(context) + mapOf("requestedEnabled" to enabled)
                )
                logResult(
                    event = "debug_set_task_reporting",
                    result = result,
                    extras = "enabled=$enabled"
                )
                DispatchResult(event = "debug_set_task_reporting", result = result)
            }

            "$pkg.debug.DUMP_TASK_REPORTING" -> {
                val result = SessionResult(
                    ok = true,
                    code = "TASK_REPORTING_STATE",
                    data = TaskRunReportHooks.dumpConfig(context)
                )
                logResult(
                    event = "debug_dump_task_reporting",
                    result = result,
                    extras = "{}"
                )
                DispatchResult(event = "debug_dump_task_reporting", result = result)
            }

            "$pkg.debug.SHOW_SESSION_ON_MAIN" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).showSessionOnMain(sessionId)
                logResult(
                    event = "debug_show_session_on_main",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                DispatchResult(event = "debug_show_session_on_main", result = result)
            }

            "$pkg.debug.RETURN_SESSION_TO_VIRTUAL" -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                val result = AgentHostRuntime.sessionCoordinator(context).returnSessionToVirtual(sessionId)
                logResult(
                    event = "debug_return_session_to_virtual",
                    result = result,
                    extras = "sessionId=$sessionId"
                )
                DispatchResult(event = "debug_return_session_to_virtual", result = result)
            }

            "$pkg.debug.ARM_MIGRATION_FAULT" -> {
                val direction = intent.getStringExtra(EXTRA_FAULT_DIRECTION).orEmpty().trim().uppercase()
                val faultCode = intent.getStringExtra(EXTRA_FAULT_CODE).orEmpty().trim().uppercase()
                val validationError = validateMigrationFault(direction, faultCode)
                val result = if (validationError == null) {
                    SessionAuditHooks.armMigrationFaultOnce(direction = direction, code = faultCode)
                    SessionResult(
                        ok = true,
                        code = "DEBUG_MIGRATION_FAULT_ARMED",
                        data = SessionAuditHooks.dumpDebugState() + mapOf(
                            "requestedFaultDirection" to direction,
                            "requestedFaultCode" to faultCode
                        )
                    )
                } else {
                    SessionResult(
                        ok = false,
                        code = "INVALID_DEBUG_MIGRATION_FAULT_REQUEST",
                        data = mapOf(
                            "requestedFaultDirection" to direction,
                            "requestedFaultCode" to faultCode,
                            "reason" to validationError
                        )
                    )
                }
                logResult(
                    event = "debug_arm_migration_fault",
                    result = result,
                    extras = "direction=$direction faultCode=$faultCode"
                )
                DispatchResult(event = "debug_arm_migration_fault", result = result)
            }

            "$pkg.debug.CLEAR_DEBUG_HOOKS" -> {
                SessionAuditHooks.clearDebugState()
                val result = SessionResult(
                    ok = true,
                    code = "DEBUG_HOOKS_CLEARED",
                    data = SessionAuditHooks.dumpDebugState()
                )
                logResult(
                    event = "debug_clear_debug_hooks",
                    result = result,
                    extras = "{}"
                )
                DispatchResult(event = "debug_clear_debug_hooks", result = result)
            }

            "$pkg.debug.DUMP_DEBUG_HOOKS" -> {
                val result = SessionResult(
                    ok = true,
                    code = "DEBUG_HOOKS_STATE",
                    data = SessionAuditHooks.dumpDebugState()
                )
                logResult(
                    event = "debug_dump_debug_hooks",
                    result = result,
                    extras = "{}"
                )
                DispatchResult(event = "debug_dump_debug_hooks", result = result)
            }

            else -> {
                Log.w(TAG, "event=debug_unknown_action code=UNKNOWN_ACTION extras=action=${intent?.action}")
                val result = SessionResult(
                    ok = false,
                    code = "UNKNOWN_ACTION",
                    data = mapOf("action" to intent?.action)
                )
                DispatchResult(event = "debug_unknown_action", result = result)
            }
        }
    }

    private fun resolveCallerIdentity(context: Context): DebugEntryAccessPolicy.CallerIdentity {
        if (Build.VERSION.SDK_INT >= 34) {
            val apiUid = readCallerUidApi34()
            val apiPackage = readCallerPackageApi34()
            val callerUid = apiUid.value?.takeIf { it >= 0 }
            if (callerUid != null) {
                val packageName = apiPackage.value ?: packageNameForUid(context, callerUid)
                return DebugEntryAccessPolicy.CallerIdentity(
                    uid = callerUid,
                    packageName = packageName,
                    identitySource = if (apiPackage.value != null) "api34" else "api34_uid_pm",
                    capabilityMissing = false,
                    failureReason = null
                )
            }
            if (!apiUid.available) {
                return resolveCallerIdentityFromReflection(context, apiUid.failureReason)
            }
        }
        return resolveCallerIdentityFromReflection(context, null)
    }

    private fun resolveCallerIdentityFromReflection(
        context: Context,
        priorFailureReason: String?
    ): DebugEntryAccessPolicy.CallerIdentity {
        val reflectedUid = invokeIntMethod("getSentFromUid")
        val reflectedPackage = invokeStringMethod("getSentFromPackage")
        val callerUid = reflectedUid.value?.takeIf { it >= 0 }
        if (callerUid != null) {
            val packageName = reflectedPackage.value ?: packageNameForUid(context, callerUid)
            return DebugEntryAccessPolicy.CallerIdentity(
                uid = callerUid,
                packageName = packageName,
                identitySource = if (reflectedPackage.value != null) {
                    "reflection"
                } else {
                    "reflection_uid_pm"
                },
                capabilityMissing = false,
                failureReason = null
            )
        }
        val capabilityMissing = !reflectedUid.available
        val failureReason = when {
            capabilityMissing -> reflectedUid.failureReason ?: priorFailureReason ?: "caller_identity_capability_missing"
            reflectedUid.value != null && reflectedUid.value < 0 -> "caller_uid_invalid"
            priorFailureReason != null -> priorFailureReason
            else -> reflectedUid.failureReason ?: "caller_identity_unavailable"
        }
        return DebugEntryAccessPolicy.CallerIdentity(
            uid = null,
            packageName = reflectedPackage.value,
            identitySource = if (reflectedUid.available) "reflection" else "unavailable",
            capabilityMissing = capabilityMissing,
            failureReason = failureReason
        )
    }

    private fun isPermissionGatedFallbackEnabled(context: Context): Boolean {
        return runCatching {
            val receiverInfo = context.packageManager.getReceiverInfo(
                ComponentName(context, SessionAuditReceiver::class.java),
                PackageManager.GET_META_DATA
            )
            receiverInfo.permission == "android.permission.DUMP"
        }.getOrDefault(false)
    }

    private fun readCallerUidApi34(): MethodCall<Int> {
        return try {
            MethodCall(value = getSentFromUid(), available = true)
        } catch (t: Throwable) {
            MethodCall(
                value = null,
                available = false,
                failureReason = "api34_sent_from_uid_unavailable:${t.javaClass.simpleName}"
            )
        }
    }

    private fun readCallerPackageApi34(): MethodCall<String> {
        return try {
            MethodCall(value = getSentFromPackage(), available = true)
        } catch (t: Throwable) {
            MethodCall(
                value = null,
                available = false,
                failureReason = "api34_sent_from_package_unavailable:${t.javaClass.simpleName}"
            )
        }
    }

    private fun invokeIntMethod(methodName: String): MethodCall<Int> {
        return try {
            val method = BroadcastReceiver::class.java.getMethod(methodName)
            val value = method.invoke(this) as? Int
            MethodCall(value = value, available = true)
        } catch (t: NoSuchMethodException) {
            MethodCall(value = null, available = false, failureReason = "${methodName}_missing")
        } catch (t: Throwable) {
            MethodCall(
                value = null,
                available = false,
                failureReason = "${methodName}_invoke_failed:${t.javaClass.simpleName}"
            )
        }
    }

    private fun invokeStringMethod(methodName: String): MethodCall<String> {
        return try {
            val method = BroadcastReceiver::class.java.getMethod(methodName)
            MethodCall(value = method.invoke(this) as? String, available = true)
        } catch (t: NoSuchMethodException) {
            MethodCall(value = null, available = false, failureReason = "${methodName}_missing")
        } catch (t: Throwable) {
            MethodCall(
                value = null,
                available = false,
                failureReason = "${methodName}_invoke_failed:${t.javaClass.simpleName}"
            )
        }
    }

    private fun packageNameForUid(context: Context, uid: Int): String? {
        return runCatching { context.packageManager.getPackagesForUid(uid)?.firstOrNull() }
            .getOrNull()
    }

    private fun validateMigrationFault(direction: String, faultCode: String): String? {
        if (direction.isBlank()) {
            return "missing_fault_direction"
        }
        if (faultCode.isBlank()) {
            return "missing_fault_code"
        }
        val supportedCodes = supportedMigrationFaults[direction] ?: return "unsupported_fault_direction"
        return if (faultCode in supportedCodes) {
            null
        } else {
            "unsupported_fault_code"
        }
    }

    private fun startWithAutomation(
        context: Context,
        command: String,
        options: SessionStartOptions
    ): SessionResult {
        val coordinator = AgentHostRuntime.sessionCoordinator(context)
        TaskRunReportHooks.prepareNextSessionSource("DEBUG")
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

        val knownKeys = linkedSetOf(
            "sessionId",
            "status",
            "displayMode",
            "executionDisplayId",
            "displayId",
            "currentExecutionDisplayId",
            "currentVisibleDisplayId",
            "virtualDisplayId",
            "surfaceDisplayId",
            "taskId",
            "lastKnownTaskId",
            "lastKnownTaskAlive",
            "lastKnownTaskDisplayId",
            "lastKnownTopActivity",
            "lastKnownTopPackage",
            "migratedToMain",
            "lastMigrationCode",
            "lastMigrationDirection",
            "lastMigrationConflictCode",
            "lastMigrationConflictReason",
            "userForegroundTaskId",
            "recoveryNeeded",
            "visibleToUser",
            "pendingReturnToVirtualDisplay",
            "bootstrapTarget",
            "lastBootstrapCode",
            "waitingQuestion",
            "lastError"
        )
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
            result.data.forEach { (key, value) ->
                if (key !in knownKeys) {
                    putNullable(key, value)
                }
            }
        }.toString()
        pendingResult.setResultCode(if (result.ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        pendingResult.setResultData(payload)
    }
}
