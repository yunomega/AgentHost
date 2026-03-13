package com.example.agenthost.migration

import android.content.ComponentName
import android.content.Intent
import com.example.agenthost.core.AgentHostLog
import java.lang.reflect.Field
import java.lang.reflect.Method

object
AtmHiddenApi {
    data class ReflectedTaskInfo(
        val taskId: Int,
        val displayId: Int,
        val topActivity: String?,
        val packageName: String?
    )

    private const val TAG = "AtmHiddenApi"
    private const val ATM_CLASS = "android.app.ActivityTaskManager"

    private val service: Any? by lazy {
        try {
            val cls = Class.forName(ATM_CLASS)
            val getService = cls.getMethod("getService")
            getService.invoke(null)
        } catch (t: Throwable) {
            AgentHostLog.e(TAG, "getService failed error=${t.message}")
            null
        }
    }

    fun supportsTaskQuery(): Boolean {
        val s = service ?: return false
        if (s.javaClass.methods.any { it.name == "getTasks" }) {
            return true
        }
        return runCatching {
            s.javaClass.getMethod("getAllRootTaskInfos")
            true
        }.getOrDefault(false)
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
            AgentHostLog.e(
                TAG,
                "moveRootTaskToDisplay failed taskId=$taskId displayId=$displayId error=${t.message}"
            )
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
            AgentHostLog.e(TAG, "removeTask failed taskId=$taskId error=${t.message}")
            false
        }
    }

    fun queryTasks(displayId: Int? = null, maxNum: Int = 32): List<ReflectedTaskInfo> {
        val s = service ?: return emptyList()
        invokeGetTasks(s, maxNum, displayId)?.let { return it }
        invokeGetAllRootTaskInfos(s, displayId)?.let { return it }
        AgentHostLog.w(
            TAG,
            "queryTasks unavailable displayId=${displayId ?: "all"} maxNum=$maxNum"
        )
        return emptyList()
    }

    private fun invokeGetTasks(
        service: Any,
        maxNum: Int,
        displayId: Int?
    ): List<ReflectedTaskInfo>? {
        val methods = service.javaClass.methods.filter { it.name == "getTasks" }
        for (method in methods.sortedByDescending { it.parameterTypes.size }) {
            val args = buildGetTasksArgs(method, maxNum, displayId) ?: continue
            val invocation = runCatching { method.invoke(service, *args) }
            val result = invocation.getOrNull()
            if (result == null && invocation.isFailure) {
                val error = invocation.exceptionOrNull()
                AgentHostLog.w(
                    TAG,
                    "getTasks invoke failed method=${method.parameterTypes.joinToString()} " +
                        "displayId=${displayId ?: "all"} error=${error?.message}"
                )
                continue
            }
            val tasks = toTaskInfoList(
                result = result,
                displayIdFilter = if (method.parameterTypes.size >= 4) null else displayId
            )
            if (tasks.isNotEmpty()) {
                return tasks
            }
            if (displayId != null && method.parameterTypes.size >= 4) {
                return emptyList()
            }
        }
        return null
    }

    private fun invokeGetAllRootTaskInfos(
        service: Any,
        displayId: Int?
    ): List<ReflectedTaskInfo>? {
        val method = runCatching { service.javaClass.getMethod("getAllRootTaskInfos") }.getOrNull()
            ?: return null
        val result = runCatching { method.invoke(service) }.getOrElse { error ->
            AgentHostLog.w(
                TAG,
                "getAllRootTaskInfos failed displayId=${displayId ?: "all"} error=${error.message}"
            )
            return null
        }
        return toTaskInfoList(result, displayId)
    }

    private fun buildGetTasksArgs(
        method: Method,
        maxNum: Int,
        displayId: Int?
    ): Array<Any?>? {
        val params = method.parameterTypes
        return when {
            params.size == 4 &&
                params[0] == Int::class.javaPrimitiveType &&
                params[1] == Boolean::class.javaPrimitiveType &&
                params[2] == Boolean::class.javaPrimitiveType &&
                params[3] == Int::class.javaPrimitiveType -> {
                arrayOf(maxNum, false, false, displayId ?: -1)
            }

            params.size == 3 &&
                params[0] == Int::class.javaPrimitiveType &&
                params[1] == Boolean::class.javaPrimitiveType &&
                params[2] == Boolean::class.javaPrimitiveType -> {
                arrayOf(maxNum, false, false)
            }

            params.size == 2 &&
                params[0] == Int::class.javaPrimitiveType &&
                params[1] == Boolean::class.javaPrimitiveType -> {
                arrayOf(maxNum, false)
            }

            params.size == 1 && params[0] == Int::class.javaPrimitiveType -> {
                arrayOf(maxNum)
            }

            else -> null
        }
    }

    private fun toTaskInfoList(result: Any?, displayIdFilter: Int?): List<ReflectedTaskInfo> {
        val rawTasks = result as? List<*> ?: return emptyList()
        return rawTasks.mapNotNull { item ->
            val task = toTaskInfo(item) ?: return@mapNotNull null
            if (displayIdFilter == null || task.displayId == displayIdFilter) {
                task
            } else {
                null
            }
        }
    }

    private fun toTaskInfo(item: Any?): ReflectedTaskInfo? {
        if (item == null) {
            return null
        }
        val taskId = readInt(item, "taskId")
            ?: readInt(item, "id")
            ?: return null
        val displayId = readInt(item, "displayId")
            ?: readInt(item, "mDisplayId")
            ?: -1
        val topActivityComponent = readComponent(item, "topActivity")
        val baseActivityComponent = readComponent(item, "baseActivity")
        val baseIntentComponent = readIntent(item, "baseIntent")?.component
        val topActivity = topActivityComponent?.flattenToShortString()
        val packageName = topActivityComponent?.packageName
            ?: baseActivityComponent?.packageName
            ?: baseIntentComponent?.packageName
        return ReflectedTaskInfo(
            taskId = taskId,
            displayId = displayId,
            topActivity = topActivity,
            packageName = packageName
        )
    }

    private fun readInt(target: Any, fieldName: String): Int? {
        val field = findField(target.javaClass, fieldName) ?: return null
        return runCatching { field.getInt(target) }.getOrNull()
    }

    private fun readComponent(target: Any, fieldName: String): ComponentName? {
        val field = findField(target.javaClass, fieldName) ?: return null
        return runCatching { field.get(target) as? ComponentName }.getOrNull()
    }

    private fun readIntent(target: Any, fieldName: String): Intent? {
        val field = findField(target.javaClass, fieldName) ?: return null
        return runCatching { field.get(target) as? Intent }.getOrNull()
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }
}
