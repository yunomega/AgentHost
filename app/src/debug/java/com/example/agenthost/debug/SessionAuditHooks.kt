package com.example.agenthost.debug

import com.example.agenthost.config.PlannerConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    private val nextStartInjection = AtomicReference<StartInjection?>(null)
    private val nextForceMigrationFail = AtomicBoolean(false)
    private val nextMigrationFault = AtomicReference<MigrationFault?>(null)

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

    fun armMigrationFaultOnce(direction: String, code: String) {
        nextMigrationFault.set(MigrationFault(direction = direction, code = code))
    }

    fun consumeMigrationFault(direction: String): MigrationFault? {
        while (true) {
            val current = nextMigrationFault.get() ?: return null
            if (current.direction != direction) {
                return null
            }
            if (nextMigrationFault.compareAndSet(current, null)) {
                return current
            }
        }
    }

    fun clearDebugState() {
        nextStartInjection.set(null)
        nextForceMigrationFail.set(false)
        nextMigrationFault.set(null)
    }

    fun dumpDebugState(): Map<String, Any?> {
        val migrationFault = nextMigrationFault.get()
        return mapOf(
            "debugHooksEnabled" to true,
            "startInjectionArmed" to (nextStartInjection.get() != null),
            "forceMigrationFailArmed" to nextForceMigrationFail.get(),
            "migrationFaultDirection" to migrationFault?.direction,
            "migrationFaultCode" to migrationFault?.code
        )
    }
}
