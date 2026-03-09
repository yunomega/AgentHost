package com.example.agenthost.session

import android.app.ActivityOptions
import android.util.Log

object LaunchOptionsCompat {
    private const val TAG = "LaunchOptionsCompat"

    fun build(displayId: Int): ActivityOptions? {
        val options = try {
            ActivityOptions.makeBasic()
        } catch (t: Throwable) {
            Log.e(TAG, "makeBasic failed displayId=$displayId", t)
            return null
        }

        try {
            options.setLaunchDisplayId(displayId)
        } catch (t: Throwable) {
            Log.e(TAG, "setLaunchDisplayId failed displayId=$displayId", t)
            return null
        }

        if (!setMovableTaskRequired(options, true)) {
            Log.e(TAG, "setMovableTaskRequired unavailable or failed displayId=$displayId")
            return null
        }
        return options
    }

    private fun setMovableTaskRequired(options: ActivityOptions, required: Boolean): Boolean {
        return try {
            val method = ActivityOptions::class.java.getMethod(
                "setMovableTaskRequired",
                Boolean::class.javaPrimitiveType!!
            )
            method.invoke(options, required)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "setMovableTaskRequired reflection failed required=$required", t)
            false
        }
    }
}
