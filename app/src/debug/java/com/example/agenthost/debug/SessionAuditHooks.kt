package com.example.agenthost.debug

import com.example.agenthost.config.PlannerConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object SessionAuditHooks {
    data class StartInjection(
        val skipAttach: Boolean = false,
        val forceReportDisplayId: Int? = null,
        val plannerConfigOverride: PlannerConfig? = null
    )

    private val nextStartInjection = AtomicReference<StartInjection?>(null)
    private val nextForceMigrationFail = AtomicBoolean(false)

    fun isEnabled(): Boolean = true

    fun configureNextStartInjection(
        skipAttach: Boolean,
        forceReportDisplayId: Int?,
        plannerConfigOverride: PlannerConfig?
    ) {
        nextStartInjection.set(
            StartInjection(
                skipAttach = skipAttach,
                forceReportDisplayId = forceReportDisplayId,
                plannerConfigOverride = plannerConfigOverride
            )
        )
    }

    fun consumeStartInjection(): StartInjection {
        return nextStartInjection.getAndSet(null) ?: StartInjection()
    }

    fun armForceMigrationFailOnce(force: Boolean) {
        nextForceMigrationFail.set(force)
    }

    fun consumeForceMigrationFail(): Boolean {
        return nextForceMigrationFail.getAndSet(false)
    }
}
