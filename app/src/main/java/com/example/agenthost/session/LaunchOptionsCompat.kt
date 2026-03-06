package com.example.agenthost.session

import android.app.ActivityOptions
import android.util.Log

object LaunchOptionsCompat {
    private const val TAG = "LaunchOptionsCompat"

    fun build(vdDisplayId: Int): ActivityOptions? {
        val options = try {
            ActivityOptions.makeBasic()
        } catch (t: Throwable) {
            Log.e(TAG, "makeBasic failed vdDisplayId=$vdDisplayId", t)
            return null
        }

        try {
            options.setLaunchDisplayId(vdDisplayId)
        } catch (t: Throwable) {
            Log.e(TAG, "setLaunchDisplayId failed vdDisplayId=$vdDisplayId", t)
            return null
        }

        if (!setMovableTaskRequired(options, true)) {
            Log.e(TAG, "setMovableTaskRequired unavailable or failed vdDisplayId=$vdDisplayId")
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
