package com.example.agenthost.session

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object SessionSurfaceRegistry {
    private val surfaces = ConcurrentHashMap<String, WeakReference<Activity>>()

    fun register(sessionId: String, activity: Activity) {
        surfaces[sessionId] = WeakReference(activity)
    }

    fun unregister(sessionId: String, activity: Activity) {
        val current = surfaces[sessionId]?.get()
        if (current === activity) {
            surfaces.remove(sessionId)
        }
    }

    fun finish(sessionId: String) {
        val activity = surfaces.remove(sessionId)?.get() ?: return
        activity.runOnUiThread {
            if (!activity.isFinishing) {
                activity.finish()
            }
        }
    }
}
