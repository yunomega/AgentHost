package com.example.agenthost.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubgoalStateMachineTest {
    private val machine = SubgoalStateMachine()

    @Test
    fun extractsParentAndTargetHintsFromSequentialChineseCommand() {
        val spec = machine.extractSpec("打开设置里的应用选项，再点击语燕输入法")

        assertNotNull(spec)
        assertEquals("打开设置里的应用选项", spec?.parentHint)
        assertEquals("语燕输入法", spec?.targetHint)
    }

    @Test
    fun clickProgressAdvancesFromParentToTargetPhase() {
        val initial = machine.initialState("打开设置里的应用选项，再点击语燕输入法")

        val advanced = machine.advancePhase(
            state = initial,
            action = CanonicalAction(
                type = CanonicalActionType.CLICK,
                coordSpace = CanonicalCoordSpace.IMAGE_PIXEL,
                point = CanonicalPoint(100, 100)
            ),
            observationChanged = true
        )

        assertEquals(SubgoalPhase.NEED_CLICK_TARGET, advanced.phase)
    }

    @Test
    fun doneOnlyPhaseRestrictsActionsToDoneOrAskUser() {
        val capabilityProfile = CapabilityProfile.phase3PlatformReady(true)

        val allowed = machine.allowedActions(SubgoalPhase.DONE_ONLY, capabilityProfile)
        val blocked = machine.blockedActions(SubgoalPhase.DONE_ONLY, capabilityProfile)

        assertEquals(listOf("ASK_USER", "DONE"), allowed)
        assertTrue(blocked.contains("CLICK"))
        assertTrue(blocked.contains("SWIPE"))
        assertTrue(blocked.contains("BACK"))
    }
}
