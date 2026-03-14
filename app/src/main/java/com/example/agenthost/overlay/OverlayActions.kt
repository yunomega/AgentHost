package com.example.agenthost.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object OverlayActions {
    const val ACTION_ENSURE_OVERLAY = "com.example.agenthost.action.ENSURE_OVERLAY"
    const val ACTION_START_SESSION = "com.example.agenthost.action.START_SESSION"
    const val ACTION_STOP_SESSION = "com.example.agenthost.action.STOP_SESSION"
    const val ACTION_REPLY_SESSION = "com.example.agenthost.action.REPLY_SESSION"
    const val EXTRA_START_REASON = "extra_start_reason"
    const val EXTRA_COMMAND = "extra_command"
    const val EXTRA_MIGRATE = "extra_migrate"
    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_USER_TEXT = "extra_user_text"
    const val START_REASON_UNSPECIFIED = "unspecified"
    const val START_REASON_BOOT_COMPLETED = "boot_completed"
    const val START_REASON_MANUAL_ENTRY = "manual_entry"
    const val START_REASON_STICKY_RESTART = "sticky_restart"

    fun ensureOverlayIntent(
        context: Context,
        startReason: String = START_REASON_UNSPECIFIED
    ): Intent {
        return Intent(context, OverlayEntryService::class.java).apply {
            action = ACTION_ENSURE_OVERLAY
            putExtra(EXTRA_START_REASON, startReason)
        }
    }

    fun startPresenceService(
        context: Context,
        startReason: String = START_REASON_UNSPECIFIED
    ) {
        val appContext = context.applicationContext
        ContextCompat.startForegroundService(
            appContext,
            ensureOverlayIntent(appContext, startReason)
        )
    }

    fun startIntent(context: Context, command: String): Intent {
        return Intent(context, OverlayEntryService::class.java).apply {
            action = ACTION_START_SESSION
            putExtra(EXTRA_COMMAND, command)
        }
    }

    fun stopIntent(context: Context, migrate: Boolean): Intent {
        return Intent(context, OverlayEntryService::class.java).apply {
            action = ACTION_STOP_SESSION
            putExtra(EXTRA_MIGRATE, migrate)
        }
    }

    fun replyIntent(context: Context, sessionId: String, userText: String): Intent {
        return Intent(context, OverlayEntryService::class.java).apply {
            action = ACTION_REPLY_SESSION
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_USER_TEXT, userText)
        }
    }
}
