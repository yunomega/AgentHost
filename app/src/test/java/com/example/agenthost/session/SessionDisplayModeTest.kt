package com.example.agenthost.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDisplayModeTest {
    @Test
    fun autoDefaultsToVirtualDisplay() {
        val options = SessionStartOptions(displayMode = SessionDisplayMode.AUTO)

        assertEquals(SessionDisplayMode.VIRTUAL_DISPLAY, options.resolvedDisplayMode())
    }

    @Test
    fun debugForceMainDisplayOverridesRequestedMode() {
        val options = SessionStartOptions(
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            debugForceMainDisplay = true
        )

        assertEquals(SessionDisplayMode.MAIN_DISPLAY, options.resolvedDisplayMode())
    }

    @Test
    fun sessionStateMainDisplayDetectionUsesExecutionDisplay() {
        val main = SessionState(
            sessionId = "s-main",
            command = "demo",
            executionDisplayId = 0,
            virtualDisplayId = null,
            surfaceDisplayId = 0,
            sessionTaskId = 1,
            status = SessionStatus.READY,
            lastError = null,
            waitingQuestion = null,
            automationStep = 0,
            displayMode = SessionDisplayMode.MAIN_DISPLAY,
            visibleToUser = true,
            backgroundPreferred = false,
            returnToVirtualAfterUser = false,
            pendingReturnToVirtualDisplay = false,
            bootstrapTarget = SessionBootstrapTarget.NONE,
            lastBootstrapCode = null
        )
        val virtual = main.copy(
            sessionId = "s-virtual",
            executionDisplayId = 11,
            virtualDisplayId = 11,
            surfaceDisplayId = null,
            displayMode = SessionDisplayMode.VIRTUAL_DISPLAY,
            visibleToUser = false,
            backgroundPreferred = true,
            returnToVirtualAfterUser = true
        )

        assertTrue(main.isOnMainDisplay())
        assertFalse(virtual.isOnMainDisplay())
    }
}
