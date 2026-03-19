package com.example.agenthost.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.example.agenthost.automation.ActionExecutionResult
import com.example.agenthost.automation.CanonicalAction
import com.example.agenthost.automation.GuiAction
import com.example.agenthost.automation.JsonLite
import com.example.agenthost.automation.JsonLiteValue
import com.example.agenthost.automation.ObservationResult
import com.example.agenthost.automation.PlanningRequest
import com.example.agenthost.automation.asObjectOrNull
import com.example.agenthost.automation.actionType
import com.example.agenthost.automation.optionalString
import com.example.agenthost.report.ClickDiagnosticsReport
import com.example.agenthost.report.ControlStepDiagnosticsReport
import com.example.agenthost.report.ModelInputSummary
import com.example.agenthost.report.ModelOutputDiagnosticsReport
import com.example.agenthost.report.TaskRunLifecycle
import com.example.agenthost.report.TaskRunLifecycleEvent
import com.example.agenthost.report.TaskRunOverview
import com.example.agenthost.report.TaskRunReport
import com.example.agenthost.report.TaskRunReportAnalyzer
import com.example.agenthost.report.TaskRunReportRenderer
import com.example.agenthost.report.TaskRunStepReport
import com.example.agenthost.session.SessionState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

object TaskRunReportHooks {
    private const val TAG = "TaskRunReportHooks"
    private const val PREFS_NAME = "task_run_reporting"
    private const val KEY_ENABLED = "enabled"
    private const val REPORTS_DIR = "reports/task_runs"
    private const val AFTER_CAPTURED = "CAPTURED"

    private val runKeyFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd_HHmmss_SSS")
        .withZone(ZoneId.systemDefault())

    private data class PendingStartMetadata(
        val source: String
    )

    private data class MutableStep(
        val stepIndex: Int,
        var stepStartTimeMs: Long? = null,
        var stepEndTimeMs: Long? = null,
        var screenshotPath: String? = null,
        var screenshotWidth: Int? = null,
        var screenshotHeight: Int? = null,
        var beforeObservationDisplayId: Int? = null,
        var annotatedScreenshotPath: String? = null,
        var injectDisplayId: Int? = null,
        var injectedX: Int? = null,
        var injectedY: Int? = null,
        var afterObservationDisplayId: Int? = null,
        var afterScreenshotPath: String? = null,
        var afterScreenshotWidth: Int? = null,
        var afterScreenshotHeight: Int? = null,
        var afterObservationCode: String? = null,
        var afterObservationMessage: String? = null,
        var topActivity: String? = null,
        var topPackage: String? = null,
        var displayId: Int? = null,
        var modelInputSummary: ModelInputSummary? = null,
        var modelRawResponseText: String? = null,
        var modelReturnedSuccessfully: Boolean = false,
        var proxySanitizerPath: String? = null,
        var modelTerminalDisposition: String? = null,
        var parsedActionType: String? = null,
        var actionArgs: Map<String, Any?> = emptyMap(),
        var canonicalActionType: String? = null,
        var canonicalCoordSpace: String? = null,
        var canonicalActionArgs: Map<String, Any?> = emptyMap(),
        var canonicalRawModelAction: String? = null,
        var canonicalSanitizerPath: String? = null,
        var canonicalSourceModel: String? = null,
        var canonicalSourceMode: String? = null,
        var proposedActionType: String? = null,
        var finalActionType: String? = null,
        var guardrailRule: String? = null,
        var guardrailDecision: String? = null,
        var phaseBefore: String? = null,
        var phaseAfter: String? = null,
        var completionVerdict: String? = null,
        var observationChanged: Boolean? = null,
        var lowYield: Boolean? = null,
        var platformAttempted: Boolean = false,
        var platformOk: Boolean? = null,
        var platformCode: String? = null,
        var platformDetails: String? = null,
        var failureLayer: String? = null,
        var failureCode: String? = null,
        var failureDetails: String? = null
    ) {
        fun finish(nowMs: Long) {
            if (stepEndTimeMs == null) {
                stepEndTimeMs = nowMs
            }
        }

        fun recordFailure(layer: String, code: String, details: String?) {
            if (failureLayer == null) {
                failureLayer = layer
                failureCode = code
                failureDetails = details
            }
        }
    }

