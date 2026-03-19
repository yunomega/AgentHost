package com.example.agenthost.overlay

import android.view.Display
import com.example.agenthost.migration.TaskMigrationPolicy
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionState

/**
 * Controller-local recovery action route.
 *
 * This is a derived dispatch hint only and must not be treated as business
 * state. `SURFACE_ON_MAIN` is retained as a reserved compatibility value.
 */
internal enum class RecoveryPrimaryRoute {
    NONE,
    SURFACE_ON_MAIN,
    MIGRATE_TO_MAIN,
    MIGRATE_TO_EXECUTION,
    END_SESSION_TO_IDLE
}

internal object OverlayRecoverySupport {
    // Recovery routing is UI/controller-local derived state. It may read canonical
    // session fields and migration result codes only, never retained UI state or
    // report diagnostics.
    fun isAlreadyOnMain(session: SessionState): Boolean {
        return session.displayMode == SessionDisplayMode.MAIN_DISPLAY ||
            session.migratedToMain ||
            (session.visibleToUser && session.surfaceDisplayId == Display.DEFAULT_DISPLAY)
    }

    fun shouldFallbackToIdle(session: SessionState): Boolean {
        return TaskMigrationPolicy.shouldFallbackToIdle(
            session.lastMigrationConflictCode ?: session.lastMigrationCode
        )
    }

    fun derivePrimaryRoute(session: SessionState): RecoveryPrimaryRoute {
        if (session.displayMode != SessionDisplayMode.VIRTUAL_DISPLAY) {
            return RecoveryPrimaryRoute.NONE
        }
        if (shouldFallbackToIdle(session)) {
            return RecoveryPrimaryRoute.END_SESSION_TO_IDLE
        }
        if (session.migratedToMain) {
            return RecoveryPrimaryRoute.MIGRATE_TO_EXECUTION
        }
        if (isAlreadyOnMain(session)) {
            return RecoveryPrimaryRoute.NONE
        }
        return RecoveryPrimaryRoute.MIGRATE_TO_MAIN
    }
}
