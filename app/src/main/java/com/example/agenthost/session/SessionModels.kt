package com.example.agenthost.session

import android.view.Display
import com.example.agenthost.config.PlannerConfig

enum class SessionDisplayMode {
    AUTO,
    MAIN_DISPLAY,
    VIRTUAL_DISPLAY
}

enum class SessionBootstrapTarget {
    NONE,
    SETTINGS
}

enum class CompletionReturnPolicy {
    CURRENT_BEHAVIOR,
    RETURN_TASK_TO_MAIN
}

enum class PostCompletionDisposition {
    NONE,
    CLEANUP_IMMEDIATELY,
    RETURN_TASK_TO_MAIN,
    SHOW_POST_COMPLETION_ENTRY
}

enum class PostCompletionState {
    NONE,
    PENDING,
    SUCCEEDED,
    FALLBACK_CLEANUP
}

enum class WaitingUserDisposition {
    STANDARD_HANDOFF,
    COMPLETION_CONFIRMATION
}

data class SessionStartOptions(
    val displayMode: SessionDisplayMode = SessionDisplayMode.AUTO,
    val visibleToUser: Boolean = false,
    val returnToVirtualAfterUser: Boolean = true,
    val completionReturnPolicy: CompletionReturnPolicy = CompletionReturnPolicy.CURRENT_BEHAVIOR,
    val debugForceMainDisplay: Boolean = false,
    val bootstrapTarget: SessionBootstrapTarget = SessionBootstrapTarget.NONE,
    val plannerConfigOverride: PlannerConfig? = null
) {
    fun resolvedDisplayMode(): SessionDisplayMode {
        return when {
            debugForceMainDisplay -> SessionDisplayMode.MAIN_DISPLAY
            displayMode == SessionDisplayMode.AUTO -> SessionDisplayMode.VIRTUAL_DISPLAY
            else -> displayMode
        }
    }
}

data class SessionState(
    val sessionId: String,
    val command: String,
    val executionDisplayId: Int,
    val currentVisibleDisplayId: Int?,
    val virtualDisplayId: Int?,
    val surfaceDisplayId: Int?,
    val sessionTaskId: Int,
    val lastKnownTaskId: Int,
    val lastKnownTopActivity: String?,
    val lastKnownTopPackage: String?,
    val status: SessionStatus,
    val lastError: String?,
    val waitingQuestion: String?,
    val waitingUserDisposition: WaitingUserDisposition = WaitingUserDisposition.STANDARD_HANDOFF,
    val automationStep: Int,
    val displayMode: SessionDisplayMode,
    val visibleToUser: Boolean,
    val backgroundPreferred: Boolean,
    val returnToVirtualAfterUser: Boolean,
    val completionReturnPolicy: CompletionReturnPolicy,
    val migratedToMain: Boolean,
    val lastMigrationCode: String?,
    val lastMigrationDirection: String?,
    val lastMigrationConflictCode: String? = null,
    val lastMigrationConflictReason: String? = null,
    val lastKnownTaskAlive: Boolean? = null,
    val lastKnownTaskDisplayId: Int? = null,
    val userForegroundTaskId: Int? = null,
    val recoveryNeeded: Boolean = false,
    val bootstrapTarget: SessionBootstrapTarget,
    val lastBootstrapCode: String?,
    val postCompletionDisposition: PostCompletionDisposition = PostCompletionDisposition.NONE,
    val postCompletionState: PostCompletionState = PostCompletionState.NONE,
    val postCompletionResultCode: String? = null
) {
    // Compatibility mirror: there is no separate mutable current execution display.
    val currentExecutionDisplayId: Int
        get() = executionDisplayId

    // Compatibility mirror: the previous flag no longer has an owning runtime state.
    val pendingReturnToVirtualDisplay: Boolean
        get() = false

    fun isOnMainDisplay(): Boolean = currentVisibleDisplayId == Display.DEFAULT_DISPLAY
}

// Live idle is represented by `activeSession == null`; there is no canonical
// in-session IDLE state value.
enum class SessionStatus {
    STARTING,
    READY,
    RUNNING,
    WAITING_USER,
    STOPPING,
    MIGRATED,
    CLOSED,
    FAILED,
    COMPLETED
}

data class SessionAttachAck(
    val sessionId: String,
    val taskId: Int,
    val displayId: Int,
    val component: String
)

data class SessionResult(
    val ok: Boolean,
    val code: String,
    val data: Map<String, Any?> = emptyMap()
)
