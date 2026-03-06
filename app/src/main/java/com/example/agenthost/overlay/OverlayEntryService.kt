package com.example.agenthost.overlay

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.example.agenthost.core.AgentHostRuntime
import com.example.agenthost.session.SessionResult
import java.util.concurrent.Executors

class OverlayEntryService : Service() {
    companion object {
        private const val TAG = "OverlayEntryService"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun start(command: String): SessionResult {
            return startWithAutomation(command)
        }

        fun stop(migrate: Boolean): SessionResult {
            return AgentHostRuntime.sessionCoordinator(applicationContext).stopSession(migrate)
        }

        fun reply(sessionId: String, userText: String): SessionResult {
            return AgentHostRuntime.sessionCoordinator(applicationContext)
                .replyToSession(sessionId, userText)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OverlayActions.ACTION_START_SESSION -> {
                val command = intent.getStringExtra(OverlayActions.EXTRA_COMMAND).orEmpty()
                worker.execute {
                    val result = startWithAutomation(command)
                    Log.i(TAG, "start result code=${result.code} ok=${result.ok} data=${result.data}")
                }
            }

            OverlayActions.ACTION_STOP_SESSION -> {
                val migrate = intent.getBooleanExtra(OverlayActions.EXTRA_MIGRATE, true)
                worker.execute {
                    val result = AgentHostRuntime.sessionCoordinator(applicationContext)
                        .stopSession(migrate)
                    Log.i(TAG, "stop result code=${result.code} ok=${result.ok} data=${result.data}")
                }
            }

            OverlayActions.ACTION_REPLY_SESSION -> {
                val sessionId = intent.getStringExtra(OverlayActions.EXTRA_SESSION_ID).orEmpty()
                val userText = intent.getStringExtra(OverlayActions.EXTRA_USER_TEXT).orEmpty()
                worker.execute {
                    val result = AgentHostRuntime.sessionCoordinator(applicationContext)
                        .replyToSession(sessionId, userText)
                    Log.i(TAG, "reply result code=${result.code} ok=${result.ok} data=${result.data}")
                }
            }

            else -> {
                Log.w(TAG, "onStartCommand unknown action=${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun startWithAutomation(command: String): SessionResult {
        val coordinator = AgentHostRuntime.sessionCoordinator(applicationContext)
        val startResult = coordinator.startSession(command)
        if (!startResult.ok) {
            return startResult
        }
        val sessionId = startResult.data["sessionId"] as? String
            ?: return SessionResult(
                ok = false,
                code = "SESSION_ID_MISSING",
                data = startResult.data
            )
        return coordinator.startAutomation(sessionId)
    }
}
