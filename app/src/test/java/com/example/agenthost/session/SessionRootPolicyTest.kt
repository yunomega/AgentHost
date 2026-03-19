package com.example.agenthost.session

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRootPolicyTest {
    @Test
    fun primaryRootUsesPassiveShellOnMainDisplay() {
        assertTrue(SessionRootPolicy.shouldUsePassivePrimaryShell(SessionRootActivity.ROOT_ROLE_PRIMARY))
        assertEquals(
            "passive_main_display",
            SessionRootPolicy.primaryInteractionMode(
                rootRole = SessionRootActivity.ROOT_ROLE_PRIMARY,
                displayMode = SessionDisplayMode.MAIN_DISPLAY.name,
                executionDisplayId = Display.DEFAULT_DISPLAY
            )
        )
    }

    @Test
    fun primaryRootUsesPassiveShellOnVirtualDisplay() {
        assertTrue(SessionRootPolicy.shouldUsePassivePrimaryShell(SessionRootActivity.ROOT_ROLE_PRIMARY))
        assertEquals(
            "passive_virtual_display",
            SessionRootPolicy.primaryInteractionMode(
                rootRole = SessionRootActivity.ROOT_ROLE_PRIMARY,
                displayMode = SessionDisplayMode.VIRTUAL_DISPLAY.name,
                executionDisplayId = 11
            )
        )
    }

    @Test
    fun mirrorRootDoesNotUsePassivePrimaryShell() {
        assertFalse(SessionRootPolicy.shouldUsePassivePrimaryShell(SessionRootActivity.ROOT_ROLE_MIRROR))
        assertEquals(
            "non_primary_root",
            SessionRootPolicy.primaryInteractionMode(
                rootRole = SessionRootActivity.ROOT_ROLE_MIRROR,
                displayMode = SessionDisplayMode.VIRTUAL_DISPLAY.name,
                executionDisplayId = 11
            )
        )
    }
}
