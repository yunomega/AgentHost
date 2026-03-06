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
            return AgentHostRuntime.sessionCoordinator(applicationContext).startSession(command)
        }

        fun stop(migrate: Boolean): SessionResult {
            return AgentHostRuntime.sessionCoordinator(applicationContext).stopSession(migrate)
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
                    val coordinator = AgentHostRuntime.sessionCoordinator(applicationContext)
                    val result = coordinator.startSession(command)
                    Log.i(TAG, "start result code=${result.code} ok=${result.ok} data=${result.data}")
                    if (result.ok) {
                        val sessionId = result.data["sessionId"] as? String
                        val running = coordinator.markRunning(sessionId)
                        Log.i(
                            TAG,
                            "markRunning result code=${running.code} ok=${running.ok} data=${running.data}"
                        )
                    }
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
}
