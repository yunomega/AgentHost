package com.example.agenthost.migration

data class TaskMigrationTarget(
    val taskId: Int,
    val displayId: Int,
    val topActivity: String?,
    val packageName: String?
)

interface TaskMigrationController {
    fun isTaskQuerySupported(): Boolean
    fun findTaskById(taskId: Int): TaskMigrationTarget?
    fun findTopTaskOnDisplay(displayId: Int, excludedPackages: Set<String>): TaskMigrationTarget?
    fun findTaskOnDisplayByPackage(displayId: Int, packageName: String): TaskMigrationTarget?
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean
    fun closeTask(taskId: Int): Boolean
}
