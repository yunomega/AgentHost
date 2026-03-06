package com.example.agenthost.migration

interface TaskMigrationController {
    fun moveTaskToDefaultDisplay(taskId: Int): Boolean
    fun closeTask(taskId: Int): Boolean
}