    private data class MutableRun(
        val sessionId: String,
        val runKey: String,
        val runDir: File,
        val assetsDir: File,
        val relativeRunDir: String,
        val task: String,
        val source: String,
        val displayMode: String,
        val bootstrapTarget: String,
        val startTimeMs: Long,
        var plannerMode: String,
        val steps: LinkedHashMap<Int, MutableStep> = linkedMapOf(),
        val lifecycleEvents: MutableList<TaskRunLifecycleEvent> = mutableListOf(),
        var finalState: String? = null,
        var finalOutcome: String? = null,
        var finalCode: String? = null,
        var activeSessionResidual: Boolean = false,
        var finalized: Boolean = false
    )

    private val lock = Any()
    private val runs = linkedMapOf<String, MutableRun>()
    private val pendingStart = AtomicReference<PendingStartMetadata?>(null)

    fun prepareNextSessionSource(source: String) {
        pendingStart.set(PendingStartMetadata(source = source.trim().ifEmpty { "OTHER" }.uppercase()))
    }

    fun isReportEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setReportEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun dumpConfig(context: Context): Map<String, Any?> {
        val root = File(context.applicationContext.filesDir, REPORTS_DIR)
        return mapOf(
            "taskReportEnabled" to isReportEnabled(context),
            "taskReportStorageRoot" to root.absolutePath
        )
    }

    fun onSessionCreated(
        context: Context,
        sessionId: String,
        task: String,
        displayMode: String,
        bootstrapTarget: String,
        plannerMode: String
    ) {
        if (!isReportEnabled(context)) {
            pendingStart.set(null)
            return
        }
        val source = pendingStart.getAndSet(null)?.source ?: "OTHER"
        val nowMs = System.currentTimeMillis()
        val runKey = runKeyFormatter.format(Instant.ofEpochMilli(nowMs)) + "_" + sessionId
        val reportsRoot = File(context.applicationContext.filesDir, REPORTS_DIR)
        val runDir = File(reportsRoot, runKey)
        val assetsDir = File(runDir, "assets")
        assetsDir.mkdirs()
        synchronized(lock) {
            runs[sessionId] = MutableRun(
                sessionId = sessionId,
                runKey = runKey,
                runDir = runDir,
                assetsDir = assetsDir,
                relativeRunDir = "$REPORTS_DIR/$runKey",
                task = task,
                source = source,
                displayMode = displayMode,
                bootstrapTarget = bootstrapTarget,
                startTimeMs = nowMs,
                plannerMode = plannerMode
            )
        }
    }

    fun updatePlannerMode(sessionId: String, plannerMode: String) {
        synchronized(lock) {
            runs[sessionId]?.plannerMode = plannerMode
        }
    }

    fun shouldCollectClickDiagnostics(sessionId: String): Boolean {
        synchronized(lock) {
            val run = runs[sessionId] ?: return false
            return !run.finalized
        }
    }

    fun onSessionEvent(
        event: String,
        code: String,
        state: SessionState?,
        sessionId: String?,
        step: Int?
    ) {
        val sid = sessionId ?: state?.sessionId ?: return
        synchronized(lock) {
            val run = runs[sid] ?: return
            if (run.finalized) {
                return
            }
            run.lifecycleEvents += TaskRunLifecycleEvent(
                timeMs = System.currentTimeMillis(),
                event = event,
                code = code,
                status = state?.status?.name,
                automationStep = step ?: state?.automationStep,
                migratedToMain = state?.migratedToMain,
                recoveryNeeded = state?.recoveryNeeded,
                currentVisibleDisplayId = state?.currentVisibleDisplayId,
                lastMigrationDirection = state?.lastMigrationDirection,
                lastMigrationCode = state?.lastMigrationCode,
                lastError = state?.lastError
            )
        }
    }

