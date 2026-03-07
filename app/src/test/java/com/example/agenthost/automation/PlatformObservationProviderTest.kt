package com.example.agenthost.automation

import com.example.agenthost.platform.PlatformActionResponse
import com.example.agenthost.platform.PlatformAutomationGateway
import com.example.agenthost.platform.PlatformCaptureResponse
import com.example.agenthost.platform.PlatformResultCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformObservationProviderTest {
    @Test
    fun encodesReturnedPngBytesAsBase64() {
        val provider = PlatformObservationProvider(
            gateway = object : PlatformAutomationGateway {
                override fun isAvailable(): Boolean = true

                override fun captureDisplay(sessionId: String, displayId: Int): PlatformCaptureResponse {
                    return PlatformCaptureResponse(
                        ok = true,
                        code = PlatformResultCodes.OK,
                        details = "ok",
                        width = 12,
                        height = 34,
                        pngBytes = byteArrayOf(1, 2, 3)
                    )
                }

                override fun injectTap(sessionId: String, displayId: Int, x: Int, y: Int): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectSwipe(
                    sessionId: String,
                    displayId: Int,
                    x1: Int,
                    y1: Int,
                    x2: Int,
                    y2: Int,
                    durationMs: Long
                ): PlatformActionResponse = PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectKey(sessionId: String, displayId: Int, keyCode: Int): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectText(sessionId: String, displayId: Int, text: String): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectLongPress(
                    sessionId: String,
                    displayId: Int,
                    x: Int,
                    y: Int,
                    durationMs: Long
                ): PlatformActionResponse = PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")
            }
        )

        val result = provider.capture(
            ObservationRequest(
                sessionId = "s1",
                vdDisplayId = 7,
                sessionTaskId = 9,
                imageReader = null
            )
        )

        assertTrue(result is ObservationResult.Captured)
        val captured = result as ObservationResult.Captured
        assertEquals("AQID", captured.base64Png)
        assertEquals(12, captured.width)
        assertEquals(34, captured.height)
    }

    @Test
    fun forwardsCaptureFailureCode() {
        val provider = PlatformObservationProvider(
            gateway = object : PlatformAutomationGateway {
                override fun isAvailable(): Boolean = true

                override fun captureDisplay(sessionId: String, displayId: Int): PlatformCaptureResponse {
                    return PlatformCaptureResponse(
                        ok = false,
                        code = PlatformResultCodes.DISPLAY_NOT_FOUND,
                        details = "missing"
                    )
                }

                override fun injectTap(sessionId: String, displayId: Int, x: Int, y: Int): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectSwipe(
                    sessionId: String,
                    displayId: Int,
                    x1: Int,
                    y1: Int,
                    x2: Int,
                    y2: Int,
                    durationMs: Long
                ): PlatformActionResponse = PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectKey(sessionId: String, displayId: Int, keyCode: Int): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectText(sessionId: String, displayId: Int, text: String): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectLongPress(
                    sessionId: String,
                    displayId: Int,
                    x: Int,
                    y: Int,
                    durationMs: Long
                ): PlatformActionResponse = PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")
            }
        )

        val result = provider.capture(
            ObservationRequest(
                sessionId = "s1",
                vdDisplayId = 7,
                sessionTaskId = 9,
                imageReader = null
            )
        )

        assertTrue(result is ObservationResult.Failed)
        val failed = result as ObservationResult.Failed
        assertEquals(PlatformResultCodes.DISPLAY_NOT_FOUND, failed.code)
        assertEquals("missing", failed.message)
    }

    @Test
    fun failsWhenOkCaptureDoesNotContainPngBytes() {
        val provider = PlatformObservationProvider(
            gateway = object : PlatformAutomationGateway {
                override fun isAvailable(): Boolean = true

                override fun captureDisplay(sessionId: String, displayId: Int): PlatformCaptureResponse {
                    return PlatformCaptureResponse(
                        ok = true,
                        code = PlatformResultCodes.OK,
                        details = "ok",
                        width = 12,
                        height = 34,
                        pngBytes = null
                    )
                }

                override fun injectTap(sessionId: String, displayId: Int, x: Int, y: Int): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectSwipe(
                    sessionId: String,
                    displayId: Int,
                    x1: Int,
                    y1: Int,
                    x2: Int,
                    y2: Int,
                    durationMs: Long
                ): PlatformActionResponse = PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectKey(sessionId: String, displayId: Int, keyCode: Int): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectText(sessionId: String, displayId: Int, text: String): PlatformActionResponse =
                    PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")

                override fun injectLongPress(
                    sessionId: String,
                    displayId: Int,
                    x: Int,
                    y: Int,
                    durationMs: Long
                ): PlatformActionResponse = PlatformActionResponse(false, PlatformResultCodes.UNSUPPORTED, "unused")
            }
        )

        val result = provider.capture(
            ObservationRequest(
                sessionId = "s1",
                vdDisplayId = 7,
                sessionTaskId = 9,
                imageReader = null
            )
        )

        assertTrue(result is ObservationResult.Failed)
        val failed = result as ObservationResult.Failed
        assertEquals(PlatformResultCodes.INTERNAL_ERROR, failed.code)
        assertEquals("PNG payload missing", failed.message)
    }
}
