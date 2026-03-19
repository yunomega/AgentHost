package com.example.agenthost.report

import com.example.agenthost.migration.TaskMigrationPolicy

object TaskRunReportAnalyzer {
    fun buildReport(
        runKey: String,
        overview: TaskRunOverview,
        steps: List<TaskRunStepReport>,
        lifecycle: TaskRunLifecycle
    ): TaskRunReport {
        val resultTag = classifyResultTag(overview, steps, lifecycle)
        val diagnostics = buildDiagnostics(overview, steps, lifecycle, resultTag)
        return TaskRunReport(
            runKey = runKey,
            resultTag = resultTag,
            overview = overview,
            steps = steps.sortedBy { it.stepIndex },
            lifecycle = lifecycle,
            diagnostics = diagnostics
        )
    }

    internal fun classifyResultTag(
        overview: TaskRunOverview,
        steps: List<TaskRunStepReport>,
        lifecycle: TaskRunLifecycle
    ): String {
        if (overview.finalOutcome == "success" && lifecycle.activeSessionResidualAfterEnd) {
            return "COMPLETED_STATE_MISMATCH"
        }
        if (overview.finalOutcome == "success") {
            return if (steps.any { it.modelReturnedSuccessfully }) {
                "MODEL_OK_COMPLETED"
            } else {
                "COMPLETED"
            }
        }
        val failedStep = steps.firstOrNull { it.failureLayer != null || it.platformOk == false }
        if (failedStep != null) {
            if (failedStep.failureLayer == "PARSE") {
                return "PARSE_FAILED"
            }
            if (failedStep.failureLayer == "PLATFORM" && failedStep.modelReturnedSuccessfully) {
                return "MODEL_OK_PLATFORM_REJECTED"
            }
            if (failedStep.failureLayer == "MIGRATION") {
                return "MIGRATION_FAILED"
            }
            if (failedStep.failureLayer == "MODEL") {
                return "MODEL_FAILED"
            }
            if (failedStep.failureLayer == "CONTROL") {
                return "CONTROL_BLOCKED"
            }
            if (failedStep.failureLayer == "TIMEOUT") {
                return "TIMEOUT"
            }
            if (failedStep.failureLayer == "STATE_MISMATCH") {
                return "COMPLETED_STATE_MISMATCH"
            }
        }
        if (overview.finalSessionState == "WAITING_USER") {
            return "WAITING_USER_STALLED"
        }
        return "INTERRUPTED"
    }

    private fun buildDiagnostics(
        overview: TaskRunOverview,
        steps: List<TaskRunStepReport>,
        lifecycle: TaskRunLifecycle,
        resultTag: String
    ): TaskRunDiagnostics {
        val lastSuccessfulAction = steps
            .asReversed()
            .firstOrNull { it.parsedActionType != null && isSuccessfulAction(it) }
            ?.let { "step=${it.stepIndex} ${it.parsedActionType}" }
        val firstFailedAction = steps
            .firstOrNull { it.failureLayer != null || it.platformOk == false }
            ?.let { step ->
                val label = step.parsedActionType ?: "NONE"
                val code = step.failureCode ?: step.platformCode ?: "UNKNOWN"
                "step=${step.stepIndex} $label code=$code"
            }
        val modelReturnedSuccessfully = steps.any { it.modelReturnedSuccessfully }
        val platformExecutedSuccessfully = steps.any { it.platformAttempted && it.platformOk == true }
        val platformKeyDispatchSummary = buildPlatformKeyDispatchSummary(steps)
        val modelTransportSummary = buildModelTransportSummary(steps)
        val modelOutputMetrics = buildModelOutputMetrics(steps)
        val likelyProblemLayer = determineLikelyProblemLayer(overview, steps, lifecycle, resultTag)
        val stoppedAtStep = steps.maxOfOrNull { it.stepIndex }
        return TaskRunDiagnostics(
            lastSuccessfulAction = lastSuccessfulAction,
            firstFailedAction = firstFailedAction,
            modelReturnedSuccessfully = modelReturnedSuccessfully,
            platformExecutedSuccessfully = platformExecutedSuccessfully,
            platformKeyDispatchSummary = platformKeyDispatchSummary,
            modelTransportSummary = modelTransportSummary,
            modelOutputMetrics = modelOutputMetrics,
            likelyProblemLayer = likelyProblemLayer,
            conclusion = TaskRunConclusion(
                stoppedAtStep = stoppedAtStep,
                modelReturnedSuccessfully = modelReturnedSuccessfully,
                platformExecutedSuccessfully = platformExecutedSuccessfully,
                mostLikelyProblemLayer = likelyProblemLayer
            )
        )
    }

