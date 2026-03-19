package com.example.agenthost.debug

import android.content.Context
import com.example.agenthost.automation.ActionExecutionResult
import com.example.agenthost.automation.CanonicalAction
import com.example.agenthost.automation.GuiAction
import com.example.agenthost.automation.ObservationResult
import com.example.agenthost.automation.PlanningRequest
import com.example.agenthost.session.SessionState

object TaskRunReportHooks {
    fun prepareNextSessionSource(source: String) = Unit

    fun isReportEnabled(context: Context): Boolean = false

    fun setReportEnabled(context: Context, enabled: Boolean) = Unit

    fun dumpConfig(context: Context): Map<String, Any?> {
        return mapOf(
            "taskReportEnabled" to false,
            "taskReportStorageRoot" to null
        )
    }

    fun onSessionCreated(
        context: Context,
        sessionId: String,
        task: String,
        displayMode: String,
        bootstrapTarget: String,
        plannerMode: String
    ) = Unit

    fun updatePlannerMode(sessionId: String, plannerMode: String) = Unit

    fun shouldCollectClickDiagnostics(sessionId: String): Boolean = false

    fun onSessionEvent(
        event: String,
        code: String,
        state: SessionState?,
        sessionId: String?,
        step: Int?
    ) = Unit

    fun onStepBegin(
        sessionId: String,
        step: Int,
        displayId: Int?,
        topActivity: String?,
        topPackage: String?
    ) = Unit

    fun onObservation(
        sessionId: String,
        step: Int,
        observation: ObservationResult,
        observationDisplayId: Int,
        displayId: Int?,
        topActivity: String?,
        topPackage: String?
    ) = Unit

    fun onModelInput(request: PlanningRequest) = Unit

    fun onModelResponse(
        sessionId: String,
        step: Int,
        rawResponseText: String
    ) = Unit

    fun onPlanFailure(
        sessionId: String,
        step: Int,
        code: String,
        details: String,
        rawResponseText: String? = null
    ) = Unit

    fun onActionPlanned(sessionId: String, step: Int, action: GuiAction) = Unit

    fun onControlActionResolved(
        sessionId: String,
        step: Int,
        proposedAction: CanonicalAction,
        finalAction: CanonicalAction,
        phaseBefore: String,
        guardrailRule: String?,
        guardrailDecision: String
    ) = Unit

    fun onControlStepEvaluated(
        sessionId: String,
        step: Int,
        phaseAfter: String,
        completionVerdict: String,
        observationChanged: Boolean?,
        lowYield: Boolean?
    ) = Unit

    fun onPlatformResult(
        sessionId: String,
        step: Int,
        action: GuiAction,
        result: ActionExecutionResult,
        injectDisplayId: Int? = null
    ) = Unit

    fun onClickAfterObservation(
        sessionId: String,
        step: Int,
        observationDisplayId: Int,
        observation: ObservationResult
    ) = Unit

    fun onSyntheticActionResult(
        sessionId: String,
        step: Int,
        action: GuiAction,
        code: String,
        details: String
    ) = Unit

    fun finalizeRun(
        sessionId: String,
        finalState: String,
        finalOutcome: String,
        activeSessionResidual: Boolean,
        finalCode: String
    ) = Unit
}
