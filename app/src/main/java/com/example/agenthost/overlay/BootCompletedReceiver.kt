package com.example.agenthost.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        Log.i(TAG, "boot_completed_received")
        OverlayActions.startPresenceService(
            context = context,
            startReason = OverlayActions.START_REASON_BOOT_COMPLETED
        )
    }
}
