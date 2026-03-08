package com.example.agenthost.debug

import com.example.agenthost.config.PlannerConfig

object SessionAuditHooks {
    data class StartInjection(
        val skipAttach: Boolean = false,
        val forceReportDisplayId: Int? = null,
        val plannerConfigOverride: PlannerConfig? = null
    )

    fun isEnabled(): Boolean = false

    fun configureNextStartInjection(
        skipAttach: Boolean,
        forceReportDisplayId: Int?,
        plannerConfigOverride: PlannerConfig?
    ) = Unit

    fun consumeStartInjection(): StartInjection = StartInjection()

    fun armForceMigrationFailOnce(force: Boolean) = Unit

    fun consumeForceMigrationFail(): Boolean = false
}
