package com.example.agenthost.overlay

import android.util.Log
import com.example.agenthost.session.OverlaySessionGateway
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionResult
import com.example.agenthost.session.SessionState
import java.util.concurrent.Executor

class OverlayActionDispatcher(
    private val coordinatorProvider: () -> OverlaySessionGateway,
    private val executor: Executor,
    private val onActionComplete: () -> Unit = {},
    private val onActionResult: (String, SessionResult) -> Unit = { _, _ -> },
    private val logger: (String) -> Unit = { message -> Log.i(TAG, message) },
    private val startNewTask: (String) -> SessionResult = {
        SessionResult(ok = false, code = "START_NOT_CONFIGURED")
    }
) {
    companion object {
        private const val TAG = "OverlayActionDispatcher"
    }

    fun requestMoveToMain(sessionId: String, overlayUiState: OverlayUiState) {
        dispatchWithLiveSession(
            action = "migrate_to_main",
            requestedSessionId = sessionId,
            overlayUiState = overlayUiState
        ) { gateway, liveSession ->
            if (liveSession.displayMode != SessionDisplayMode.VIRTUAL_DISPLAY || liveSession.migratedToMain) {
                SessionResult(ok = true, code = "SESSION_ALREADY_ON_MAIN")
            } else {
                gateway.migrateTaskToMain(liveSession.sessionId)
            }
        }
    }

    fun requestRecoveryPrimary(sessionId: String, overlayUiState: OverlayUiState) {
        dispatchWithLiveSession(
            action = "recovery_primary",
            requestedSessionId = sessionId,
            overlayUiState = overlayUiState
        ) { gateway, liveSession ->
            val recoveryRoute = OverlayRecoverySupport.derivePrimaryRoute(liveSession)
            when (recoveryRoute) {
                RecoveryPrimaryRoute.NONE -> SessionResult(ok = true, code = "NO_ACTION")

                RecoveryPrimaryRoute.SURFACE_ON_MAIN -> {
                    gateway.showSessionOnMain(liveSession.sessionId)
                }

                RecoveryPrimaryRoute.MIGRATE_TO_MAIN -> {
                    gateway.migrateTaskToMain(liveSession.sessionId)
                }

                RecoveryPrimaryRoute.MIGRATE_TO_EXECUTION -> {
                    gateway.migrateTaskToExecution(liveSession.sessionId)
                }

                RecoveryPrimaryRoute.END_SESSION_TO_IDLE -> {
                    logger(
                        OverlayAuditLog.build(
                            event = "overlay_idle_fallback_due_to_missing_task",
                            action = "recovery_primary",
                            sessionId = liveSession.sessionId,
                            activeSession = liveSession,
                            session = liveSession,
                            overlayUiState = overlayUiState.mode.name,
                            extras = "lastMigrationCode=${liveSession.lastMigrationCode ?: "null"}"
                        )
                    )
                    gateway.stopSession(migrate = false)
                }
            }
        }
    }

    fun submitReply(sessionId: String, replyText: String, overlayUiState: OverlayUiState) {
        dispatchWithLiveSession(
            action = "reply_to_session",
            requestedSessionId = sessionId,
            overlayUiState = overlayUiState
        ) { gateway, liveSession ->
            gateway.replyToSession(liveSession.sessionId, replyText)
        }
    }

    fun requestReturnToMain(sessionId: String, overlayUiState: OverlayUiState) {
        dispatchWithLiveSession(
            action = "return_to_main",
            requestedSessionId = sessionId,
            overlayUiState = overlayUiState
        ) { gateway, _ ->
            gateway.stopSession(migrate = true)
        }
    }

    fun submitNewTask(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            dispatchImmediate(
                action = "start_session",
                result = SessionResult(ok = false, code = "COMMAND_EMPTY")
            )
            return
        }
        dispatch("start_session") {
            startNewTask(trimmed)
        }
    }

    fun cancelSession(sessionId: String?, overlayUiState: OverlayUiState) {
        dispatchWithLiveSession(
            action = "stop_session",
            requestedSessionId = sessionId,
            overlayUiState = overlayUiState
        ) { gateway, _ ->
            gateway.stopSession(migrate = false)
        }
    }

    private fun dispatchImmediate(action: String, result: SessionResult) {
        logger("action=$action ok=${result.ok} code=${result.code} data=${result.data}")
        onActionResult(action, result)
        onActionComplete()
    }

    private fun dispatch(action: String, block: () -> SessionResult) {
        executor.execute {
            val result = block()
            logger("action=$action ok=${result.ok} code=${result.code} data=${result.data}")
            onActionResult(action, result)
            onActionComplete()
        }
    }

    private fun dispatchWithLiveSession(
        action: String,
        requestedSessionId: String?,
        overlayUiState: OverlayUiState,
        block: (OverlaySessionGateway, SessionState) -> SessionResult
    ) {
        executor.execute {
            val gateway = coordinatorProvider()
            val activeSession = gateway.getActiveSession()
            val liveSession = resolveLiveSession(activeSession, requestedSessionId)
            logger(
                OverlayAuditLog.build(
                    event = "overlay_action_click",
                    action = action,
                    sessionId = requestedSessionId,
                    activeSession = activeSession,
                    session = liveSession ?: activeSession,
                    overlayUiState = overlayUiState.mode.name,
                    extras = "requestedSessionId=${requestedSessionId ?: "null"} matched=${liveSession != null}"
                )
            )
            val result = if (liveSession == null) {
                staleResult(requestedSessionId, activeSession)
            } else {
                block(gateway, liveSession)
            }
            logger("action=$action ok=${result.ok} code=${result.code} data=${result.data}")
            onActionResult(action, result)
            onActionComplete()
        }
    }

    private fun resolveLiveSession(
        activeSession: SessionState?,
        requestedSessionId: String?
    ): SessionState? {
        if (requestedSessionId.isNullOrBlank()) {
            return activeSession
        }
        return activeSession?.takeIf { it.sessionId == requestedSessionId }
    }

    private fun staleResult(
        requestedSessionId: String?,
        activeSession: SessionState?
    ): SessionResult {
        return SessionResult(
            ok = false,
            code = "SESSION_STALE",
            data = buildMap {
                put("sessionId", requestedSessionId)
                put("activeSessionId", activeSession?.sessionId)
                put("status", activeSession?.status?.name)
            }
        )
    }
}
