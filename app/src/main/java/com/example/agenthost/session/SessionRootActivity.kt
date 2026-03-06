package com.example.agenthost.session

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import com.example.agenthost.debug.SessionAuditHooks

class SessionRootActivity : Activity() {
    companion object {
        private const val TAG = "SessionRootActivity"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_VD_DISPLAY_ID = "extra_vd_display_id"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_DEBUG_SKIP_ATTACH = "debug_skip_attach"
        const val EXTRA_DEBUG_FORCE_REPORT_DISPLAY_ID = "debug_force_report_display_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        val expectedDisplayId = intent.getIntExtra(EXTRA_VD_DISPLAY_ID, -1)
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
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

        if (skipAttach) {
            Log.i(
                TAG,
                "event=session_root_attach sessionId=$sessionId vdDisplayId=$expectedDisplayId " +
                    "sessionTaskId=$currentTaskId code=ATTACH_SKIPPED " +
                    "extras=currentDisplayId=$currentDisplayId reportedDisplayId=$reportedDisplayId"
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
                "event=session_root_attach sessionId=$sessionId vdDisplayId=$expectedDisplayId " +
                    "sessionTaskId=$currentTaskId code=$attachCode " +
                    "extras=currentDisplayId=$currentDisplayId reportedDisplayId=$reportedDisplayId " +
                    "component=$component command=$command"
            )
        }

        val container = FrameLayout(this)
        val text = TextView(this).apply {
            text = "Session placeholder\nsessionId=$sessionId\ndisplayId=$currentDisplayId\ntaskId=$currentTaskId\ncommand=$command"
        }
        container.addView(text)
        setContentView(container)
    }
}
