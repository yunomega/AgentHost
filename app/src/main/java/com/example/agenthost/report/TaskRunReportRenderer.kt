package com.example.agenthost.report

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TaskRunReportRenderer {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .withZone(ZoneId.systemDefault())

    fun renderMarkdown(report: TaskRunReport): String {
        return buildString {
            appendLine("# Task Run Report")
            appendLine()
            appendLine("- resultTag: ${report.resultTag}")
            appendLine("- reportVersion: ${report.reportVersion}")
            appendLine("- reportPath: ${report.overview.reportPath}")
            appendLine("- detailPath: ${report.overview.detailPath}")
            appendLine()
            appendLine("## Task Overview")
            appendLine("- task: ${report.overview.task}")
            appendLine("- sessionId: ${report.overview.sessionId}")
            appendLine("- source: ${report.overview.source}")
            appendLine("- displayMode: ${report.overview.displayMode}")
            appendLine("- bootstrapTarget: ${report.overview.bootstrapTarget}")
            appendLine("- plannerMode: ${report.overview.plannerMode}")
            appendLine("- startTime: ${formatTime(report.overview.startTimeMs)}")
            appendLine("- endTime: ${formatTime(report.overview.endTimeMs)}")
            appendLine("- totalDurationMs: ${report.overview.totalDurationMs}")
            appendLine("- finalSessionState: ${report.overview.finalSessionState}")
            appendLine("- finalOutcome: ${report.overview.finalOutcome}")
            appendLine("- activeSessionResidual: ${report.overview.activeSessionResidual}")
            appendLine()
            appendLine("## Steps")
            if (report.steps.isEmpty()) {
                appendLine("- none")
            } else {
                report.steps.forEach { step ->
                    appendLine("### Step ${step.stepIndex}")
                    appendLine("- stepStartTime: ${formatNullableTime(step.stepStartTimeMs)}")
                    appendLine("- stepEndTime: ${formatNullableTime(step.stepEndTimeMs)}")
                    appendLine("- durationMs: ${step.durationMs ?: "null"}")
                    appendLine("- screenshot: ${step.screenshotPath ?: "null"}")
                    appendLine("- screenshotSize: ${formatSize(step.screenshotWidth, step.screenshotHeight)}")
                    appendLine("- topActivity: ${step.topActivity ?: "null"}")
                    appendLine("- topPackage: ${step.topPackage ?: "null"}")
                    appendLine("- displayId: ${step.displayId ?: "null"}")
                    appendLine("- modelInputSummary: ${formatModelInputSummary(step.modelInputSummary)}")
                    appendLine("- modelRawResponseSummary: ${summarizeRawModelResponse(step.modelRawResponseText) ?: "null"}")
                    appendLine("- modelOutputDiagnostics: ${formatModelOutputDiagnosticsSummary(step.modelOutputDiagnostics)}")
                    appendLine("- parsedActionType: ${step.parsedActionType ?: "null"}")
                    appendLine("- actionArgs: ${formatMap(step.actionArgs)}")
                    appendLine("- platformAttempted: ${step.platformAttempted}")
                    appendLine("- platformOk: ${step.platformOk ?: "null"}")
                    appendLine("- platformCode: ${step.platformCode ?: "null"}")
                    appendLine("- platformDetails: ${step.platformDetails ?: "null"}")
                    appendLine("- failureLayer: ${step.failureLayer ?: "null"}")
                    appendLine("- failureCode: ${step.failureCode ?: "null"}")
                    appendLine("- failureDetails: ${step.failureDetails ?: "null"}")
                    appendLine("- clickDiagnostics: ${formatClickDiagnosticsSummary(step.clickDiagnostics)}")
                    appendLine("- controlDiagnostics: ${formatControlDiagnosticsSummary(step.controlDiagnostics)}")
                    appendLine()
                }
            }
            appendLine("## Lifecycle Timeline")
            appendLine("- migratedToMainOccurred: ${report.lifecycle.migratedToMainOccurred}")
            appendLine("- migrateBackOccurred: ${report.lifecycle.migrateBackOccurred}")
            appendLine("- recoveryNeededSeen: ${report.lifecycle.recoveryNeededSeen}")
            appendLine("- activeSessionResidualAfterEnd: ${report.lifecycle.activeSessionResidualAfterEnd}")
            if (report.lifecycle.events.isEmpty()) {
                appendLine("- timeline: none")
            } else {
                report.lifecycle.events.forEach { event ->
                    appendLine(
                        "- ${formatTime(event.timeMs)} ${event.event} code=${event.code} " +
                            "status=${event.status ?: "null"} step=${event.automationStep ?: "null"} " +
                            "migratedToMain=${event.migratedToMain ?: "null"} " +
                            "recoveryNeeded=${event.recoveryNeeded ?: "null"}"
                    )
                }
            }
            appendLine()
            appendLine("## Diagnostics")
            appendLine("- lastSuccessfulAction: ${report.diagnostics.lastSuccessfulAction ?: "none"}")
            appendLine("- firstFailedAction: ${report.diagnostics.firstFailedAction ?: "none"}")
            appendLine("- modelReturnedSuccessfully: ${report.diagnostics.modelReturnedSuccessfully}")
            appendLine("- platformExecutedSuccessfully: ${report.diagnostics.platformExecutedSuccessfully}")
            appendLine("- platformKeyDispatchSummary: ${report.diagnostics.platformKeyDispatchSummary ?: "none"}")
            appendLine("- modelTransportSummary: ${report.diagnostics.modelTransportSummary.joinToString().ifBlank { "none" }}")
            appendLine("- likelyProblemLayer: ${report.diagnostics.likelyProblemLayer}")
            appendLine()
            appendLine("## Model Output Metrics")
            appendLine("- totalModelResponses: ${report.diagnostics.modelOutputMetrics.totalModelResponses}")
            appendLine("- directValid: ${formatCountRate(report.diagnostics.modelOutputMetrics.directValidCount, report.diagnostics.modelOutputMetrics.directValidRate)}")
            appendLine("- jsonExtract: ${formatCountRate(report.diagnostics.modelOutputMetrics.jsonExtractCount, report.diagnostics.modelOutputMetrics.jsonExtractRate)}")
            appendLine("- repair: ${formatCountRate(report.diagnostics.modelOutputMetrics.repairCount, report.diagnostics.modelOutputMetrics.repairRate)}")
            appendLine("- legacyParse: ${formatCountRate(report.diagnostics.modelOutputMetrics.legacyParseCount, report.diagnostics.modelOutputMetrics.legacyParseRate)}")
            appendLine("- parserReject: ${formatCountRate(report.diagnostics.modelOutputMetrics.parserRejectCount, report.diagnostics.modelOutputMetrics.parserRejectRate)}")
            appendLine("- validatorReject: ${formatCountRate(report.diagnostics.modelOutputMetrics.validatorRejectCount, report.diagnostics.modelOutputMetrics.validatorRejectRate)}")
            appendLine("- controlReject: ${formatCountRate(report.diagnostics.modelOutputMetrics.controlRejectCount, report.diagnostics.modelOutputMetrics.controlRejectRate)}")
            appendLine()
            appendLine("## Conclusion")
            appendLine("1. 这次任务停在第 ${report.diagnostics.conclusion.stoppedAtStep ?: 0} 步。")
            appendLine(
                "2. 模型${if (report.diagnostics.conclusion.modelReturnedSuccessfully) "有" else "没有"}成功返回。"
            )
            appendLine(
                "3. 平台${if (report.diagnostics.conclusion.platformExecutedSuccessfully) "有" else "没有"}成功执行。"
            )
            appendLine("4. 当前最像 ${report.diagnostics.conclusion.mostLikelyProblemLayer} 层问题。")
        }
    }

    fun renderJson(report: TaskRunReport): String {
        return writeJson(
            mapOf(
                "reportVersion" to report.reportVersion,
                "runKey" to report.runKey,
                "resultTag" to report.resultTag,
                "overview" to report.overview.toJsonValue(),
                "steps" to report.steps.map { it.toJsonValue() },
                "lifecycle" to report.lifecycle.toJsonValue(),
                "diagnostics" to report.diagnostics.toJsonValue()
            )
        )
    }

    internal fun summarizeRawModelResponse(text: String?, maxChars: Int = 240): String? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) {
            return null
        }
        val flattened = value.replace('\n', ' ')
        return if (flattened.length <= maxChars) {
            flattened
        } else {
            flattened.take(maxChars) + "..."
        }
    }

    private fun TaskRunOverview.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "task" to task,
            "sessionId" to sessionId,
            "source" to source,
            "displayMode" to displayMode,
            "bootstrapTarget" to bootstrapTarget,
            "plannerMode" to plannerMode,
            "startTime" to formatTime(startTimeMs),
            "endTime" to formatTime(endTimeMs),
            "totalDurationMs" to totalDurationMs,
            "finalSessionState" to finalSessionState,
            "finalOutcome" to finalOutcome,
            "activeSessionResidual" to activeSessionResidual,
            "reportPath" to reportPath,
            "detailPath" to detailPath
        )
    }

    private fun TaskRunStepReport.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "stepIndex" to stepIndex,
            "stepStartTime" to stepStartTimeMs?.let(::formatTime),
            "stepEndTime" to stepEndTimeMs?.let(::formatTime),
            "durationMs" to durationMs,
            "screenshot" to mapOf(
                "path" to screenshotPath,
                "width" to screenshotWidth,
                "height" to screenshotHeight
            ),
            "deviceContext" to mapOf(
                "topActivity" to topActivity,
                "topPackage" to topPackage,
                "displayId" to displayId
            ),
            "model" to mapOf(
                "inputSummary" to modelInputSummary?.toJsonValue(),
                "rawResponseText" to modelRawResponseText,
                "responseOk" to modelReturnedSuccessfully,
                "outputDiagnostics" to modelOutputDiagnostics?.toJsonValue()
            ),
            "parsedAction" to mapOf(
                "type" to parsedActionType,
                "args" to actionArgs
            ),
            "platform" to mapOf(
                "attempted" to platformAttempted,
                "ok" to platformOk,
                "code" to platformCode,
                "details" to platformDetails
            ),
            "failureLayer" to failureLayer,
            "failureCode" to failureCode,
            "failureDetails" to failureDetails,
            "clickDiagnostics" to clickDiagnostics?.toJsonValue(),
            "controlDiagnostics" to controlDiagnostics?.toJsonValue()
        )
    }

    private fun ClickDiagnosticsReport.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "beforeObservationDisplayId" to beforeObservationDisplayId,
            "annotatedScreenshotPath" to annotatedScreenshotPath,
            "injectDisplayId" to injectDisplayId,
            "injectedX" to injectedX,
            "injectedY" to injectedY,
            "afterObservationDisplayId" to afterObservationDisplayId,
            "afterScreenshotPath" to afterScreenshotPath,
            "afterScreenshotWidth" to afterScreenshotWidth,
            "afterScreenshotHeight" to afterScreenshotHeight,
            "afterObservationCode" to afterObservationCode,
            "afterObservationMessage" to afterObservationMessage
        )
    }

    private fun ModelInputSummary.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "commandPreview" to commandPreview,
            "executionDisplayId" to executionDisplayId,
            "sessionTaskId" to sessionTaskId,
            "observationStatus" to observationStatus,
            "observationWidth" to observationWidth,
            "observationHeight" to observationHeight,
            "recentActionCount" to recentActionCount,
            "lastFailureCode" to lastFailureCode,
            "hasLastUserReply" to hasLastUserReply,
            "currentPhase" to currentPhase,
            "allowedActions" to allowedActions,
            "blockedActions" to blockedActions,
            "lastResult" to lastResult,
            "completionState" to completionState,
            "doneOnly" to doneOnly
        )
    }

    private fun ModelOutputDiagnosticsReport.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "proxySanitizerPath" to proxySanitizerPath,
            "terminalDisposition" to terminalDisposition
        )
    }

    private fun ControlStepDiagnosticsReport.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "canonicalActionType" to canonicalActionType,
            "canonicalCoordSpace" to canonicalCoordSpace,
            "canonicalActionArgs" to canonicalActionArgs,
            "rawModelAction" to rawModelAction,
            "sanitizerPath" to sanitizerPath,
            "sourceModel" to sourceModel,
            "sourceMode" to sourceMode,
            "proposedActionType" to proposedActionType,
            "finalActionType" to finalActionType,
            "guardrailRule" to guardrailRule,
            "guardrailDecision" to guardrailDecision,
            "phaseBefore" to phaseBefore,
            "phaseAfter" to phaseAfter,
            "completionVerdict" to completionVerdict,
            "observationChanged" to observationChanged,
            "lowYield" to lowYield
        )
    }

    private fun TaskRunLifecycle.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "events" to events.map { it.toJsonValue() },
            "migratedToMainOccurred" to migratedToMainOccurred,
            "migrateBackOccurred" to migrateBackOccurred,
            "recoveryNeededSeen" to recoveryNeededSeen,
            "activeSessionResidualAfterEnd" to activeSessionResidualAfterEnd
        )
    }

    private fun TaskRunLifecycleEvent.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "time" to formatTime(timeMs),
            "event" to event,
            "code" to code,
            "status" to status,
            "automationStep" to automationStep,
            "migratedToMain" to migratedToMain,
            "recoveryNeeded" to recoveryNeeded,
            "currentVisibleDisplayId" to currentVisibleDisplayId,
            "lastMigrationDirection" to lastMigrationDirection,
            "lastMigrationCode" to lastMigrationCode,
            "lastError" to lastError
        )
    }

    private fun TaskRunDiagnostics.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "lastSuccessfulAction" to lastSuccessfulAction,
            "firstFailedAction" to firstFailedAction,
            "modelReturnedSuccessfully" to modelReturnedSuccessfully,
            "platformExecutedSuccessfully" to platformExecutedSuccessfully,
            "platformKeyDispatchSummary" to platformKeyDispatchSummary,
            "modelTransportSummary" to modelTransportSummary,
            "modelOutputMetrics" to modelOutputMetrics.toJsonValue(),
            "likelyProblemLayer" to likelyProblemLayer,
            "conclusion" to conclusion.toJsonValue()
        )
    }

    private fun ModelOutputMetrics.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "totalModelResponses" to totalModelResponses,
            "directValidCount" to directValidCount,
            "jsonExtractCount" to jsonExtractCount,
            "repairCount" to repairCount,
            "legacyParseCount" to legacyParseCount,
            "parserRejectCount" to parserRejectCount,
            "validatorRejectCount" to validatorRejectCount,
            "controlRejectCount" to controlRejectCount,
            "directValidRate" to directValidRate,
            "jsonExtractRate" to jsonExtractRate,
            "repairRate" to repairRate,
            "legacyParseRate" to legacyParseRate,
            "parserRejectRate" to parserRejectRate,
            "validatorRejectRate" to validatorRejectRate,
            "controlRejectRate" to controlRejectRate
        )
    }

    private fun TaskRunConclusion.toJsonValue(): Map<String, Any?> {
        return mapOf(
            "stoppedAtStep" to stoppedAtStep,
            "modelReturnedSuccessfully" to modelReturnedSuccessfully,
            "platformExecutedSuccessfully" to platformExecutedSuccessfully,
            "mostLikelyProblemLayer" to mostLikelyProblemLayer
        )
    }

    private fun formatTime(value: Long): String {
        return timeFormatter.format(Instant.ofEpochMilli(value))
    }

    private fun formatNullableTime(value: Long?): String {
        return value?.let(::formatTime) ?: "null"
    }

    private fun formatSize(width: Int?, height: Int?): String {
        return if (width == null || height == null) {
            "null"
        } else {
            "${width}x${height}"
        }
    }

    private fun formatModelInputSummary(summary: ModelInputSummary?): String {
        if (summary == null) {
            return "null"
        }
        return "command=${summary.commandPreview} executionDisplayId=${summary.executionDisplayId} " +
            "sessionTaskId=${summary.sessionTaskId} observation=${summary.observationStatus} " +
            "size=${formatSize(summary.observationWidth, summary.observationHeight)} " +
            "recentActionCount=${summary.recentActionCount} " +
            "lastFailureCode=${summary.lastFailureCode ?: "null"} " +
            "hasLastUserReply=${summary.hasLastUserReply} " +
            "phase=${summary.currentPhase ?: "null"} " +
            "allowed=${summary.allowedActions.joinToString(",").ifEmpty { "none" }} " +
            "blocked=${summary.blockedActions.joinToString(",").ifEmpty { "none" }} " +
            "lastResult=${summary.lastResult ?: "null"} " +
            "completionState=${summary.completionState ?: "null"} " +
            "doneOnly=${summary.doneOnly}"
    }

    private fun formatMap(values: Map<String, Any?>): String {
        if (values.isEmpty()) {
            return "{}"
        }
        return values.entries.joinToString(prefix = "{", postfix = "}") { entry ->
            "${entry.key}=${entry.value}"
        }
    }

    private fun formatModelOutputDiagnosticsSummary(value: ModelOutputDiagnosticsReport?): String {
        if (value == null) {
            return "null"
        }
        return "proxySanitizerPath=${value.proxySanitizerPath ?: "null"} " +
            "terminalDisposition=${value.terminalDisposition ?: "null"}"
    }

    private fun formatCountRate(count: Int, rate: Double): String {
        return "$count (${String.format(Locale.US, "%.2f", rate)})"
    }

    private fun formatClickDiagnosticsSummary(value: ClickDiagnosticsReport?): String {
        if (value == null) {
            return "null"
        }
        return buildString {
            append("beforeDisplayId=")
            append(value.beforeObservationDisplayId ?: "null")
            append(" annotated=")
            append(value.annotatedScreenshotPath ?: "null")
            append(" injectDisplayId=")
            append(value.injectDisplayId ?: "null")
            append(" injected=(")
            append(value.injectedX ?: "null")
            append(",")
            append(value.injectedY ?: "null")
            append(")")
            append(" afterDisplayId=")
            append(value.afterObservationDisplayId ?: "null")
            append(" after=")
            append(value.afterScreenshotPath ?: "null")
            append(" afterSize=")
            append(formatSize(value.afterScreenshotWidth, value.afterScreenshotHeight))
            append(" afterCode=")
            append(value.afterObservationCode ?: "null")
            append(" afterMessage=")
            append(value.afterObservationMessage ?: "null")
        }
    }

    private fun formatControlDiagnosticsSummary(value: ControlStepDiagnosticsReport?): String {
        if (value == null) {
            return "null"
        }
        return buildString {
            append("canonicalType=").append(value.canonicalActionType ?: "null")
            append(" coordSpace=").append(value.canonicalCoordSpace ?: "null")
            append(" sanitizerPath=").append(value.sanitizerPath ?: "null")
            append(" proposed=").append(value.proposedActionType ?: "null")
            append(" final=").append(value.finalActionType ?: "null")
            append(" guardrailRule=").append(value.guardrailRule ?: "null")
            append(" guardrailDecision=").append(value.guardrailDecision ?: "null")
            append(" phase=").append(value.phaseBefore ?: "null").append("->").append(value.phaseAfter ?: "null")
            append(" completion=").append(value.completionVerdict ?: "null")
            append(" observationChanged=").append(value.observationChanged ?: "null")
            append(" lowYield=").append(value.lowYield ?: "null")
        }
    }

    private fun writeJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"" + escapeJson(value) + "\""
            is Number,
            is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { entry ->
                val key = entry.key as? String ?: entry.key.toString()
                "\"" + escapeJson(key) + "\":" + writeJson(entry.value)
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { item ->
                writeJson(item)
            }
            else -> "\"" + escapeJson(value.toString()) + "\""
        }
    }

    private fun escapeJson(value: String): String {
        val builder = StringBuilder(value.length + 16)
        value.forEach { ch ->
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000c' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        builder.append("\\u")
                        builder.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(ch)
                    }
                }
            }
        }
        return builder.toString()
    }
}
