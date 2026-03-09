package com.example.agenthost.session

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.agenthost.R
import com.example.agenthost.debug.SessionAuditHooks

class SessionRootActivity : Activity() {
    companion object {
        private const val TAG = "SessionRootActivity"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_EXECUTION_DISPLAY_ID = "extra_execution_display_id"
        const val EXTRA_VIRTUAL_DISPLAY_ID = "extra_virtual_display_id"
        const val EXTRA_SURFACE_DISPLAY_ID = "extra_surface_display_id"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_ROOT_ROLE = "extra_root_role"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_WAITING_QUESTION = "extra_waiting_question"
        const val EXTRA_DISPLAY_MODE = "extra_display_mode"
        const val EXTRA_DEBUG_SKIP_ATTACH = "debug_skip_attach"
        const val EXTRA_DEBUG_FORCE_REPORT_DISPLAY_ID = "debug_force_report_display_id"

        const val ROOT_ROLE_PRIMARY = "primary_execution"
        const val ROOT_ROLE_MIRROR = "mirror_surface"
    }

    private var sessionId: String = ""
    private var rootRole: String = ROOT_ROLE_PRIMARY

    override fun onCreate(savedInstanceState: Bundle?) {
        rootRole = intent?.getStringExtra(EXTRA_ROOT_ROLE).orEmpty().ifBlank { ROOT_ROLE_PRIMARY }
        val requestedDisplayMode = intent?.getStringExtra(EXTRA_DISPLAY_MODE).orEmpty()
        val requestedExecutionDisplayId = intent?.getIntExtra(EXTRA_EXECUTION_DISPLAY_ID, -1) ?: -1
        if (shouldUsePassivePrimaryShell(rootRole, requestedDisplayMode, requestedExecutionDisplayId)) {
            setTheme(R.style.Theme_AgentHost_SessionRoot_Transparent)
        }
        super.onCreate(savedInstanceState)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        val executionDisplayId = intent.getIntExtra(EXTRA_EXECUTION_DISPLAY_ID, -1)
        val virtualDisplayId = if (intent.hasExtra(EXTRA_VIRTUAL_DISPLAY_ID)) {
            intent.getIntExtra(EXTRA_VIRTUAL_DISPLAY_ID, -1)
        } else {
            -1
        }
        val surfaceDisplayId = if (intent.hasExtra(EXTRA_SURFACE_DISPLAY_ID)) {
            intent.getIntExtra(EXTRA_SURFACE_DISPLAY_ID, -1)
        } else {
            -1
        }
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val status = intent.getStringExtra(EXTRA_STATUS).orEmpty()
        val waitingQuestion = intent.getStringExtra(EXTRA_WAITING_QUESTION).orEmpty()
        val displayMode = intent.getStringExtra(EXTRA_DISPLAY_MODE).orEmpty()
        val skipAttach = SessionAuditHooks.isEnabled() &&
            intent.getBooleanExtra(EXTRA_DEBUG_SKIP_ATTACH, false)
        val forcedReportDisplayId = if (
            SessionAuditHooks.isEnabled() &&
            intent.hasExtra(EXTRA_DEBUG_FORCE_REPORT_DISPLAY_ID)
        ) {
            intent.getIntExtra(EXTRA_DEBUG_FORCE_REPORT_DISPLAY_ID, -1)
        } else {
            null
        }
        if (sessionId.isBlank()) {
            Log.e(TAG, "missing sessionId, finish")
            finish()
            return
        }
        val currentDisplayId = display?.displayId ?: -1
        val currentTaskId = taskId
        val component = componentName.flattenToShortString()
        val reportedDisplayId = forcedReportDisplayId ?: currentDisplayId

        val passivePrimaryShell = shouldUsePassivePrimaryShell(
            rootRole = rootRole,
            displayMode = displayMode,
            executionDisplayId = executionDisplayId
        )

        if (rootRole == ROOT_ROLE_PRIMARY) {
            if (passivePrimaryShell) {
                configurePassivePrimaryExecutionWindow()
            } else {
                configureInteractivePrimaryExecutionWindow()
            }
            if (skipAttach) {
                Log.i(
                    TAG,
                    "event=session_root_attach sessionId=$sessionId executionDisplayId=$executionDisplayId " +
                        "virtualDisplayId=$virtualDisplayId surfaceDisplayId=$surfaceDisplayId " +
                        "sessionTaskId=$currentTaskId code=ATTACH_SKIPPED " +
                        "extras=currentDisplayId=$currentDisplayId reportedDisplayId=$reportedDisplayId " +
                        "role=$rootRole"
                )
            } else {
                val accepted = SessionBridge.reportAttached(
                    SessionAttachAck(
                        sessionId = sessionId,
                        taskId = currentTaskId,
                        displayId = reportedDisplayId,
                        component = component
                    )
                )
                val attachCode = if (accepted) "ATTACH_REPORTED" else "ATTACH_IGNORED_NO_WAITER"
                Log.i(
                    TAG,
                    "event=session_root_attach sessionId=$sessionId executionDisplayId=$executionDisplayId " +
                        "virtualDisplayId=$virtualDisplayId surfaceDisplayId=$surfaceDisplayId " +
                        "sessionTaskId=$currentTaskId code=$attachCode " +
                        "extras=currentDisplayId=$currentDisplayId reportedDisplayId=$reportedDisplayId " +
                        "component=$component command=$command role=$rootRole"
                )
            }
        } else {
            SessionSurfaceRegistry.register(sessionId, this)
            Log.i(
                TAG,
                "event=session_surface_on_main sessionId=$sessionId executionDisplayId=$executionDisplayId " +
                    "virtualDisplayId=$virtualDisplayId surfaceDisplayId=$surfaceDisplayId " +
                    "sessionTaskId=$currentTaskId code=SURFACE_VISIBLE " +
                    "extras=currentDisplayId=$currentDisplayId component=$component status=$status role=$rootRole"
            )
        }

        if (rootRole == ROOT_ROLE_PRIMARY) {
            if (passivePrimaryShell) {
                setContentView(buildPrimaryExecutionShell())
            } else {
                setContentView(
                    buildContentView(
                        sessionId = sessionId,
                        command = command,
                        status = status,
                        waitingQuestion = waitingQuestion,
                        displayMode = displayMode,
                        currentDisplayId = currentDisplayId,
                        currentTaskId = currentTaskId,
                        executionDisplayId = executionDisplayId,
                        virtualDisplayId = virtualDisplayId,
                        surfaceDisplayId = surfaceDisplayId
                    )
                )
            }
            Log.i(
                TAG,
                "event=session_root_shell_ready sessionId=$sessionId executionDisplayId=$executionDisplayId " +
                    "virtualDisplayId=$virtualDisplayId surfaceDisplayId=$surfaceDisplayId " +
                    "sessionTaskId=$currentTaskId code=PRIMARY_SHELL_READY " +
                    "extras=currentDisplayId=$currentDisplayId role=$rootRole " +
                    "interactionMode=${if (passivePrimaryShell) "passive_main_display" else "interactive_execution"}"
            )
        } else {
            setContentView(
                buildContentView(
                    sessionId = sessionId,
                    command = command,
                    status = status,
                    waitingQuestion = waitingQuestion,
                    displayMode = displayMode,
                    currentDisplayId = currentDisplayId,
                    currentTaskId = currentTaskId,
                    executionDisplayId = executionDisplayId,
                    virtualDisplayId = virtualDisplayId,
                    surfaceDisplayId = surfaceDisplayId
                )
            )
        }
    }

