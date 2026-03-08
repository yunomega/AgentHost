package com.example.agenthost.automation

import com.example.agenthost.config.ModelConfig
import com.example.agenthost.config.PlannerConfig
import com.example.agenthost.core.AgentHostLog
import com.example.agenthost.model.FakeModelClient
import com.example.agenthost.model.HttpModelClient
import java.util.concurrent.ConcurrentHashMap

class PlannerSelector(
    private val history: AutomationHistory,
    private val plannerConfigProvider: () -> PlannerConfig
) : PlanningAdapter {
    companion object {
        private const val TAG = "PlannerSelector"
    }

    private data class SessionPlanner(
        val mode: PlanningMode,
        val adapter: PlanningAdapter
    )

    private val planners = ConcurrentHashMap<String, SessionPlanner>()

    override fun modeForSession(sessionId: String): PlanningMode {
        return planners[sessionId]?.mode ?: plannerConfigProvider().mode
    }

    override fun planNext(request: PlanningRequest): PlanResult {
        val sessionPlanner = planners.computeIfAbsent(request.sessionId) {
            buildSessionPlanner(request)
        }
        return sessionPlanner.adapter.planNext(request.copy(planningMode = sessionPlanner.mode))
    }

    override fun clearSession(sessionId: String) {
        planners.remove(sessionId)?.adapter?.clearSession(sessionId)
    }

    private fun buildSessionPlanner(request: PlanningRequest): SessionPlanner {
        val plannerConfig = plannerConfigProvider()
        val mode = plannerConfig.mode
        val adapter = when (mode) {
            PlanningMode.SCRIPTED -> ScriptedStepPlanningAdapter(history)
            PlanningMode.MODEL,
            PlanningMode.MODEL_WITH_SCRIPTED_FALLBACK -> buildModelAdapter(mode, plannerConfig.modelConfig)

            PlanningMode.MOCK_MODEL -> buildMockModelAdapter(mode, plannerConfig.modelConfig)
        }
        AgentHostLog.i(
            TAG,
            "event=planner_mode_selected sessionId=${request.sessionId} vdDisplayId=${request.vdDisplayId} " +
                "sessionTaskId=${request.sessionTaskId} step=${request.step} plannerMode=${mode.name} " +
                "actionType=NONE code=PLANNER_MODE_SELECTED details=${plannerConfig.modelConfig.sanitizedSummary()}"
        )
        return SessionPlanner(mode = mode, adapter = adapter)
    }

    private fun buildModelAdapter(mode: PlanningMode, modelConfig: ModelConfig): PlanningAdapter {
        if (!modelConfig.hasEndpoint() || !modelConfig.hasResolvedApiKey()) {
            return UnavailablePlanningAdapter(
                mode = mode,
                code = "MODEL_UNAVAILABLE",
                message = "planner mode requires endpoint and api key"
            )
        }
        return ModelPlanningAdapter(
            mode = mode,
            modelClient = HttpModelClient(modelConfig),
            maxResponseChars = modelConfig.maxResponseChars
        )
    }

    private fun buildMockModelAdapter(mode: PlanningMode, modelConfig: ModelConfig): PlanningAdapter {
        val mockResponse = modelConfig.mockResponse?.trim().orEmpty().ifEmpty {
            "{\"action\":\"DONE\",\"reason\":\"mock_model_default\"}"
        }
        return ModelPlanningAdapter(
            mode = mode,
            modelClient = FakeModelClient {
                com.example.agenthost.model.ModelResponse.Success(mockResponse)
            },
            maxResponseChars = modelConfig.maxResponseChars
        )
    }

    private class UnavailablePlanningAdapter(
        private val mode: PlanningMode,
        private val code: String,
        private val message: String
    ) : PlanningAdapter {
        override fun modeForSession(sessionId: String): PlanningMode = mode

        override fun planNext(request: PlanningRequest): PlanResult {
            return PlanResult.PlanFailure(code = code, message = message)
        }

        override fun clearSession(sessionId: String) = Unit
    }
}
