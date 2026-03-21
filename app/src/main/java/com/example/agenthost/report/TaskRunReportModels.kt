package com.example.agenthost.report

data class TaskRunReport(
    val reportVersion: String = "v1",
    val runKey: String,
    val resultTag: String,
    val overview: TaskRunOverview,
    val steps: List<TaskRunStepReport>,
    val lifecycle: TaskRunLifecycle,
    val diagnostics: TaskRunDiagnostics
)

data class TaskRunOverview(
    val task: String,
    val sessionId: String,
    val source: String,
    val displayMode: String,
    val bootstrapTarget: String,
    val plannerMode: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val totalDurationMs: Long,
    val finalSessionState: String,
    val finalOutcome: String,
    val activeSessionResidual: Boolean,
    val reportPath: String,
    val detailPath: String
)

data class TaskRunStepReport(
    val stepIndex: Int,
    val stepStartTimeMs: Long?,
    val stepEndTimeMs: Long?,
    val durationMs: Long?,
    val screenshotPath: String?,
    val screenshotWidth: Int?,
    val screenshotHeight: Int?,
    val topActivity: String?,
    val topPackage: String?,
    val displayId: Int?,
    val modelInputSummary: ModelInputSummary?,
    val modelRawResponseText: String?,
    val modelReturnedSuccessfully: Boolean,
    val modelOutputDiagnostics: ModelOutputDiagnosticsReport? = null,
    val parsedActionType: String?,
    val actionArgs: Map<String, Any?>,
    val platformAttempted: Boolean,
    val platformOk: Boolean?,
    val platformCode: String?,
    val platformDetails: String?,
    val failureLayer: String?,
    val failureCode: String?,
    val failureDetails: String?,
    val clickDiagnostics: ClickDiagnosticsReport? = null,
    val controlDiagnostics: ControlStepDiagnosticsReport? = null
)

data class ClickDiagnosticsReport(
    val beforeObservationDisplayId: Int?,
    val annotatedScreenshotPath: String?,
    val injectDisplayId: Int?,
    val injectedX: Int?,
    val injectedY: Int?,
    val afterObservationDisplayId: Int?,
    val afterScreenshotPath: String?,
    val afterScreenshotWidth: Int?,
    val afterScreenshotHeight: Int?,
    val afterObservationCode: String?,
    val afterObservationMessage: String?
)

data class ModelInputSummary(
    val commandPreview: String,
    val executionDisplayId: Int,
    val sessionTaskId: Int,
    val observationStatus: String,
    val observationWidth: Int?,
    val observationHeight: Int?,
    val recentActionCount: Int,
    val lastFailureCode: String?,
    val hasLastUserReply: Boolean,
    val currentPhase: String? = null,
    val allowedActions: List<String> = emptyList(),
    val blockedActions: List<String> = emptyList(),
    val lastResult: String? = null,
    val completionState: String? = null,
    val doneOnly: Boolean = false
)

data class ModelOutputDiagnosticsReport(
    val proxySanitizerPath: String?,
    val terminalDisposition: String?
)

data class ControlStepDiagnosticsReport(
    val canonicalActionType: String?,
    val canonicalCoordSpace: String?,
    val canonicalActionArgs: Map<String, Any?> = emptyMap(),
    val rawModelAction: String?,
    val sanitizerPath: String?,
    val sourceModel: String?,
    val sourceMode: String?,
    val proposedActionType: String?,
    val finalActionType: String?,
    val guardrailRule: String?,
    val guardrailDecision: String?,
    val phaseBefore: String?,
    val phaseAfter: String?,
    val completionVerdict: String?,
    val observationChanged: Boolean?,
    val lowYield: Boolean?
)

data class TaskRunLifecycle(
    val events: List<TaskRunLifecycleEvent>,
    val migratedToMainOccurred: Boolean,
    val migrateBackOccurred: Boolean,
    val recoveryNeededSeen: Boolean,
    val activeSessionResidualAfterEnd: Boolean
)

data class TaskRunLifecycleEvent(
    val timeMs: Long,
    val event: String,
    val code: String,
    val status: String?,
    val automationStep: Int?,
    val migratedToMain: Boolean?,
    val recoveryNeeded: Boolean?,
    val currentVisibleDisplayId: Int?,
    val lastMigrationDirection: String?,
    val lastMigrationCode: String?,
    val lastError: String?,
    val bootstrapComponent: String? = null,
    val lastResolvedBootstrapComponent: String? = null
)

data class TaskRunDiagnostics(
    val lastSuccessfulAction: String?,
    val firstFailedAction: String?,
    val modelReturnedSuccessfully: Boolean,
    val platformExecutedSuccessfully: Boolean,
    val platformKeyDispatchSummary: String?,
    val modelTransportSummary: List<String>,
    val modelOutputMetrics: ModelOutputMetrics,
    val likelyProblemLayer: String,
    val conclusion: TaskRunConclusion
)

data class ModelOutputMetrics(
    val totalModelResponses: Int,
    val directValidCount: Int,
    val jsonExtractCount: Int,
    val repairCount: Int,
    val legacyParseCount: Int,
    val parserRejectCount: Int,
    val validatorRejectCount: Int,
    val controlRejectCount: Int,
    val directValidRate: Double,
    val jsonExtractRate: Double,
    val repairRate: Double,
    val legacyParseRate: Double,
    val parserRejectRate: Double,
    val validatorRejectRate: Double,
    val controlRejectRate: Double
)

data class TaskRunConclusion(
    val stoppedAtStep: Int?,
    val modelReturnedSuccessfully: Boolean,
    val platformExecutedSuccessfully: Boolean,
    val mostLikelyProblemLayer: String
)
