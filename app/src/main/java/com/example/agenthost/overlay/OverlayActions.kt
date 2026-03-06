package com.example.agenthost.overlay

import android.content.Context
import android.content.Intent

object OverlayActions {
    const val ACTION_START_SESSION = "com.example.agenthost.action.START_SESSION"
    const val ACTION_STOP_SESSION = "com.example.agenthost.action.STOP_SESSION"
    const val EXTRA_COMMAND = "extra_command"
    const val EXTRA_MIGRATE = "extra_migrate"

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
}
