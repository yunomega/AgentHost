package com.example.agenthost.automation

import java.util.concurrent.ConcurrentHashMap

class ScriptedStepPlanningAdapter(
    private val history: AutomationHistory
) : PlanningAdapter {
    private data class ScriptPlan(
        val steps: List<GuiAction>
    )

    private val plans = ConcurrentHashMap<String, Result<ScriptPlan>>()

    override fun planNext(request: PlanningRequest): PlanResult {
        val planResult = plans.computeIfAbsent(request.sessionId) {
            parsePlan(request.command)
        }

        val plan = planResult.getOrElse {
            return PlanResult.PlanFailure(
                code = "SCRIPT_PARSE_FAILED",
                message = it.message ?: "Script command parse failed"
            )
        }

        val cursor = history.getScriptCursor(request.sessionId)
        if (cursor >= plan.steps.size) {
            return PlanResult.PlannedAction(GuiAction.Done("script_finished"))
        }

        val raw = plan.steps[cursor]
        val resolved = resolveAction(raw, request.lastUserReply)
        if (resolved is GuiAction.AskUser && raw !is GuiAction.AskUser) {
            return PlanResult.PlannedAction(resolved)
        }

        history.setScriptCursor(request.sessionId, cursor + 1)
        return PlanResult.PlannedAction(resolved)
    }

    override fun clearSession(sessionId: String) {
        plans.remove(sessionId)
    }

    private fun parsePlan(command: String): Result<ScriptPlan> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return Result.success(ScriptPlan(listOf(GuiAction.Done("empty_command"))))
        }

        return runCatching {
            val parsed = JsonLite.parse(trimmed)
            val steps = when (parsed) {
                is JsonLiteValue.Obj -> {
                    val stepsValue = parsed.fields["steps"]
                    if (stepsValue == null) {
                        listOf(parseAction(parsed))
                    } else {
                        val array = stepsValue as? JsonLiteValue.Arr
                            ?: throw IllegalArgumentException("steps must be an array")
                        parseStepsArray(array)
                    }
                }

                is JsonLiteValue.Arr -> parseStepsArray(parsed)
                else -> throw IllegalArgumentException("command must be a JSON object or array")
            }
            ScriptPlan(
                steps = if (steps.isEmpty()) {
                    listOf(GuiAction.Done("empty_steps"))
                } else {
                    steps
                }
            )
        }
    }

    private fun parseStepsArray(array: JsonLiteValue.Arr): List<GuiAction> {
        val steps = mutableListOf<GuiAction>()
        array.items.forEachIndexed { index, item ->
            val obj = item as? JsonLiteValue.Obj
                ?: throw IllegalArgumentException("steps[$index] must be an object")
            steps += parseAction(obj)
        }
        return steps
    }

    private fun parseAction(obj: JsonLiteValue.Obj): GuiAction {
        val action = obj.optionalString("action").trim().uppercase()
        if (action.isBlank()) {
            throw IllegalArgumentException("missing action field")
        }

        return when (action) {
            "CLICK" -> GuiAction.Click(
                x = obj.requireInt("x"),
                y = obj.requireInt("y")
            )

            "LONG_PRESS" -> GuiAction.LongPress(
                x = obj.requireInt("x"),
                y = obj.requireInt("y"),
                durationMs = obj.optionalLong("durationMs", 800L).coerceAtLeast(1L)
            )

            "SWIPE" -> GuiAction.Swipe(
                x1 = obj.requireInt("x1"),
                y1 = obj.requireInt("y1"),
                x2 = obj.requireInt("x2"),
                y2 = obj.requireInt("y2"),
                durationMs = obj.optionalLong("durationMs", 300L).coerceAtLeast(1L)
            )

            "TYPE_TEXT" -> GuiAction.TypeText(
                text = obj.optionalString("text").ifBlank {
                    throw IllegalArgumentException("text must not be blank")
                }
            )

            "BACK" -> GuiAction.Back
            "HOME" -> GuiAction.Home
            "WAIT" -> GuiAction.Wait(
                durationMs = obj.optionalLong("durationMs", 500L).coerceAtLeast(1L)
            )

            "ASK_USER" -> GuiAction.AskUser(
                question = obj.optionalString("question").ifBlank {
                    throw IllegalArgumentException("question must not be blank")
                }
            )

            "DONE" -> GuiAction.Done(
                reason = obj.optionalString("reason", "done")
            )

            else -> throw IllegalArgumentException("unsupported action=$action")
        }
    }

    private fun resolveAction(action: GuiAction, lastUserReply: String?): GuiAction {
        if (action !is GuiAction.TypeText) {
            return action
        }

        val placeholder = "{{last_user_input}}"
        if (!action.text.contains(placeholder)) {
            return action
        }

        if (lastUserReply.isNullOrBlank()) {
            return GuiAction.AskUser("Please provide user input text")
        }

        return GuiAction.TypeText(action.text.replace(placeholder, lastUserReply))
    }
}