    override fun onDestroy() {
        if (rootRole == ROOT_ROLE_MIRROR && sessionId.isNotBlank()) {
            SessionSurfaceRegistry.unregister(sessionId, this)
            Log.i(
                TAG,
                "event=session_surface_on_main sessionId=$sessionId executionDisplayId=-1 " +
                    "virtualDisplayId=-1 surfaceDisplayId=-1 sessionTaskId=$taskId " +
                    "code=SURFACE_DISMISSED extras=role=$rootRole"
            )
        }
        super.onDestroy()
    }

    private fun buildContentView(
        sessionId: String,
        command: String,
        status: String,
        waitingQuestion: String,
        displayMode: String,
        currentDisplayId: Int,
        currentTaskId: Int,
        executionDisplayId: Int,
        virtualDisplayId: Int,
        surfaceDisplayId: Int
    ): FrameLayout {
        val frame = FrameLayout(this)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = if (rootRole == ROOT_ROLE_PRIMARY) {
                "Session Execution Root"
            } else {
                "Session Mirror Surface"
            }
            textSize = 20f
            gravity = Gravity.START
        }
        val body = TextView(this).apply {
            text = buildString {
                append("sessionId=").append(sessionId).append('\n')
                append("role=").append(rootRole).append('\n')
                append("status=").append(status.ifBlank { "UNKNOWN" }).append('\n')
                append("displayMode=").append(displayMode.ifBlank { "UNKNOWN" }).append('\n')
                append("currentDisplayId=").append(currentDisplayId).append('\n')
                append("executionDisplayId=").append(executionDisplayId).append('\n')
                append("virtualDisplayId=")
                append(if (virtualDisplayId >= 0) virtualDisplayId else "null")
                append('\n')
                append("surfaceDisplayId=")
                append(if (surfaceDisplayId >= 0) surfaceDisplayId else "null")
                append('\n')
                append("taskId=").append(currentTaskId).append('\n')
                append("command=").append(command).append('\n')
                if (waitingQuestion.isNotBlank()) {
                    append("waitingQuestion=").append(waitingQuestion).append('\n')
                }
                if (rootRole == ROOT_ROLE_MIRROR) {
                    append("\nReply via debug broadcast or existing overlay entry path.")
                }
            }
        }
        column.addView(title)
        column.addView(body)
        scroll.addView(column)
        frame.addView(scroll)
        return frame
    }

    private fun buildPrimaryExecutionShell(): View {
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configurePassivePrimaryExecutionWindow() {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
    }

    private fun configureInteractivePrimaryExecutionWindow() {
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
    }

    private fun shouldUsePassivePrimaryShell(
        rootRole: String,
        displayMode: String,
        executionDisplayId: Int
    ): Boolean {
        if (rootRole != ROOT_ROLE_PRIMARY) {
            return false
        }
        return displayMode == "MAIN_DISPLAY" || executionDisplayId == Display.DEFAULT_DISPLAY
    }
}
