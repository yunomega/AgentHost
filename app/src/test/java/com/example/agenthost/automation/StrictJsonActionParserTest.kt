package com.example.agenthost.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonActionParserTest {
    private val parser = StrictJsonActionParser()

    @Test
    fun parseSingleObjectSuccess() {
        val result = parser.parseSingleAction("""{"action":"CLICK","x":1,"y":2}""")
        assertTrue(result is ParseResult.ParseSuccess)
        assertEquals("CLICK", (result as ParseResult.ParseSuccess).action.actionType())
    }

    @Test
    fun rejectArrayResponse() {
        val result = parser.parseSingleAction("""[{"action":"CLICK","x":1,"y":2}]""")
        assertTrue(result is ParseResult.ParseError)
        assertEquals(
            "PARSE_MULTI_ACTION_UNSUPPORTED",
            (result as ParseResult.ParseError).code
        )
    }

    @Test
    fun rejectTrailingContent() {
        val result = parser.parseSingleAction("""{"action":"DONE"}{"action":"BACK"}""")
        assertTrue(result is ParseResult.ParseError)
        assertEquals("PARSE_TRAILING_CONTENT", (result as ParseResult.ParseError).code)
    }

    @Test
    fun rejectStepsContainer() {
        val result = parser.parseSingleAction("""{"steps":[{"action":"BACK"}]}""")
        assertTrue(result is ParseResult.ParseError)
        assertEquals(
            "PARSE_MULTI_ACTION_UNSUPPORTED",
            (result as ParseResult.ParseError).code
        )
    }
}
