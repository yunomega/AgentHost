package com.example.agenthost.debug

import com.example.agenthost.config.PlannerConfig

object SessionAuditHooks {
    data class MigrationFault(
        val direction: String,
        val code: String
    )

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

    fun armMigrationFaultOnce(direction: String, code: String) = Unit

    fun consumeMigrationFault(direction: String): MigrationFault? = null

    fun clearDebugState() = Unit

    fun dumpDebugState(): Map<String, Any?> {
        return mapOf(
            "debugHooksEnabled" to false,
            "startInjectionArmed" to false,
            "forceMigrationFailArmed" to false,
            "migrationFaultDirection" to null,
            "migrationFaultCode" to null
        )
    }
}
