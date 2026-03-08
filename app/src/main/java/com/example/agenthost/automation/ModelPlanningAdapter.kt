package com.example.agenthost.automation

import com.example.agenthost.core.AgentHostLog
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
    private val maxResponseChars: Int = 4_096
) : PlanningAdapter {
    companion object {
        private const val TAG = "ModelPlanningAdapter"
    }

    override fun modeForSession(sessionId: String): PlanningMode = mode

    override fun planNext(request: PlanningRequest): PlanResult {
        val modelRequest = ModelRequest(
            context = request.toModelContext(),
            systemPrompt = buildSystemPrompt(request.capabilityProfile),
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
                val snippet = truncateForLogs(response.outputText, maxResponseChars)
                log(
                    event = "model_request_success",
                    request = request,
                    code = "MODEL_RESPONSE_OK",
                    details = "responseChars=${response.outputText.length}"
                )
                when (val parsed = actionParser.parseSingleAction(response.outputText)) {
                    is ParseResult.ParseError -> {
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
                        when (val validation = capabilityValidator.validate(parsed.action, request.capabilityProfile)) {
                            is CapabilityValidationResult.Invalid -> {
                                log(
                                    event = "model_output_unsupported_action",
                                    request = request,
                                    code = validation.code,
                                    details = appendSnippet(validation.message, snippet)
                                )
                                PlanResult.PlanFailure(
                                    code = validation.code,
                                    message = validation.message,
                                    rawResponseSnippet = snippet
                                )
                            }

                            CapabilityValidationResult.Valid -> PlanResult.PlannedAction(parsed.action)
                        }
                    }
                }
            }
        }
    }

    override fun clearSession(sessionId: String) = Unit

    private fun buildSystemPrompt(capabilityProfile: CapabilityProfile): String {
        return buildString {
            append("You are a single-step Android planner for the current session only. ")
            append("Return exactly one JSON object and nothing else. ")
            append("Do not return markdown, arrays, steps containers, multiple objects, or natural language. ")
            append("Do not use HOME. Do not assume default-display fallback. Do not relaunch apps or guess packages. ")
            append("Only plan one next action using the current session display. ")
            append("Capabilities:\n")
            append(capabilityProfile.summaryText())
        }
    }

    private fun buildUserPrompt(request: PlanningRequest): String {
        val observationText = when (val observation = request.observation) {
            is ObservationResult.Captured -> {
                "observation_status=captured width=${observation.width} height=${observation.height} " +
                    "base64_png_chars=${observation.base64Png.length}"
            }

            is ObservationResult.Failed -> {
                "observation_status=failed code=${observation.code} message=${observation.message}"
            }
        }
        val actions = if (request.recentActions.isEmpty()) {
            "none"
        } else {
            request.recentActions.joinToString(separator = " | ") {
                "step=${it.step} action=${it.actionType} code=${it.code} summary=${it.summary}"
            }
        }
        return buildString {
            append("command=")
            append(request.command)
            append('\n')
            append("sessionId=")
            append(request.sessionId)
            append('\n')
            append("vdDisplayId=")
            append(request.vdDisplayId)
            append('\n')
            append("sessionTaskId=")
            append(request.sessionTaskId)
            append('\n')
            append("step=")
            append(request.step)
            append('\n')
            append(request.capabilityProfile.summaryText())
            append('\n')
            append(observationText)
            append('\n')
            append("recent_actions=")
            append(actions)
            append('\n')
            append("last_failure_code=")
            append(request.lastFailureCode ?: "null")
            append('\n')
            append("last_user_input=")
            append(request.lastUserReply ?: "null")
            append('\n')
            append("Return exactly one JSON object representing the single next action.")
        }
    }

    private fun PlanningRequest.toModelContext(): ModelContext {
        return ModelContext(
            sessionId = sessionId,
            command = command,
            vdDisplayId = vdDisplayId,
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
            lastUserReply = lastUserReply
        )
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
            "event=$event sessionId=${request.sessionId} vdDisplayId=${request.vdDisplayId} " +
                "sessionTaskId=${request.sessionTaskId} step=${request.step} plannerMode=${mode.name} " +
                "actionType=NONE code=$code details=$details"
        )
    }
}
