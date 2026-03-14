package com.example.agenthost.overlay

import android.content.Context

class OverlayPresencePrefs(context: Context) {
    companion object {
        private const val PREFS_NAME = "overlay_presence"
        private const val KEY_LAST_START_REASON = "last_start_reason"
        private const val KEY_LAST_STARTED_AT_MS = "last_started_at_ms"
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun recordStart(reason: String, startedAtMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_START_REASON, reason)
            .putLong(KEY_LAST_STARTED_AT_MS, startedAtMs)
            .apply()
    }
}