    private fun isSuccessfulAction(step: TaskRunStepReport): Boolean {
        return when {
            step.platformAttempted -> step.platformOk == true
            else -> step.failureLayer == null
        }
    }

    private fun buildPlatformKeyDispatchSummary(steps: List<TaskRunStepReport>): String? {
        val keySteps = steps.filter { it.parsedActionType == "BACK" || it.parsedActionType == "HOME" }
        if (keySteps.isEmpty()) {
            return null
        }
        return keySteps.joinToString(separator = " | ") { step ->
            "step=${step.stepIndex} ${step.parsedActionType} ok=${step.platformOk ?: false} " +
                "code=${step.platformCode ?: "NONE"}"
        }
    }

    private fun buildModelTransportSummary(steps: List<TaskRunStepReport>): List<String> {
        val summary = mutableListOf<String>()
        if (steps.any { it.modelReturnedSuccessfully }) {
            summary += "MODEL_RESPONSE_OK observed"
        }
        val modelFailureCodes = steps.mapNotNull { step ->
            if (step.failureLayer == "MODEL") {
                step.failureCode
            } else {
                null
            }
        }.distinct()
        summary += modelFailureCodes
        return summary
    }

    private fun buildModelOutputMetrics(steps: List<TaskRunStepReport>): ModelOutputMetrics {
        val successfulResponses = steps.filter { it.modelReturnedSuccessfully }
        val total = successfulResponses.size
        val directValidCount = successfulResponses.count {
            it.modelOutputDiagnostics?.proxySanitizerPath == "direct_valid"
        }
        val jsonExtractCount = successfulResponses.count {
            it.modelOutputDiagnostics?.proxySanitizerPath == "json_extract"
        }
        val repairCount = successfulResponses.count {
            it.modelOutputDiagnostics?.proxySanitizerPath == "repair"
        }
        val legacyParseCount = successfulResponses.count {
            it.modelOutputDiagnostics?.proxySanitizerPath == "legacy_parse"
        }
        val parserRejectCount = successfulResponses.count {
            it.modelOutputDiagnostics?.terminalDisposition == "parser_reject"
        }
        val validatorRejectCount = successfulResponses.count {
            it.modelOutputDiagnostics?.terminalDisposition == "validator_reject"
        }
        val controlRejectCount = successfulResponses.count {
            it.modelOutputDiagnostics?.terminalDisposition == "control_reject"
        }
        return ModelOutputMetrics(
            totalModelResponses = total,
            directValidCount = directValidCount,
            jsonExtractCount = jsonExtractCount,
            repairCount = repairCount,
            legacyParseCount = legacyParseCount,
            parserRejectCount = parserRejectCount,
            validatorRejectCount = validatorRejectCount,
            controlRejectCount = controlRejectCount,
            directValidRate = rate(directValidCount, total),
            jsonExtractRate = rate(jsonExtractCount, total),
            repairRate = rate(repairCount, total),
            legacyParseRate = rate(legacyParseCount, total),
            parserRejectRate = rate(parserRejectCount, total),
            validatorRejectRate = rate(validatorRejectCount, total),
            controlRejectRate = rate(controlRejectCount, total)
        )
    }

    private fun determineLikelyProblemLayer(
        overview: TaskRunOverview,
        steps: List<TaskRunStepReport>,
        lifecycle: TaskRunLifecycle,
        resultTag: String
    ): String {
        if (overview.finalOutcome == "success") {
            return if (lifecycle.activeSessionResidualAfterEnd || resultTag == "COMPLETED_STATE_MISMATCH") {
                "STATE_MISMATCH"
            } else {
                "NONE"
            }
        }
        val finalSnapshot = finalRuntimeSnapshot(lifecycle)
        return steps.firstOrNull { it.failureLayer != null || it.platformOk == false }?.failureLayer
            ?: when {
                finalSnapshot?.recoveryNeeded == true -> "MIGRATION"
                TaskMigrationPolicy.requiresRecovery(finalSnapshot?.lastMigrationCode) -> "MIGRATION"
                overview.finalSessionState == "WAITING_USER" -> "STATE_MISMATCH"
                else -> "UNKNOWN"
            }
    }

    private fun finalRuntimeSnapshot(lifecycle: TaskRunLifecycle): TaskRunLifecycleEvent? {
        return lifecycle.events.lastOrNull()
    }

    private fun rate(count: Int, total: Int): Double {
        if (total <= 0) {
            return 0.0
        }
        return count.toDouble() / total.toDouble()
    }
}
