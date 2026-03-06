package com.example.agenthost.automation

class StrictJsonActionParser : ActionParser {
    override fun parseSingleAction(rawResponse: String): ParseResult {
        val text = rawResponse.trim()
        if (text.isEmpty()) {
            return ParseResult.ParseError(
                code = "PARSE_EMPTY_RESPONSE",
                message = "Planner response is empty"
            )
        }

        val parsed = try {
            JsonLite.parse(text)
        } catch (t: Throwable) {
            val message = t.message ?: "Invalid JSON"
            if (message.contains("Trailing content", ignoreCase = true)) {
                return ParseResult.ParseError(
                    code = "PARSE_TRAILING_CONTENT",
                    message = message
                )
            }
            return ParseResult.ParseError(
                code = "PARSE_INVALID_JSON",
                message = message
            )
        }

        if (parsed is JsonLiteValue.Arr) {
            return ParseResult.ParseError(
                code = "PARSE_MULTI_ACTION_UNSUPPORTED",
                message = "JSON array is not allowed for single-step protocol"
            )
        }

        val obj = parsed.asObjectOrNull()
            ?: return ParseResult.ParseError(
                code = "PARSE_EXPECTED_OBJECT",
                message = "Expected a single JSON object"
            )

        if (obj.has("actions") || obj.has("steps")) {
            return ParseResult.ParseError(
                code = "PARSE_MULTI_ACTION_UNSUPPORTED",
                message = "actions/steps container is not allowed"
            )
        }

        return parseActionObject(obj)
    }

    private fun parseActionObject(obj: JsonLiteValue.Obj): ParseResult {
        val actionName = obj.optionalString("action").trim().uppercase()
        if (actionName.isBlank()) {
            return ParseResult.ParseError(
                code = "PARSE_MISSING_ACTION",
                message = "Missing action field"
            )
        }

        return try {
            val action = when (actionName) {
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

                else -> throw IllegalArgumentException("unsupported action=$actionName")
            }
            ParseResult.ParseSuccess(action)
        } catch (t: Throwable) {
            ParseResult.ParseError(
                code = "PARSE_INVALID_ACTION",
                message = t.message ?: "Invalid action payload"
            )
        }
    }
}
