package com.example.agenthost.migration

import android.util.Log

object
AtmHiddenApi {
    private const val TAG = "AtmHiddenApi"
    private const val ATM_CLASS = "android.app.ActivityTaskManager"

    private val service: Any? by lazy {
        try {
            val cls = Class.forName(ATM_CLASS)
            val getService = cls.getMethod("getService")
            getService.invoke(null)
        } catch (t: Throwable) {
            Log.e(TAG, "getService failed", t)
            null
        }
    }

    fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        val s = service ?: return false
        return try {
            val m = s.javaClass.getMethod(
                "moveRootTaskToDisplay",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
            m.invoke(s, taskId, displayId)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "moveRootTaskToDisplay failed taskId=$taskId displayId=$displayId", t)
            false
        }
    }

    fun removeTask(taskId: Int): Boolean {
        val s = service ?: return false
        return try {
            val m = s.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType!!)
            val result = m.invoke(s, taskId)
            (result as? Boolean) ?: false
        } catch (t: Throwable) {
            Log.e(TAG, "removeTask failed taskId=$taskId", t)
            false
        }
    }
}
