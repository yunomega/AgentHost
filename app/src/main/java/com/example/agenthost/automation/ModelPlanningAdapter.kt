package com.example.agenthost.automation

import com.example.agenthost.core.AgentHostLog
import com.example.agenthost.debug.TaskRunReportHooks
import com.example.agenthost.model.ModelActionHistoryEntry
import com.example.agenthost.model.ModelClient
import com.example.agenthost.model.ModelContext
import com.example.agenthost.model.ModelObservation
import com.example.agenthost.model.ModelRequest
import com.example.agenthost.model.ModelResponse
import com.example.agenthost.model.truncateForLogs

class ModelPlanningAdapter(
    private val mode: PlanningMode,
    private val modelClient: ModelClient,
    private val actionParser: ActionParser = StrictJsonActionParser(),
    private val capabilityValidator: CapabilityValidator = CapabilityValidator(),
    private val canonicalActionMapper: CanonicalActionMapper = CanonicalActionMapper(),
    private val maxResponseChars: Int = 4_096
) : PlanningAdapter {
    companion object {
        private const val TAG = "ModelPlanningAdapter"
    }

    override fun modeForSession(sessionId: String): PlanningMode = mode

    override fun planNext(request: PlanningRequest): PlanResult {
        TaskRunReportHooks.onModelInput(request)
        val modelRequest = ModelRequest(
            context = request.toModelContext(),
            systemPrompt = buildSystemPrompt(request),
            userPrompt = buildUserPrompt(request),
            maxResponseChars = maxResponseChars
        )
        log(
            event = "model_request_begin",
            request = request,
            code = "MODEL_REQUEST_BEGIN",
            details = "recentActions=${request.recentActions.size} lastFailureCode=${request.lastFailureCode}"
        )

        return when (val response = modelClient.complete(modelRequest)) {
            is ModelResponse.Failure -> {
                TaskRunReportHooks.onPlanFailure(
                    sessionId = request.sessionId,
                    step = request.step,
                    code = response.code,
                    details = response.message,
                    rawResponseText = response.rawResponseSnippet
                )
                log(
                    event = "model_request_failed",
                    request = request,
                    code = response.code,
                    details = appendSnippet(response.message, response.rawResponseSnippet)
                )
                PlanResult.PlanFailure(
                    code = response.code,
                    message = response.message,
                    rawResponseSnippet = response.rawResponseSnippet
                )
            }

            is ModelResponse.Success -> {
                TaskRunReportHooks.onModelResponse(
                    sessionId = request.sessionId,
                    step = request.step,
                    rawResponseText = response.outputText
                )
                val snippet = truncateForLogs(response.outputText, maxResponseChars)
                log(
                    event = "model_request_success",
                    request = request,
                    code = "MODEL_RESPONSE_OK",
                    details = "responseChars=${response.outputText.length}"
                )
                when (val parsed = actionParser.parseSingleAction(response.outputText)) {
                    is ParseResult.ParseError -> {
                        TaskRunReportHooks.onPlanFailure(
                            sessionId = request.sessionId,
                            step = request.step,
                            code = parsed.code,
                            details = parsed.message,
                            rawResponseText = response.outputText
                        )
                        log(
                            event = "model_response_invalid",
                            request = request,
                            code = "MODEL_OUTPUT_INVALID",
                            details = appendSnippet(parsed.message, snippet)
                        )
                        PlanResult.PlanFailure(
                            code = "MODEL_OUTPUT_INVALID",
                            message = parsed.message,
                            rawResponseSnippet = snippet
                        )
                    }

                    is ParseResult.ParseSuccess -> {
                        when (val phaseValidation = validatePhaseAction(parsed.action, request.controlSnapshot)) {
                            is CapabilityValidationResult.Invalid -> {
                                TaskRunReportHooks.onPlanFailure(
                                    sessionId = request.sessionId,
                                    step = request.step,
                                    code = phaseValidation.code,
                                    details = phaseValidation.message,
                                    rawResponseText = response.outputText
                                )
                                log(
                                    event = "model_output_phase_blocked",
                                    request = request,
                                    code = phaseValidation.code,
                                    details = appendSnippet(phaseValidation.message, snippet)
                                )
                                PlanResult.PlanFailure(
                                    code = phaseValidation.code,
                                    message = phaseValidation.message,
                                    rawResponseSnippet = snippet
                                )
                            }

                            CapabilityValidationResult.Valid -> when (
                                val capabilityValidation =
                                    capabilityValidator.validate(parsed.action, request.capabilityProfile)
                            ) {
                                is CapabilityValidationResult.Invalid -> {
                                    TaskRunReportHooks.onPlanFailure(
                                        sessionId = request.sessionId,
                                        step = request.step,
                                        code = capabilityValidation.code,
                                        details = capabilityValidation.message,
                                        rawResponseText = response.outputText
                                    )
                                    log(
                                        event = "model_output_unsupported_action",
                                        request = request,
                                        code = capabilityValidation.code,
                                        details = appendSnippet(capabilityValidation.message, snippet)
                                    )
                                    PlanResult.PlanFailure(
                                        code = capabilityValidation.code,
                                        message = capabilityValidation.message,
                                        rawResponseSnippet = snippet
                                    )
                                }

                                CapabilityValidationResult.Valid -> {
                                    val canonicalAction = canonicalActionMapper.map(
                                        action = parsed.action,
                                        rawResponseText = response.outputText,
                                        sourceMode = mode.name
                                    )
                                    PlanResult.PlannedAction(
                                        action = parsed.action,
                                        canonicalAction = canonicalAction,
                                        rawResponseText = response.outputText
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun clearSession(sessionId: String) = Unit

    private fun buildSystemPrompt(request: PlanningRequest): String {
        return buildString {
            append("DEBUG_ONLY_ANDROID_SYSTEM_PROMPT ")
            append("sessionId=").append(request.sessionId)
            append(" step=").append(request.step)
            append(" plannerMode=").append(request.planningMode.name)
            append(" executionDisplayId=").append(request.executionDisplayId)
            append(" sessionTaskId=").append(request.sessionTaskId)
        }
    }

    private fun buildUserPrompt(request: PlanningRequest): String {
        return buildString {
            append("DEBUG_ONLY_ANDROID_USER_PROMPT ")
            append("command=").append(request.command.trim().replace('\n', ' ').take(120))
            append(" recentActionCount=").append(request.recentActions.size)
            append(" lastFailureCode=").append(request.lastFailureCode ?: "null")
            append(" hasLastUserReply=").append(!request.lastUserReply.isNullOrBlank())
            request.controlSnapshot?.let { snapshot ->
                append(" phase=").append(snapshot.currentPhase)
                append(" doneOnly=").append(snapshot.doneOnly)
            }
        }
    }

    private fun PlanningRequest.toModelContext(): ModelContext {
        return ModelContext(
            sessionId = sessionId,
            command = command,
            vdDisplayId = executionDisplayId,
            sessionTaskId = sessionTaskId,
            step = step,
            capabilityProfile = capabilityProfile,
            capabilitySummary = capabilityProfile.summaryText(),
            observation = when (val currentObservation = observation) {
                is ObservationResult.Captured -> ModelObservation(
                    status = "CAPTURED",
                    base64Png = currentObservation.base64Png,
                    width = currentObservation.width,
                    height = currentObservation.height
                )

                is ObservationResult.Failed -> ModelObservation(
                    status = "FAILED",
                    code = currentObservation.code,
                    message = currentObservation.message
                )
            },
            recentActions = recentActions.map {
                ModelActionHistoryEntry(
                    step = it.step,
                    actionType = it.actionType,
                    code = it.code,
                    summary = it.summary
                )
            },
            lastFailureCode = lastFailureCode,
            lastUserReply = lastUserReply,
            controlSnapshot = controlSnapshot
        )
    }

    private fun validatePhaseAction(
        action: GuiAction,
        controlSnapshot: AgentControlSnapshot?
    ): CapabilityValidationResult {
        val snapshot = controlSnapshot ?: return CapabilityValidationResult.Valid
        val actionType = action.actionType()
        return if (snapshot.blockedActions.contains(actionType)) {
            CapabilityValidationResult.Invalid(
                code = "MODEL_OUTPUT_PHASE_BLOCKED",
                message = "action=$actionType is blocked in currentPhase=${snapshot.currentPhase}"
            )
        } else {
            CapabilityValidationResult.Valid
        }
    }

    private fun appendSnippet(message: String, snippet: String?): String {
        return if (snippet.isNullOrBlank()) {
            message
        } else {
            "$message rawSnippet=$snippet"
        }
    }

    private fun log(
        event: String,
        request: PlanningRequest,
        code: String,
        details: String
    ) {
        AgentHostLog.i(
            TAG,
            "event=$event sessionId=${request.sessionId} executionDisplayId=${request.executionDisplayId} " +
                "sessionTaskId=${request.sessionTaskId} step=${request.step} plannerMode=${mode.name} " +
                "actionType=NONE code=$code details=$details"
        )
    }
}
