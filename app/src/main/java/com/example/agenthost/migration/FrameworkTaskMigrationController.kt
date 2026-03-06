package com.example.agenthost.migration

import android.util.Log
import android.view.Display

class FrameworkTaskMigrationController : TaskMigrationController {
    companion object {
        private const val TAG = "TaskMigrationController"
    }

    override fun moveTaskToDefaultDisplay(taskId: Int): Boolean {
        val ok = AtmHiddenApi.moveRootTaskToDisplay(taskId, Display.DEFAULT_DISPLAY)
        Log.i(
            TAG,
            "moveTaskToDefaultDisplay taskId=$taskId targetDisplayId=${Display.DEFAULT_DISPLAY} ok=$ok"
        )
        return ok
    }

    override fun closeTask(taskId: Int): Boolean {
        val ok = AtmHiddenApi.removeTask(taskId)
        Log.i(TAG, "closeTask taskId=$taskId ok=$ok")
        return ok
    }
}
