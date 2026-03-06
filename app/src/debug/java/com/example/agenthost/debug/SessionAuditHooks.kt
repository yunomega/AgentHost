package com.example.agenthost.debug

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object SessionAuditHooks {
    data class StartInjection(
        val skipAttach: Boolean = false,
        val forceReportDisplayId: Int? = null
    )

    private val nextStartInjection = AtomicReference<StartInjection?>(null)
    private val nextForceMigrationFail = AtomicBoolean(false)

    fun isEnabled(): Boolean = true

    fun configureNextStartInjection(skipAttach: Boolean, forceReportDisplayId: Int?) {
        nextStartInjection.set(
            StartInjection(
                skipAttach = skipAttach,
                forceReportDisplayId = forceReportDisplayId
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
