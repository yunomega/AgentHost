package com.example.agenthost.debug

object SessionAuditHooks {
    data class StartInjection(
        val skipAttach: Boolean = false,
        val forceReportDisplayId: Int? = null
    )

    fun isEnabled(): Boolean = false

    fun configureNextStartInjection(skipAttach: Boolean, forceReportDisplayId: Int?) = Unit

    fun consumeStartInjection(): StartInjection = StartInjection()

    fun armForceMigrationFailOnce(force: Boolean) = Unit

    fun consumeForceMigrationFail(): Boolean = false
}
