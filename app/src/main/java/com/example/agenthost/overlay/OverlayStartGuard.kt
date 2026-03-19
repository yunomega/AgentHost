package com.example.agenthost.overlay

import com.example.agenthost.session.OverlaySessionGateway
import com.example.agenthost.session.SessionResult
import com.example.agenthost.session.SessionState

internal class OverlayStartGuard(
    private val sessionGateway: OverlaySessionGateway,
    private val logger: (String) -> Unit = {},
    private val startTask: (String) -> SessionResult
) {
    fun start(command: String): SessionResult {
        val activeSession = sessionGateway.getActiveSession()
        if (activeSession == null) {
            return startTask(command)
        }
        if (OverlayRecoverySupport.primaryRoute(activeSession) == RecoveryPrimaryRoute.END_SESSION_TO_IDLE) {
            logger(
                OverlayAuditLog.build(
                    event = "overlay_idle_fallback_due_to_missing_task",
                    action = "start_session_guard",
                    sessionId = activeSession.sessionId,
                    activeSession = activeSession,
                    session = activeSession,
                    overlayUiState = OverlayMode.RECOVERY.name,
                    extras = "source=start_guard lastMigrationCode=${activeSession.lastMigrationCode ?: "null"}"
                )
            )
            val cleanup = sessionGateway.stopSession(migrate = false)
            val afterCleanup = sessionGateway.getActiveSession()
            if (afterCleanup == null) {
                return startTask(command)
            }
            return SessionResult(
                ok = false,
                code = "SESSION_CLEANUP_FAILED",
                data = busyData(afterCleanup) + mapOf("cleanupCode" to cleanup.code)
            )
        }
        return SessionResult(
            ok = false,
            code = "SESSION_ALREADY_ACTIVE",
            data = busyData(activeSession)
        )
    }

    private fun busyData(session: SessionState): Map<String, Any?> {
        return mapOf(
            "sessionId" to session.sessionId,
            "status" to session.status.name
        )
    }
}
