package com.example.agenthost.overlay

import android.util.Log
import com.example.agenthost.session.SessionCoordinator
import com.example.agenthost.session.SessionDisplayMode
import com.example.agenthost.session.SessionResult
import com.example.agenthost.session.SessionState
import java.util.concurrent.Executor

class OverlayActionDispatcher(
    private val coordinatorProvider: () -> SessionCoordinator,
    private val executor: Executor,
    private val onActionComplete: () -> Unit = {}
) {
    companion object {
        private const val TAG = "OverlayActionDispatcher"
    }

    fun requestMoveToMain(session: SessionState) {
        if (session.displayMode != SessionDisplayMode.VIRTUAL_DISPLAY || session.migratedToMain) {
            onActionComplete()
            return
        }
        dispatch("migrate_to_main") {
            coordinatorProvider().migrateTaskToMain(session.sessionId)
        }
    }

    fun requestRecoveryPrimary(session: SessionState) {
        if (session.displayMode != SessionDisplayMode.VIRTUAL_DISPLAY) {
            onActionComplete()
            return
        }
        if (session.migratedToMain) {
            dispatch("migrate_to_execution") {
                coordinatorProvider().migrateTaskToExecution(session.sessionId)
            }
        } else {
            dispatch("migrate_to_main") {
                coordinatorProvider().migrateTaskToMain(session.sessionId)
            }
        }
    }

    fun submitReply(sessionId: String, replyText: String) {
        dispatch("reply_to_session") {
            coordinatorProvider().replyToSession(sessionId, replyText)
        }
    }

    fun cancelSession() {
        dispatch("stop_session") {
            coordinatorProvider().stopSession(migrate = false)
        }
    }

    private fun dispatch(action: String, block: () -> SessionResult) {
        executor.execute {
            val result = block()
            Log.i(
                TAG,
                "action=$action ok=${result.ok} code=${result.code} data=${result.data}"
            )
            onActionComplete()
        }
    }
}
