package com.example.agenthost.migration

data class TaskValidationResult(
    val ok: Boolean,
    val code: String,
    val reason: String,
    val target: TaskMigrationTarget? = null,
    val actualForegroundTaskId: Int? = null
)

internal object TaskMigrationPolicy {
    // Shared migration classification must stay pure: it may read canonical session
    // fields and result codes only, never overlay retained state or report diagnostics.
    private val precheckFailureCodes = setOf(
        "TASK_CONFLICT_ON_MAIN",
        "USER_OWNS_MAIN_INSTANCE",
        "TARGET_TASK_REMOVED",
        "TASK_ID_STALE",
        "TASK_NOT_ON_EXPECTED_DISPLAY",
        "TASK_MOVE_BACK_CONFLICT",
        "TASK_QUERY_UNSUPPORTED",
        "TARGET_TASK_NOT_FOUND"
    )

    private val completionReturnSuccessCodes = setOf(
        "TASK_MIGRATION_OK",
        "TASK_MIGRATION_ALREADY_ON_MAIN"
    )

    private val idleFallbackCodes = setOf(
        "TARGET_TASK_NOT_FOUND",
        "TARGET_TASK_REMOVED",
        "TASK_ID_STALE",
        "TASK_QUERY_UNSUPPORTED"
    )

    private val recoveryCodes = setOf(
        "TASK_CONFLICT_ON_MAIN",
        "USER_OWNS_MAIN_INSTANCE",
        "TARGET_TASK_REMOVED",
        "TASK_ID_STALE",
        "TASK_NOT_ON_EXPECTED_DISPLAY",
        "TASK_MOVE_BACK_CONFLICT",
        "TASK_QUERY_UNSUPPORTED",
        "TARGET_TASK_NOT_FOUND",
        "TASK_MIGRATION_PRECHECK_FAILED",
        "TASK_MOVE_FAILED",
        "TASK_MOVE_BACK_FAILED"
    )

    fun requiresRecovery(code: String?): Boolean {
        return code != null && code in recoveryCodes
    }

    fun isPrecheckFailure(code: String?): Boolean {
        return code != null && code in precheckFailureCodes
    }

    fun isCompletionReturnSuccess(code: String?): Boolean {
        return code != null && code in completionReturnSuccessCodes
    }

    fun shouldCompletionFallbackToCleanup(code: String?): Boolean {
        return !isCompletionReturnSuccess(code)
    }

    fun shouldFallbackToIdle(code: String?): Boolean {
        return code != null && code in idleFallbackCodes
    }

    fun isSamePackageDifferentTask(
        expected: TaskMigrationTarget?,
        actualForeground: TaskMigrationTarget?
    ): Boolean {
        if (expected == null || actualForeground == null) {
            return false
        }
        val expectedPackage = expected.packageName ?: return false
        return expectedPackage.isNotBlank() &&
            actualForeground.packageName == expectedPackage &&
            actualForeground.taskId > 0 &&
            actualForeground.taskId != expected.taskId
    }
}
