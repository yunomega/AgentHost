package com.example.agenthost.overlay

import com.example.agenthost.automation.PlanningMode
import com.example.agenthost.session.SessionBootstrapTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayModelPlannerConfigTest {
    @Test
    fun createUsesModelModeAndLocalProxyDefaults() {
        val plannerConfig = OverlayModelPlannerConfig.create()
        val modelConfig = plannerConfig.modelConfig

        assertEquals(PlanningMode.MODEL, plannerConfig.mode)
        assertEquals(OverlayModelPlannerConfig.DEFAULT_PROXY_ENDPOINT, modelConfig.endpoint)
        assertEquals(OverlayModelPlannerConfig.DEFAULT_PROXY_API_KEY, modelConfig.apiKey)
        assertEquals(OverlayModelPlannerConfig.DEFAULT_TIMEOUT_MS, modelConfig.timeoutMs)
        assertEquals(OverlayModelPlannerConfig.DEFAULT_MAX_RETRIES, modelConfig.maxRetries)
        assertEquals(
            OverlayModelPlannerConfig.DEFAULT_MAX_RESPONSE_CHARS,
            modelConfig.maxResponseChars
        )
        assertTrue(modelConfig.hasEndpoint())
        assertTrue(modelConfig.hasResolvedApiKey())
    }

    @Test
    fun overlaySessionStartOptionsDefaultsToSecondaryHome() {
        val plannerConfig = OverlayModelPlannerConfig.create()

        val options = overlaySessionStartOptions(plannerConfig)

        assertEquals(SessionBootstrapTarget.SECONDARY_HOME, options.bootstrapTarget)
        assertEquals(plannerConfig, options.plannerConfigOverride)
    }

    @Test
    fun overlaySessionStartOptionsHonorsExplicitBootstrapTarget() {
        val plannerConfig = OverlayModelPlannerConfig.create()

        val secondaryHome = overlaySessionStartOptions(
            plannerConfig,
            bootstrapTarget = SessionBootstrapTarget.SECONDARY_HOME
        )
        val settings = overlaySessionStartOptions(
            plannerConfig,
            bootstrapTarget = SessionBootstrapTarget.SETTINGS
        )
        val none = overlaySessionStartOptions(
            plannerConfig,
            bootstrapTarget = SessionBootstrapTarget.NONE
        )

        assertEquals(SessionBootstrapTarget.SECONDARY_HOME, secondaryHome.bootstrapTarget)
        assertEquals(SessionBootstrapTarget.SETTINGS, settings.bootstrapTarget)
        assertEquals(SessionBootstrapTarget.NONE, none.bootstrapTarget)
    }
}
