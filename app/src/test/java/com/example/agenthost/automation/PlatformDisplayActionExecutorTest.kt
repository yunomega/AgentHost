package com.example.agenthost.automation

import com.example.agenthost.platform.PlatformActionResponse
import com.example.agenthost.platform.PlatformAutomationGateway
import com.example.agenthost.platform.PlatformCaptureResponse
import com.example.agenthost.platform.PlatformResultCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDisplayActionExecutorTest {
    @Test
    fun reportsUnsupportedWhenServiceUnavailable() {
        val executor = PlatformDisplayActionExecutor(FakeGateway(available = false))

        val capabilities = executor.capabilities()

        assertFalse(capabilities.supported)
        assertEquals("UNSUPPORTED", capabilities.code)
    }

    @Test
    fun mapsClickIntoTapCall() {
        val gateway = FakeGateway()
        val executor = PlatformDisplayActionExecutor(gateway)

        val result = executor.execute(
            ActionExecutionRequest(
                sessionId = "s1",
                vdDisplayId = 23,
                action = GuiAction.Click(10, 20)
            )
        )

        assertTrue(result.ok)
        assertEquals("tap:s1:23:10:20", gateway.lastCall)
    }

    @Test
    fun forwardsGatewayFailureWithoutFallback() {
        val gateway = FakeGateway(
            actionResponse = PlatformActionResponse(
                ok = false,
                code = PlatformResultCodes.DISPLAY_NOT_FOUND,
                details = "released"
            )
        )
        val executor = PlatformDisplayActionExecutor(gateway)

        val result = executor.execute(
            ActionExecutionRequest(
                sessionId = "s1",
                vdDisplayId = 23,
                action = GuiAction.Click(10, 20)
            )
        )

        assertFalse(result.ok)
        assertEquals(PlatformResultCodes.DISPLAY_NOT_FOUND, result.code)
        assertEquals("released", result.details)
        assertEquals("tap:s1:23:10:20", gateway.lastCall)
    }

    @Test
    fun rejectsUnsupportedActionInExecutor() {
        val gateway = FakeGateway()
        val executor = PlatformDisplayActionExecutor(gateway)

        val result = executor.execute(
            ActionExecutionRequest(
                sessionId = "s1",
                vdDisplayId = 23,
                action = GuiAction.Wait(100)
            )
        )

        assertFalse(result.ok)
        assertEquals("EXECUTOR_ACTION_UNSUPPORTED", result.code)
    }

    private class FakeGateway(
        private val available: Boolean = true,
        private val actionResponse: PlatformActionResponse = PlatformActionResponse(
            ok = true,
            code = PlatformResultCodes.OK,
            details = "ok"
        )
    ) : PlatformAutomationGateway {
        var lastCall: String? = null

        override fun isAvailable(): Boolean = available

        override fun captureDisplay(sessionId: String, displayId: Int): PlatformCaptureResponse {
            return PlatformCaptureResponse(ok = true, code = PlatformResultCodes.OK, details = "ok")
        }

        override fun injectTap(sessionId: String, displayId: Int, x: Int, y: Int): PlatformActionResponse {
            lastCall = "tap:$sessionId:$displayId:$x:$y"
            return actionResponse
        }

        override fun injectSwipe(
            sessionId: String,
            displayId: Int,
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            durationMs: Long
        ): PlatformActionResponse {
            lastCall = "swipe:$sessionId:$displayId:$x1:$y1:$x2:$y2:$durationMs"
            return actionResponse
        }

        override fun injectKey(sessionId: String, displayId: Int, keyCode: Int): PlatformActionResponse {
            lastCall = "key:$sessionId:$displayId:$keyCode"
            return actionResponse
        }

        override fun injectText(sessionId: String, displayId: Int, text: String): PlatformActionResponse {
            lastCall = "text:$sessionId:$displayId:$text"
            return actionResponse
        }

        override fun injectLongPress(
            sessionId: String,
            displayId: Int,
            x: Int,
            y: Int,
            durationMs: Long
        ): PlatformActionResponse {
            lastCall = "longPress:$sessionId:$displayId:$x:$y:$durationMs"
            return actionResponse
        }
    }
}
