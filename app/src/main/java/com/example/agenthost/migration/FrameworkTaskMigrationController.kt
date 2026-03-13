package com.example.agenthost.migration

import android.view.Display
import com.example.agenthost.core.AgentHostLog

class FrameworkTaskMigrationController : TaskMigrationController {
    companion object {
        private const val TAG = "TaskMigrationController"
    }

    override fun isTaskQuerySupported(): Boolean {
        return AtmHiddenApi.supportsTaskQuery()
    }

    override fun findTaskById(taskId: Int): TaskMigrationTarget? {
        val target = selectTaskById(
            tasks = AtmHiddenApi.queryTasks(displayId = null).map { it.toTarget() },
            taskId = taskId
        )
        AgentHostLog.i(
            TAG,
            "findTaskById taskId=$taskId resultTaskId=${target?.taskId ?: -1} " +
                "displayId=${target?.displayId ?: -1} topActivity=${target?.topActivity ?: "null"} " +
                "package=${target?.packageName ?: "null"}"
        )
        return target
    }

    override fun findTopTaskOnDisplay(
        displayId: Int,
        excludedPackages: Set<String>
    ): TaskMigrationTarget? {
        val target = selectTopTask(
            tasks = AtmHiddenApi.queryTasks(displayId = displayId).map { it.toTarget() },
            excludedPackages = excludedPackages
        )
        AgentHostLog.i(
            TAG,
            "findTopTaskOnDisplay displayId=$displayId excludedPackages=$excludedPackages " +
                "taskId=${target?.taskId ?: -1} topActivity=${target?.topActivity ?: "null"} " +
                "package=${target?.packageName ?: "null"}"
        )
        return target
    }

    override fun findTaskOnDisplayByPackage(displayId: Int, packageName: String): TaskMigrationTarget? {
        val target = selectTaskByPackage(
            tasks = AtmHiddenApi.queryTasks(displayId = displayId).map { it.toTarget() },
            packageName = packageName
        )
        AgentHostLog.i(
            TAG,
            "findTaskOnDisplayByPackage displayId=$displayId package=$packageName " +
                "taskId=${target?.taskId ?: -1} topActivity=${target?.topActivity ?: "null"}"
        )
        return target
    }

    override fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        val ok = AtmHiddenApi.moveRootTaskToDisplay(taskId, displayId)
        AgentHostLog.i(
            TAG,
            "moveTaskToDisplay taskId=$taskId targetDisplayId=$displayId ok=$ok " +
                "isDefaultDisplay=${displayId == Display.DEFAULT_DISPLAY}"
        )
        return ok
    }

    override fun closeTask(taskId: Int): Boolean {
        val ok = AtmHiddenApi.removeTask(taskId)
        AgentHostLog.i(TAG, "closeTask taskId=$taskId ok=$ok")
        return ok
    }

    private fun AtmHiddenApi.ReflectedTaskInfo.toTarget(): TaskMigrationTarget {
        return TaskMigrationTarget(
            taskId = taskId,
            displayId = displayId,
            topActivity = topActivity,
            packageName = packageName
        )
    }

    internal fun selectTopTask(
        tasks: List<TaskMigrationTarget>,
        excludedPackages: Set<String>
    ): TaskMigrationTarget? {
        return tasks.firstOrNull { task ->
            task.taskId > 0 &&
                !task.packageName.isNullOrBlank() &&
                task.packageName !in excludedPackages
        }
    }

    internal fun selectTaskByPackage(
        tasks: List<TaskMigrationTarget>,
        packageName: String
    ): TaskMigrationTarget? {
        return tasks.firstOrNull { task ->
            task.taskId > 0 && task.packageName == packageName
        }
    }

    internal fun selectTaskById(
        tasks: List<TaskMigrationTarget>,
        taskId: Int
    ): TaskMigrationTarget? {
        return tasks.firstOrNull { task -> task.taskId == taskId }
    }
}