    fun onStepBegin(
        sessionId: String,
        step: Int,
        displayId: Int?,
        topActivity: String?,
        topPackage: String?
    ) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(sessionId, step) ?: return
            stepState.stepStartTimeMs = stepState.stepStartTimeMs ?: System.currentTimeMillis()
            stepState.displayId = displayId
            stepState.topActivity = topActivity
            stepState.topPackage = topPackage
        }
    }

    fun onObservation(
        sessionId: String,
        step: Int,
        observation: ObservationResult,
        observationDisplayId: Int,
        displayId: Int?,
        topActivity: String?,
        topPackage: String?
    ) {
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val run = runs[sessionId] ?: return
            if (run.finalized) {
                return
            }
            val stepState = run.steps.getOrPut(step) { MutableStep(stepIndex = step) }
            stepState.stepStartTimeMs = stepState.stepStartTimeMs ?: nowMs
            stepState.beforeObservationDisplayId = observationDisplayId
            stepState.displayId = displayId
            stepState.topActivity = topActivity
            stepState.topPackage = topPackage
            if (observation is ObservationResult.Captured) {
                stepState.screenshotWidth = observation.width
                stepState.screenshotHeight = observation.height
                stepState.screenshotPath = persistScreenshotLocked(run, step, "before", observation.base64Png)
            }
        }
    }

    fun onModelInput(request: PlanningRequest) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(request.sessionId, request.step) ?: return
            val observationStatus = when (val observation = request.observation) {
                is ObservationResult.Captured -> {
                    stepState.screenshotWidth = observation.width
                    stepState.screenshotHeight = observation.height
                    "CAPTURED"
                }

                is ObservationResult.Failed -> observation.code
            }
            stepState.modelInputSummary = ModelInputSummary(
                commandPreview = request.command.trim().replace('\n', ' ').take(120),
                executionDisplayId = request.executionDisplayId,
                sessionTaskId = request.sessionTaskId,
                observationStatus = observationStatus,
                observationWidth = (request.observation as? ObservationResult.Captured)?.width,
                observationHeight = (request.observation as? ObservationResult.Captured)?.height,
                recentActionCount = request.recentActions.size,
                lastFailureCode = request.lastFailureCode,
                hasLastUserReply = !request.lastUserReply.isNullOrBlank(),
                currentPhase = request.controlSnapshot?.currentPhase,
                allowedActions = request.controlSnapshot?.allowedActions ?: emptyList(),
                blockedActions = request.controlSnapshot?.blockedActions ?: emptyList(),
                lastResult = request.controlSnapshot?.lastResult,
                completionState = request.controlSnapshot?.completionState,
                doneOnly = request.controlSnapshot?.doneOnly == true
            )
        }
    }

    fun onModelResponse(
        sessionId: String,
        step: Int,
        rawResponseText: String
    ) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(sessionId, step) ?: return
            stepState.modelRawResponseText = rawResponseText
            stepState.modelReturnedSuccessfully = true
            stepState.proxySanitizerPath = stepState.proxySanitizerPath ?: extractProxySanitizerPath(rawResponseText)
            stepState.modelTerminalDisposition = stepState.modelTerminalDisposition ?: "accepted"
        }
    }

    fun onPlanFailure(
        sessionId: String,
        step: Int,
        code: String,
        details: String,
        rawResponseText: String? = null
    ) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(sessionId, step) ?: return
            if (!rawResponseText.isNullOrBlank() && stepState.modelRawResponseText.isNullOrBlank()) {
                stepState.modelRawResponseText = rawResponseText
            }
            if (!rawResponseText.isNullOrBlank() && stepState.proxySanitizerPath == null) {
                stepState.proxySanitizerPath = extractProxySanitizerPath(rawResponseText)
            }
            mapModelTerminalDisposition(code)?.let { stepState.modelTerminalDisposition = it }
            stepState.recordFailure(mapFailureLayer(code, details), code, details)
            stepState.finish(System.currentTimeMillis())
        }
    }

    fun onActionPlanned(sessionId: String, step: Int, action: GuiAction) {
        synchronized(lock) {
            val run = runs[sessionId] ?: return
            if (run.finalized) {
                return
            }
            val stepState = run.steps.getOrPut(step) { MutableStep(stepIndex = step) }
            stepState.parsedActionType = action.actionType()
            stepState.actionArgs = actionArgs(action)
            if (action is GuiAction.Click) {
                stepState.annotatedScreenshotPath = persistClickAnnotationLocked(run, stepState, action)
            }
        }
    }

    fun onControlActionResolved(
        sessionId: String,
        step: Int,
        proposedAction: CanonicalAction,
        finalAction: CanonicalAction,
        phaseBefore: String,
        guardrailRule: String?,
        guardrailDecision: String
    ) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(sessionId, step) ?: return
            stepState.canonicalActionType = proposedAction.actionType()
            stepState.canonicalCoordSpace = proposedAction.coordSpace.name
            stepState.canonicalActionArgs = proposedAction.asReportArgs()
            stepState.canonicalRawModelAction = proposedAction.rawModelAction
            stepState.canonicalSanitizerPath = proposedAction.sanitizerPath
            if (stepState.proxySanitizerPath == null) {
                stepState.proxySanitizerPath = proposedAction.sanitizerPath
            }
            stepState.canonicalSourceModel = proposedAction.sourceModel
            stepState.canonicalSourceMode = proposedAction.sourceMode
            stepState.proposedActionType = proposedAction.actionType()
            stepState.finalActionType = finalAction.actionType()
            stepState.guardrailRule = guardrailRule
            stepState.guardrailDecision = guardrailDecision
            stepState.phaseBefore = phaseBefore
        }
    }

    fun onControlStepEvaluated(
        sessionId: String,
        step: Int,
        phaseAfter: String,
        completionVerdict: String,
        observationChanged: Boolean?,
        lowYield: Boolean?
    ) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(sessionId, step) ?: return
            stepState.phaseAfter = phaseAfter
            stepState.completionVerdict = completionVerdict
            stepState.observationChanged = observationChanged
            stepState.lowYield = lowYield
        }
    }

    fun onPlatformResult(
        sessionId: String,
        step: Int,
        action: GuiAction,
        result: ActionExecutionResult,
        injectDisplayId: Int? = null
    ) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(sessionId, step) ?: return
            stepState.parsedActionType = stepState.parsedActionType ?: action.actionType()
            if (stepState.actionArgs.isEmpty()) {
                stepState.actionArgs = actionArgs(action)
            }
            if (action is GuiAction.Click) {
                stepState.injectDisplayId = injectDisplayId
                stepState.injectedX = action.x
                stepState.injectedY = action.y
            }
            stepState.platformAttempted = true
            stepState.platformOk = result.ok
            stepState.platformCode = result.code
            stepState.platformDetails = result.details
            if (!result.ok) {
                stepState.recordFailure(mapFailureLayer(result.code, result.details), result.code, result.details)
            }
            stepState.finish(System.currentTimeMillis())
        }
    }

    fun onClickAfterObservation(
        sessionId: String,
        step: Int,
        observationDisplayId: Int,
        observation: ObservationResult
    ) {
        synchronized(lock) {
            val run = runs[sessionId] ?: return
            if (run.finalized) {
                return
            }
            val stepState = run.steps.getOrPut(step) { MutableStep(stepIndex = step) }
            stepState.afterObservationDisplayId = observationDisplayId
            when (observation) {
                is ObservationResult.Captured -> {
                    stepState.afterScreenshotWidth = observation.width
                    stepState.afterScreenshotHeight = observation.height
                    stepState.afterScreenshotPath = persistScreenshotLocked(run, step, "after", observation.base64Png)
                    stepState.afterObservationCode = AFTER_CAPTURED
                    stepState.afterObservationMessage = null
                }

                is ObservationResult.Failed -> {
                    stepState.afterObservationCode = observation.code
                    stepState.afterObservationMessage = observation.message
                }
            }
        }
    }

    fun onSyntheticActionResult(
        sessionId: String,
        step: Int,
        action: GuiAction,
        code: String,
        details: String
    ) {
        synchronized(lock) {
            val stepState = getOrCreateStepLocked(sessionId, step) ?: return
            stepState.parsedActionType = stepState.parsedActionType ?: action.actionType()
            if (stepState.actionArgs.isEmpty()) {
                stepState.actionArgs = actionArgs(action)
            }
            stepState.platformAttempted = false
            stepState.platformOk = null
            stepState.platformCode = code
            stepState.platformDetails = details
            stepState.finish(System.currentTimeMillis())
        }
    }

    fun finalizeRun(
        sessionId: String,
        finalState: String,
        finalOutcome: String,
        activeSessionResidual: Boolean,
        finalCode: String
    ) {
        val finalized = synchronized(lock) {
            val run = runs[sessionId] ?: return
            if (run.finalized) {
                return
            }
            run.finalized = true
            run.finalState = finalState
            run.finalOutcome = finalOutcome
            run.finalCode = finalCode
            run.activeSessionResidual = activeSessionResidual
            val endTimeMs = System.currentTimeMillis()
            val lastEvent = run.lifecycleEvents.lastOrNull()
            if (lastEvent?.code != finalCode || lastEvent.status != finalState) {
                run.lifecycleEvents += TaskRunLifecycleEvent(
                    timeMs = endTimeMs,
                    event = "report_finalize",
                    code = finalCode,
                    status = finalState,
                    automationStep = run.steps.keys.maxOrNull(),
                    migratedToMain = lastEvent?.migratedToMain,
                    recoveryNeeded = lastEvent?.recoveryNeeded,
                    currentVisibleDisplayId = lastEvent?.currentVisibleDisplayId,
                    lastMigrationDirection = lastEvent?.lastMigrationDirection,
                    lastMigrationCode = lastEvent?.lastMigrationCode,
                    lastError = if (finalOutcome == "failed") finalCode else lastEvent?.lastError
                )
            }
            run.steps.values.forEach { step ->
                if (step.stepStartTimeMs != null && step.stepEndTimeMs == null) {
                    step.finish(endTimeMs)
                }
            }
            run.runDir to buildReportLocked(run, endTimeMs)
        }
        writeReportFiles(finalized.first, finalized.second)
        synchronized(lock) {
            runs.remove(sessionId)
        }
    }

    private fun buildReportLocked(run: MutableRun, endTimeMs: Long): TaskRunReport {
        val overview = TaskRunOverview(
            task = run.task,
            sessionId = run.sessionId,
            source = run.source,
            displayMode = run.displayMode,
            bootstrapTarget = run.bootstrapTarget,
            plannerMode = run.plannerMode,
            startTimeMs = run.startTimeMs,
            endTimeMs = endTimeMs,
            totalDurationMs = (endTimeMs - run.startTimeMs).coerceAtLeast(0L),
            finalSessionState = run.finalState ?: "UNKNOWN",
            finalOutcome = run.finalOutcome ?: "failed",
            activeSessionResidual = run.activeSessionResidual,
            reportPath = "${run.relativeRunDir}/report.md",
            detailPath = "${run.relativeRunDir}/report.json"
        )
        val steps = run.steps.values.map { step ->
            TaskRunStepReport(
                stepIndex = step.stepIndex,
                stepStartTimeMs = step.stepStartTimeMs,
                stepEndTimeMs = step.stepEndTimeMs,
                durationMs = if (step.stepStartTimeMs != null && step.stepEndTimeMs != null) {
                    (step.stepEndTimeMs!! - step.stepStartTimeMs!!).coerceAtLeast(0L)
                } else {
                    null
                },
                screenshotPath = step.screenshotPath,
                screenshotWidth = step.screenshotWidth,
                screenshotHeight = step.screenshotHeight,
                topActivity = step.topActivity,
                topPackage = step.topPackage,
                displayId = step.displayId,
                modelInputSummary = step.modelInputSummary,
                modelRawResponseText = step.modelRawResponseText,
                modelReturnedSuccessfully = step.modelReturnedSuccessfully,
                modelOutputDiagnostics = buildModelOutputDiagnostics(step),
                parsedActionType = step.parsedActionType,
                actionArgs = step.actionArgs,
                platformAttempted = step.platformAttempted,
                platformOk = step.platformOk,
                platformCode = step.platformCode,
                platformDetails = step.platformDetails,
                failureLayer = step.failureLayer,
                failureCode = step.failureCode,
                failureDetails = step.failureDetails,
                clickDiagnostics = buildClickDiagnostics(step),
                controlDiagnostics = buildControlDiagnostics(step)
            )
        }
        val lifecycle = TaskRunLifecycle(
            events = run.lifecycleEvents.toList(),
            migratedToMainOccurred = run.lifecycleEvents.any { it.migratedToMain == true || it.code == "TASK_MIGRATION_OK" },
            migrateBackOccurred = run.lifecycleEvents.any {
                it.lastMigrationDirection == "TO_EXECUTION" && it.code == "TASK_MIGRATION_BACK_OK"
            },
            recoveryNeededSeen = run.lifecycleEvents.any { it.recoveryNeeded == true },
            activeSessionResidualAfterEnd = run.activeSessionResidual
        )
        return TaskRunReportAnalyzer.buildReport(
            runKey = run.runKey,
            overview = overview,
            steps = steps,
            lifecycle = lifecycle
        )
    }

    private fun writeReportFiles(runRoot: File, report: TaskRunReport) {
        runCatching {
            val markdownFile = File(runRoot, "report.md")
            val jsonFile = File(runRoot, "report.json")
            markdownFile.writeText(TaskRunReportRenderer.renderMarkdown(report), Charsets.UTF_8)
            jsonFile.writeText(TaskRunReportRenderer.renderJson(report), Charsets.UTF_8)
        }.onFailure { error ->
            Log.e(TAG, "failed to write task run report for sessionId=${report.overview.sessionId}", error)
        }
    }

    private fun persistScreenshotLocked(
        run: MutableRun,
        step: Int,
        artifactLabel: String,
        base64Png: String
    ): String? {
        return runCatching {
            val pngBytes = Base64.getDecoder().decode(base64Png)
            val file = artifactFile(run, step, artifactLabel)
            file.writeBytes(pngBytes)
            "${run.relativeRunDir}/assets/${file.name}"
        }.getOrNull()
    }

    private fun getOrCreateStepLocked(sessionId: String, step: Int): MutableStep? {
        val run = runs[sessionId] ?: return null
        if (run.finalized) {
            return null
        }
        return run.steps.getOrPut(step) { MutableStep(stepIndex = step) }
    }

    private fun actionArgs(action: GuiAction): Map<String, Any?> {
        return when (action) {
            is GuiAction.Click -> mapOf("x" to action.x, "y" to action.y)
            is GuiAction.LongPress -> mapOf(
                "x" to action.x,
                "y" to action.y,
                "durationMs" to action.durationMs
            )
            is GuiAction.Swipe -> mapOf(
                "x1" to action.x1,
                "y1" to action.y1,
                "x2" to action.x2,
                "y2" to action.y2,
                "durationMs" to action.durationMs
            )
            is GuiAction.TypeText -> mapOf("text" to action.text)
            GuiAction.Back -> emptyMap()
            GuiAction.Home -> emptyMap()
            is GuiAction.Wait -> mapOf("durationMs" to action.durationMs)
            is GuiAction.AskUser -> mapOf("question" to action.question)
            is GuiAction.Done -> mapOf("reason" to action.reason)
        }
    }

    private fun mapFailureLayer(code: String, details: String?): String {
        val upperCode = code.uppercase()
        val upperDetails = details?.uppercase().orEmpty()
        return when {
            upperCode.contains("TIMEOUT") || upperDetails.contains("TIMEOUT") -> "TIMEOUT"
            upperCode.contains("GUARDRAIL") || upperCode.contains("PHASE_BLOCKED") -> "CONTROL"
            upperCode.startsWith("PARSE_") ||
                upperCode == "MODEL_OUTPUT_INVALID" ||
                upperCode == "MODEL_OUTPUT_UNSUPPORTED_ACTION" -> "PARSE"
            upperCode.startsWith("MODEL_") -> "MODEL"
            upperCode.startsWith("TASK_") ||
                upperCode.contains("MIGRATION") ||
                upperCode == "TARGET_TASK_REMOVED" ||
                upperCode == "TASK_ID_STALE" ||
                upperCode == "TARGET_TASK_NOT_FOUND" -> "MIGRATION"
            upperCode == "SESSION_DISPLAY_MISMATCH" ||
                upperCode.contains("STATE_MISMATCH") ||
                upperCode == "SESSION_NOT_RUNNING" -> "STATE_MISMATCH"
            else -> "PLATFORM"
        }
    }

    private fun mapModelTerminalDisposition(code: String): String? {
        val upperCode = code.uppercase()
        return when {
            upperCode.startsWith("PARSE_") || upperCode == "MODEL_OUTPUT_INVALID" -> "parser_reject"
            upperCode == "MODEL_OUTPUT_UNSUPPORTED_ACTION" -> "validator_reject"
            upperCode.contains("GUARDRAIL") || upperCode.contains("PHASE_BLOCKED") -> "control_reject"
            else -> null
        }
    }

    private fun buildClickDiagnostics(step: MutableStep): ClickDiagnosticsReport? {
        if (step.parsedActionType != "CLICK") {
            return null
        }
        val hasValues = step.beforeObservationDisplayId != null ||
            step.annotatedScreenshotPath != null ||
            step.injectDisplayId != null ||
            step.injectedX != null ||
            step.injectedY != null ||
            step.afterObservationDisplayId != null ||
            step.afterScreenshotPath != null ||
            step.afterScreenshotWidth != null ||
            step.afterScreenshotHeight != null ||
            step.afterObservationCode != null ||
            step.afterObservationMessage != null
        if (!hasValues) {
            return null
        }
        return ClickDiagnosticsReport(
            beforeObservationDisplayId = step.beforeObservationDisplayId,
            annotatedScreenshotPath = step.annotatedScreenshotPath,
            injectDisplayId = step.injectDisplayId,
            injectedX = step.injectedX,
            injectedY = step.injectedY,
            afterObservationDisplayId = step.afterObservationDisplayId,
            afterScreenshotPath = step.afterScreenshotPath,
            afterScreenshotWidth = step.afterScreenshotWidth,
            afterScreenshotHeight = step.afterScreenshotHeight,
            afterObservationCode = step.afterObservationCode,
            afterObservationMessage = step.afterObservationMessage
        )
    }

    private fun buildControlDiagnostics(step: MutableStep): ControlStepDiagnosticsReport? {
        val hasValues = step.canonicalActionType != null ||
            step.finalActionType != null ||
            step.guardrailRule != null ||
            step.phaseBefore != null ||
            step.phaseAfter != null ||
            step.completionVerdict != null ||
            step.observationChanged != null ||
            step.lowYield != null
        if (!hasValues) {
            return null
        }
        return ControlStepDiagnosticsReport(
            canonicalActionType = step.canonicalActionType,
            canonicalCoordSpace = step.canonicalCoordSpace,
            canonicalActionArgs = step.canonicalActionArgs,
            rawModelAction = step.canonicalRawModelAction,
            sanitizerPath = step.canonicalSanitizerPath,
            sourceModel = step.canonicalSourceModel,
            sourceMode = step.canonicalSourceMode,
            proposedActionType = step.proposedActionType,
            finalActionType = step.finalActionType,
            guardrailRule = step.guardrailRule,
            guardrailDecision = step.guardrailDecision,
            phaseBefore = step.phaseBefore,
            phaseAfter = step.phaseAfter,
            completionVerdict = step.completionVerdict,
            observationChanged = step.observationChanged,
            lowYield = step.lowYield
        )
    }

    private fun buildModelOutputDiagnostics(step: MutableStep): ModelOutputDiagnosticsReport? {
        val hasValues = step.proxySanitizerPath != null || step.modelTerminalDisposition != null
        if (!hasValues) {
            return null
        }
        return ModelOutputDiagnosticsReport(
            proxySanitizerPath = step.proxySanitizerPath,
            terminalDisposition = step.modelTerminalDisposition
        )
    }

    private fun extractProxySanitizerPath(rawResponseText: String): String? {
        val parsed = runCatching { JsonLite.parse(rawResponseText) }.getOrNull()
        val obj = parsed?.asObjectOrNull() ?: return null
        val meta = obj.fields["_meta"] as? JsonLiteValue.Obj ?: return null
        return meta.optionalString("sanitizerPath").ifBlank { null }
    }

    private fun persistClickAnnotationLocked(
        run: MutableRun,
        stepState: MutableStep,
        action: GuiAction.Click
    ): String? {
        val beforeFile = artifactFile(run, stepState.stepIndex, "before")
        if (!beforeFile.exists()) {
            return null
        }
        return runCatching {
            val decoded = BitmapFactory.decodeFile(beforeFile.absolutePath) ?: return@runCatching null
            val annotated = decoded.copy(Bitmap.Config.ARGB_8888, true)
            if (annotated == null) {
                decoded.recycle()
                return@runCatching null
            }
            if (annotated !== decoded) {
                decoded.recycle()
            }
            try {
                Canvas(annotated).apply {
                    drawClickMarker(
                        width = annotated.width,
                        height = annotated.height,
                        rawX = action.x,
                        rawY = action.y
                    )
                }
                val outputFile = artifactFile(run, stepState.stepIndex, "click_annotated")
                outputFile.outputStream().use { stream ->
                    annotated.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                "${run.relativeRunDir}/assets/${outputFile.name}"
            } finally {
                annotated.recycle()
            }
        }.onFailure { error ->
            Log.w(
                TAG,
                "failed to persist click annotation sessionId=${run.sessionId} step=${stepState.stepIndex}",
                error
            )
        }.getOrNull()
    }

    private fun Canvas.drawClickMarker(width: Int, height: Int, rawX: Int, rawY: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        val x = rawX.coerceIn(0, width - 1).toFloat()
        val y = rawY.coerceIn(0, height - 1).toFloat()
        val radius = (minOf(width, height) * 0.025f).coerceIn(16f, 48f)
        val cross = radius * 1.6f
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (radius * 0.35f).coerceAtLeast(4f)
        }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = (radius * 0.2f).coerceAtLeast(3f)
        }
        drawCircle(x, y, radius, outer)
        drawCircle(x, y, radius, inner)
        drawLine(x - cross, y, x + cross, y, outer)
        drawLine(x, y - cross, x, y + cross, outer)
        drawLine(x - cross, y, x + cross, y, inner)
        drawLine(x, y - cross, x, y + cross, inner)
    }

    private fun artifactFile(run: MutableRun, step: Int, artifactLabel: String): File {
        return File(run.assetsDir, "step_${step.toString().padStart(3, '0')}_${artifactLabel}.png")
    }
}
