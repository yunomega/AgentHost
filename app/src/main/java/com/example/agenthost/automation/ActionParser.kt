package com.example.agenthost.automation

sealed class ParseResult {
    data class ParseSuccess(val action: GuiAction) : ParseResult()

    data class ParseError(
        val code: String,
        val message: String
    ) : ParseResult()
}

interface ActionParser {
    fun parseSingleAction(rawResponse: String): ParseResult
}
