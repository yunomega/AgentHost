package com.example.agenthost.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameworkTaskMigrationControllerTest {
    private val controller = FrameworkTaskMigrationController()

    @Test
    fun selectTopTaskSkipsAgentHostAndReturnsFirstRealTask() {
        val tasks = listOf(
            TaskMigrationTarget(
                taskId = 10,
                displayId = 7,
                topActivity = "com.example.agenthost/.session.SessionRootActivity",
                packageName = "com.example.agenthost"
            ),
            TaskMigrationTarget(
                taskId = 21,
                displayId = 7,
                topActivity = "com.android.settings/.Settings",
                packageName = "com.android.settings"
            ),
            TaskMigrationTarget(
                taskId = 30,
                displayId = 7,
                topActivity = "com.example.other/.MainActivity",
                packageName = "com.example.other"
            )
        )

        val result = controller.selectTopTask(tasks, excludedPackages = setOf("com.example.agenthost"))

        assertEquals(21, result?.taskId)
        assertEquals("com.android.settings", result?.packageName)
    }

    @Test
    fun selectTaskByPackageFindsSettingsFallback() {
        val tasks = listOf(
            TaskMigrationTarget(
                taskId = 10,
                displayId = 7,
                topActivity = "com.example.agenthost/.session.SessionRootActivity",
                packageName = "com.example.agenthost"
            ),
            TaskMigrationTarget(
                taskId = 21,
                displayId = 7,
                topActivity = "com.android.settings/.Settings",
                packageName = "com.android.settings"
            )
        )

        val result = controller.selectTaskByPackage(tasks, packageName = "com.android.settings")

        assertEquals(21, result?.taskId)
    }

    @Test
    fun selectTaskByIdUsesExactTaskEvenWhenPackageMatches() {
        val tasks = listOf(
            TaskMigrationTarget(
                taskId = 21,
                displayId = 0,
                topActivity = "com.android.settings/.homepage.SettingsHomepageActivity",
                packageName = "com.android.settings"
            ),
            TaskMigrationTarget(
                taskId = 84,
                displayId = 9,
                topActivity = "com.android.settings/.Settings\$NetworkDashboardActivity",
                packageName = "com.android.settings"
            )
        )

        val result = controller.selectTaskById(tasks, taskId = 84)

        assertEquals(84, result?.taskId)
        assertEquals(9, result?.displayId)
        assertEquals("com.android.settings/.Settings\$NetworkDashboardActivity", result?.topActivity)
    }

    @Test
    fun selectTopTaskReturnsNullWhenOnlyExcludedTasksExist() {
        val tasks = listOf(
            TaskMigrationTarget(
                taskId = 10,
                displayId = 7,
                topActivity = "com.example.agenthost/.session.SessionRootActivity",
                packageName = "com.example.agenthost"
            )
        )

        val result = controller.selectTopTask(tasks, excludedPackages = setOf("com.example.agenthost"))

        assertNull(result)
    }
}
