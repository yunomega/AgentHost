package com.example.agenthost.migration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMigrationPolicyTest {
    @Test
    fun requiresRecoveryIsTrueForConflictAndTaskLossCodes() {
        assertTrue(TaskMigrationPolicy.requiresRecovery("USER_OWNS_MAIN_INSTANCE"))
        assertTrue(TaskMigrationPolicy.requiresRecovery("TASK_MOVE_BACK_CONFLICT"))
        assertTrue(TaskMigrationPolicy.requiresRecovery("TARGET_TASK_REMOVED"))
        assertTrue(TaskMigrationPolicy.requiresRecovery("TASK_MIGRATION_PRECHECK_FAILED"))
    }

    @Test
    fun requiresRecoveryIsFalseForSuccessAndIdempotentCodes() {
        assertFalse(TaskMigrationPolicy.requiresRecovery("TASK_MIGRATION_OK"))
        assertFalse(TaskMigrationPolicy.requiresRecovery("TASK_MIGRATION_BACK_OK"))
        assertFalse(TaskMigrationPolicy.requiresRecovery("TASK_MIGRATION_ALREADY_ON_MAIN"))
        assertFalse(TaskMigrationPolicy.requiresRecovery("TASK_MIGRATION_BACK_SKIPPED"))
    }

    @Test
    fun samePackageDifferentTaskCountsAsUserOwnedConflict() {
        val expected = TaskMigrationTarget(
            taskId = 42,
            displayId = 7,
            topActivity = "com.android.settings/.homepage.SettingsHomepageActivity",
            packageName = "com.android.settings"
        )
        val actualForeground = TaskMigrationTarget(
            taskId = 99,
            displayId = 0,
            topActivity = "com.android.settings/.Settings\$WifiSettingsActivity",
            packageName = "com.android.settings"
        )

        assertTrue(TaskMigrationPolicy.isSamePackageDifferentTask(expected, actualForeground))
    }

    @Test
    fun sameTaskOrDifferentPackageDoesNotConflict() {
        val expected = TaskMigrationTarget(
            taskId = 42,
            displayId = 7,
            topActivity = "com.android.settings/.homepage.SettingsHomepageActivity",
            packageName = "com.android.settings"
        )
        val sameTask = expected.copy(displayId = 0)
        val differentPackage = TaskMigrationTarget(
            taskId = 99,
            displayId = 0,
            topActivity = "com.android.launcher3/.Launcher",
            packageName = "com.android.launcher3"
        )

        assertFalse(TaskMigrationPolicy.isSamePackageDifferentTask(expected, sameTask))
        assertFalse(TaskMigrationPolicy.isSamePackageDifferentTask(expected, differentPackage))
    }
}
