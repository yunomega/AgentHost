package com.example.agenthost.overlay

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Binder
import android.os.IBinder
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.agenthost.core.AgentHostRuntime
import com.example.agenthost.debug.TaskRunReportHooks
import com.example.agenthost.session.SessionCoordinator
import com.example.agenthost.session.SessionBootstrapTarget
import com.example.agenthost.session.SessionResult
import com.example.agenthost.session.SessionStateListener
import com.example.agenthost.session.SessionStateListenerSnapshot
import com.example.agenthost.session.SessionStartOptions
import java.util.concurrent.Executors

class OverlayEntryService : Service() {
    companion object {
        private const val TAG = "OverlayEntryService"
        private const val POLL_INTERVAL_MS = 500L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()
    private lateinit var overlayController: OverlayController
    private lateinit var actionDispatcher: OverlayActionDispatcher
    private lateinit var presencePrefs: OverlayPresencePrefs
    private val overlayStateMapper = OverlayStateMapper()
    private var foregroundStarted = false
    // Diff cache only. This snapshot exists to suppress redundant renders and is
    // not a canonical session snapshot or a source of business semantics.
    private var lastRenderedSessionSnapshot: SessionStateListenerSnapshot? = null
    private val sessionStateListener = SessionStateListener { session ->
        mainHandler.post { renderOverlayIfChanged(session) }
    }
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshOverlay()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    inner class LocalBinder : Binder() {
        fun start(command: String): SessionResult {
            return startNewTaskIfIdle(command).also { requestOverlayRefresh() }
        }

        fun stop(migrate: Boolean): SessionResult {
            return sessionCoordinator().stopSession(migrate).also { requestOverlayRefresh() }
        }

        fun reply(sessionId: String, userText: String): SessionResult {
            return sessionCoordinator()
                .replyToSession(sessionId, userText)
                .also { requestOverlayRefresh() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        presencePrefs = OverlayPresencePrefs(applicationContext)
        ensureForegroundStarted()
        actionDispatcher = OverlayActionDispatcher(
            coordinatorProvider = ::sessionCoordinator,
            executor = worker,
            onActionComplete = ::requestOverlayRefresh,
            startNewTask = ::startNewTaskIfIdle
        )
        overlayController = OverlayController(
            context = applicationContext,
            stateMapper = overlayStateMapper,
            actionDispatcher = actionDispatcher
        )
        sessionCoordinator().addSessionStateListener(sessionStateListener)
        overlayController.show()
        mainHandler.post(pollRunnable)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundStarted()
        val startReason = intent?.getStringExtra(OverlayActions.EXTRA_START_REASON)
            ?: if (intent == null) {
                OverlayActions.START_REASON_STICKY_RESTART
            } else {
                OverlayActions.START_REASON_UNSPECIFIED
            }
        overlayController.show()
        when (intent?.action) {
            null,
            OverlayActions.ACTION_ENSURE_OVERLAY -> {
                presencePrefs.recordStart(startReason)
                Log.i(TAG, "presence ensure reason=$startReason")
                requestOverlayRefresh()
            }

            OverlayActions.ACTION_START_SESSION -> {
                val command = intent.getStringExtra(OverlayActions.EXTRA_COMMAND).orEmpty()
                worker.execute {
                    val result = startNewTaskIfIdle(command)
                    Log.i(TAG, "start result code=${result.code} ok=${result.ok} data=${result.data}")
                    requestOverlayRefresh()
                }
            }

            OverlayActions.ACTION_STOP_SESSION -> {
                val migrate = intent.getBooleanExtra(OverlayActions.EXTRA_MIGRATE, true)
                worker.execute {
                    val result = sessionCoordinator().stopSession(migrate)
                    Log.i(TAG, "stop result code=${result.code} ok=${result.ok} data=${result.data}")
                    requestOverlayRefresh()
                }
            }

            OverlayActions.ACTION_REPLY_SESSION -> {
                val sessionId = intent.getStringExtra(OverlayActions.EXTRA_SESSION_ID).orEmpty()
                val userText = intent.getStringExtra(OverlayActions.EXTRA_USER_TEXT).orEmpty()
                worker.execute {
                    val result = sessionCoordinator().replyToSession(sessionId, userText)
                    Log.i(TAG, "reply result code=${result.code} ok=${result.ok} data=${result.data}")
                    requestOverlayRefresh()
                }
            }

            else -> {
                Log.w(TAG, "onStartCommand unknown action=${intent?.action}")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        sessionCoordinator().removeSessionStateListener(sessionStateListener)
        overlayController.destroy()
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foregroundStarted = false
        }
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) {
            return
        }
        try {
            OverlayNotificationHelper.ensureChannel(applicationContext)
            val notification = OverlayNotificationHelper.buildNotification(applicationContext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    OverlayNotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                startForeground(OverlayNotificationHelper.NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            Log.i(TAG, "foreground_started")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start foreground service", t)
            throw t
        }
    }

    private fun startWithAutomation(command: String): SessionResult {
        val coordinator = sessionCoordinator()
        val plannerConfig = OverlayModelPlannerConfig.create()
        val modelConfig = plannerConfig.modelConfig
        Log.i(
            TAG,
            "overlay_start_request source=overlay plannerMode=${plannerConfig.mode.name} " +
                "endpointConfigured=${modelConfig.hasEndpoint()} apiKeyConfigured=${modelConfig.hasResolvedApiKey()} " +
                "endpoint=${modelConfig.endpoint ?: "null"}"
        )
        TaskRunReportHooks.prepareNextSessionSource("OVERLAY")
        val startResult = coordinator.startSession(
            command,
            SessionStartOptions(
                bootstrapTarget = SessionBootstrapTarget.SETTINGS,
                plannerConfigOverride = plannerConfig
            )
        )
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

    private fun startNewTaskIfIdle(command: String): SessionResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return SessionResult(ok = false, code = "COMMAND_EMPTY")
        }
        val result = OverlayStartGuard(
            sessionGateway = sessionCoordinator(),
            logger = { message -> Log.i(TAG, message) },
            startTask = ::startWithAutomation,
            overlayModeLabel = overlayStateMapper::modeName
        ).start(trimmed)
        if (!result.ok && result.code == "SESSION_ALREADY_ACTIVE") {
            Log.i(
                TAG,
                "guarded_start_blocked code=${result.code} activeSessionId=${result.data["sessionId"]} " +
                    "status=${result.data["status"]}"
            )
        }
        return result
    }

    private fun requestOverlayRefresh() {
        mainHandler.post { refreshOverlay() }
    }

    private fun refreshOverlay() {
        renderOverlayIfChanged(sessionCoordinator().getActiveSession())
    }

    private fun renderOverlayIfChanged(session: com.example.agenthost.session.SessionState?) {
        val snapshot = SessionStateListenerSnapshot.from(session)
        if (snapshot == lastRenderedSessionSnapshot) {
            return
        }
        lastRenderedSessionSnapshot = snapshot
        overlayController.render(session)
    }

    private fun sessionCoordinator(): SessionCoordinator {
        return AgentHostRuntime.sessionCoordinator(applicationContext)
    }
}
