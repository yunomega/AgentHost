package com.example.agenthost.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunReportRendererTest {
    @Test
    fun completedStateMismatchWinsForSuccessfulResidualSession() {
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-1",
            overview = overview(
                finalSessionState = "COMPLETED",
                finalOutcome = "success",
                activeSessionResidual = true
            ),
            steps = listOf(successfulDoneStep()),
            lifecycle = lifecycle(activeSessionResidualAfterEnd = true)
        )

        assertEquals("COMPLETED_STATE_MISMATCH", report.resultTag)
        assertEquals("STATE_MISMATCH", report.diagnostics.likelyProblemLayer)
    }

    @Test
    fun platformRejectedAfterModelSuccessUsesModelOkPlatformRejectedTag() {
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-2",
            overview = overview(finalSessionState = "FAILED", finalOutcome = "failed"),
            steps = listOf(
                step(
                    parsedActionType = "CLICK",
                    modelReturnedSuccessfully = true,
                    platformAttempted = true,
                    platformOk = false,
                    platformCode = "INJECTION_REJECTED",
                    failureLayer = "PLATFORM",
                    failureCode = "INJECTION_REJECTED",
                    failureDetails = "rejected"
                )
            ),
            lifecycle = lifecycle()
        )

        assertEquals("MODEL_OK_PLATFORM_REJECTED", report.resultTag)
        assertEquals("PLATFORM", report.diagnostics.likelyProblemLayer)
    }

    @Test
    fun parseFailureIsTaggedSeparately() {
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-3",
            overview = overview(finalSessionState = "FAILED", finalOutcome = "failed"),
            steps = listOf(
                step(
                    parsedActionType = null,
                    modelReturnedSuccessfully = true,
                    failureLayer = "PARSE",
                    failureCode = "PARSE_INVALID_JSON",
                    failureDetails = "bad json"
                )
            ),
            lifecycle = lifecycle()
        )

        assertEquals("PARSE_FAILED", report.resultTag)
    }

    @Test
    fun askUserActionAloneDoesNotImplyWaitingUserTerminalState() {
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-ask-user-terminal",
            overview = overview(finalSessionState = "FAILED", finalOutcome = "failed"),
            steps = listOf(
                step(
                    parsedActionType = "ASK_USER",
                    modelReturnedSuccessfully = true
                )
            ),
            lifecycle = lifecycle(
                lastEvent = TaskRunLifecycleEvent(
                    timeMs = 2_000L,
                    event = "report_finalize",
                    code = "AUTOMATION_STOPPED",
                    status = "FAILED",
                    automationStep = 1,
                    migratedToMain = false,
                    recoveryNeeded = false,
                    currentVisibleDisplayId = 3,
                    lastMigrationDirection = null,
                    lastMigrationCode = null,
                    lastError = "AUTOMATION_STOPPED"
                )
            )
        )

        assertEquals("INTERRUPTED", report.resultTag)
        assertEquals("UNKNOWN", report.diagnostics.likelyProblemLayer)
    }

    @Test
    fun timelineMigrationFlagsDoNotOverrideCanonicalFinalSnapshot() {
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-migration-flags",
            overview = overview(finalSessionState = "FAILED", finalOutcome = "failed"),
            steps = emptyList(),
            lifecycle = lifecycle(
                migratedToMainOccurred = true,
                recoveryNeededSeen = true,
                lastEvent = TaskRunLifecycleEvent(
                    timeMs = 2_000L,
                    event = "report_finalize",
                    code = "FAILED",
                    status = "FAILED",
                    automationStep = 0,
                    migratedToMain = false,
                    recoveryNeeded = false,
                    currentVisibleDisplayId = 3,
                    lastMigrationDirection = null,
                    lastMigrationCode = null,
                    lastError = "FAILED"
                )
            )
        )

        assertEquals("INTERRUPTED", report.resultTag)
        assertEquals("UNKNOWN", report.diagnostics.likelyProblemLayer)
    }

    @Test
    fun finalSnapshotRecoveryCodeStillMapsToMigrationDiagnosis() {
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-final-snapshot-migration",
            overview = overview(finalSessionState = "FAILED", finalOutcome = "failed"),
            steps = emptyList(),
            lifecycle = lifecycle(
                lastEvent = TaskRunLifecycleEvent(
                    timeMs = 2_000L,
                    event = "report_finalize",
                    code = "TARGET_TASK_REMOVED",
                    status = "FAILED",
                    automationStep = 2,
                    migratedToMain = true,
                    recoveryNeeded = true,
                    currentVisibleDisplayId = 0,
                    lastMigrationDirection = "TO_MAIN",
                    lastMigrationCode = "TARGET_TASK_REMOVED",
                    lastError = "TARGET_TASK_REMOVED"
                )
            )
        )

        assertEquals("INTERRUPTED", report.resultTag)
        assertEquals("MIGRATION", report.diagnostics.likelyProblemLayer)
    }

    @Test
    fun markdownSummarizesRawResponseButJsonKeepsFullText() {
        val tail = "TAIL_MARKER_1234567890"
        val rawResponse = "{\"action\":\"CLICK\",\"x\":120,\"y\":300,\"reason\":\"" +
            "abcdefghijklmnopqrstuvwxyz".repeat(20) + tail + "\"}"
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-4",
            overview = overview(finalSessionState = "COMPLETED", finalOutcome = "success"),
            steps = listOf(
                step(
                    parsedActionType = "CLICK",
                    modelReturnedSuccessfully = true,
                    modelRawResponseText = rawResponse,
                    actionArgs = mapOf("x" to 120, "y" to 300),
                    clickDiagnostics = ClickDiagnosticsReport(
                        beforeObservationDisplayId = 3,
                        annotatedScreenshotPath = "reports/task_runs/run/assets/step_001_click_annotated.png",
                        injectDisplayId = 3,
                        injectedX = 120,
                        injectedY = 300,
                        afterObservationDisplayId = 3,
                        afterScreenshotPath = "reports/task_runs/run/assets/step_001_after.png",
                        afterScreenshotWidth = 1080,
                        afterScreenshotHeight = 1920,
                        afterObservationCode = "CAPTURED",
                        afterObservationMessage = null
                    ),
                    platformAttempted = true,
                    platformOk = true,
                    platformCode = "OK"
                )
            ),
            lifecycle = lifecycle()
        )

        val markdown = TaskRunReportRenderer.renderMarkdown(report)
        val json = TaskRunReportRenderer.renderJson(report)

        assertTrue(json.contains(tail))
        assertTrue(markdown.contains("modelRawResponseSummary"))
        assertFalse(markdown.contains(tail))
        assertTrue(markdown.contains(TaskRunReportRenderer.summarizeRawModelResponse(rawResponse).orEmpty()))
        assertTrue(json.contains("\"clickDiagnostics\""))
        assertTrue(json.contains("step_001_click_annotated.png"))
        assertTrue(json.contains("\"injectDisplayId\":3"))
        assertTrue(markdown.contains("clickDiagnostics: beforeDisplayId=3"))
        assertTrue(markdown.contains("step_001_after.png"))
    }

    @Test
    fun reportAggregatesModelOutputMetricsAndRendersStepDiagnostics() {
        val report = TaskRunReportAnalyzer.buildReport(
            runKey = "run-5",
            overview = overview(finalSessionState = "FAILED", finalOutcome = "failed"),
            steps = listOf(
                step(
                    stepIndex = 1,
                    parsedActionType = "CLICK",
                    modelReturnedSuccessfully = true,
                    modelOutputDiagnostics = ModelOutputDiagnosticsReport(
                        proxySanitizerPath = "direct_valid",
                        terminalDisposition = "accepted"
                    )
                ),
                step(
                    stepIndex = 2,
                    parsedActionType = null,
                    modelReturnedSuccessfully = true,
                    modelOutputDiagnostics = ModelOutputDiagnosticsReport(
                        proxySanitizerPath = "json_extract",
                        terminalDisposition = "parser_reject"
                    ),
                    failureLayer = "PARSE",
                    failureCode = "PARSE_INVALID_JSON",
                    failureDetails = "bad json"
                ),
                step(
                    stepIndex = 3,
                    parsedActionType = null,
                    modelReturnedSuccessfully = true,
                    modelOutputDiagnostics = ModelOutputDiagnosticsReport(
                        proxySanitizerPath = "repair",
                        terminalDisposition = "validator_reject"
                    ),
                    failureLayer = "PARSE",
                    failureCode = "MODEL_OUTPUT_UNSUPPORTED_ACTION",
                    failureDetails = "unsupported"
                ),
                step(
                    stepIndex = 4,
                    parsedActionType = null,
                    modelReturnedSuccessfully = true,
                    modelOutputDiagnostics = ModelOutputDiagnosticsReport(
                        proxySanitizerPath = "legacy_parse",
                        terminalDisposition = "control_reject"
                    ),
                    failureLayer = "CONTROL",
                    failureCode = "MODEL_OUTPUT_PHASE_BLOCKED",
                    failureDetails = "blocked"
                )
            ),
            lifecycle = lifecycle()
        )

        val metrics = report.diagnostics.modelOutputMetrics
        val markdown = TaskRunReportRenderer.renderMarkdown(report)
        val json = TaskRunReportRenderer.renderJson(report)

        assertEquals(4, metrics.totalModelResponses)
        assertEquals(1, metrics.directValidCount)
        assertEquals(1, metrics.jsonExtractCount)
        assertEquals(1, metrics.repairCount)
        assertEquals(1, metrics.legacyParseCount)
        assertEquals(1, metrics.parserRejectCount)
        assertEquals(1, metrics.validatorRejectCount)
        assertEquals(1, metrics.controlRejectCount)
        assertTrue(markdown.contains("## Model Output Metrics"))
        assertTrue(markdown.contains("totalModelResponses: 4"))
        assertTrue(markdown.contains("parserReject: 1 (0.25)"))
        assertTrue(markdown.contains("validatorReject: 1 (0.25)"))
        assertTrue(markdown.contains("controlReject: 1 (0.25)"))
        assertTrue(markdown.contains("modelOutputDiagnostics: proxySanitizerPath=direct_valid terminalDisposition=accepted"))
        assertTrue(json.contains("\"modelOutputMetrics\""))
        assertTrue(json.contains("\"proxySanitizerPath\":\"legacy_parse\""))
        assertTrue(json.contains("\"terminalDisposition\":\"control_reject\""))
    }

    private fun overview(
        finalSessionState: String,
        finalOutcome: String,
        activeSessionResidual: Boolean = false
    ): TaskRunOverview {
        return TaskRunOverview(
            task = "open settings",
            sessionId = "session-1",
            source = "DEBUG",
            displayMode = "VIRTUAL_DISPLAY",
            bootstrapTarget = "SETTINGS",
            plannerMode = "MODEL",
            startTimeMs = 1_000L,
            endTimeMs = 2_000L,
            totalDurationMs = 1_000L,
            finalSessionState = finalSessionState,
            finalOutcome = finalOutcome,
            activeSessionResidual = activeSessionResidual,
            reportPath = "reports/task_runs/run/report.md",
            detailPath = "reports/task_runs/run/report.json"
        )
    }

    private fun lifecycle(
        activeSessionResidualAfterEnd: Boolean = false,
        migratedToMainOccurred: Boolean = false,
        recoveryNeededSeen: Boolean = false,
        lastEvent: TaskRunLifecycleEvent? = null
    ): TaskRunLifecycle {
        return TaskRunLifecycle(
            events = listOf(
                TaskRunLifecycleEvent(
                    timeMs = 1_000L,
                    event = "session_ready",
                    code = "SESSION_READY",
                    status = "READY",
                    automationStep = 0,
                    migratedToMain = false,
                    recoveryNeeded = false,
                    currentVisibleDisplayId = 3,
                    lastMigrationDirection = null,
                    lastMigrationCode = null,
                    lastError = null
                )
            ) + listOfNotNull(lastEvent),
            migratedToMainOccurred = migratedToMainOccurred,
            migrateBackOccurred = false,
            recoveryNeededSeen = recoveryNeededSeen,
            activeSessionResidualAfterEnd = activeSessionResidualAfterEnd
        )
    }

    private fun successfulDoneStep(): TaskRunStepReport {
        return step(
            parsedActionType = "DONE",
            modelReturnedSuccessfully = true,
            modelRawResponseText = "{\"action\":\"DONE\",\"reason\":\"finished\"}",
            actionArgs = mapOf("reason" to "finished")
        )
    }

    private fun step(
        stepIndex: Int = 1,
        parsedActionType: String?,
        modelReturnedSuccessfully: Boolean,
        modelRawResponseText: String? = null,
        modelOutputDiagnostics: ModelOutputDiagnosticsReport? = null,
        actionArgs: Map<String, Any?> = emptyMap(),
        clickDiagnostics: ClickDiagnosticsReport? = null,
        platformAttempted: Boolean = false,
        platformOk: Boolean? = null,
        platformCode: String? = null,
        failureLayer: String? = null,
        failureCode: String? = null,
        failureDetails: String? = null
    ): TaskRunStepReport {
        return TaskRunStepReport(
            stepIndex = stepIndex,
            stepStartTimeMs = 1_200L,
            stepEndTimeMs = 1_400L,
            durationMs = 200L,
            screenshotPath = "reports/task_runs/run/assets/step_001_before.png",
            screenshotWidth = 1080,
            screenshotHeight = 1920,
            topActivity = "com.android.settings/.Settings",
            topPackage = "com.android.settings",
            displayId = 3,
            modelInputSummary = ModelInputSummary(
                commandPreview = "open settings",
                executionDisplayId = 3,
                sessionTaskId = 21,
                observationStatus = "CAPTURED",
                observationWidth = 1080,
                observationHeight = 1920,
                recentActionCount = 0,
                lastFailureCode = null,
                hasLastUserReply = false
            ),
            modelRawResponseText = modelRawResponseText,
            modelReturnedSuccessfully = modelReturnedSuccessfully,
            modelOutputDiagnostics = modelOutputDiagnostics,
            parsedActionType = parsedActionType,
            actionArgs = actionArgs,
            platformAttempted = platformAttempted,
            platformOk = platformOk,
            platformCode = platformCode,
            platformDetails = if (platformCode == null) null else "details=$platformCode",
            failureLayer = failureLayer,
            failureCode = failureCode,
            failureDetails = failureDetails,
            clickDiagnostics = clickDiagnostics
        )
    }
}
