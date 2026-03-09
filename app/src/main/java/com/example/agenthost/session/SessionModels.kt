package com.example.agenthost.session

import android.view.Display

enum class SessionDisplayMode {
    AUTO,
    MAIN_DISPLAY,
    VIRTUAL_DISPLAY
}

enum class SessionBootstrapTarget {
    NONE,
    SETTINGS
}

data class SessionStartOptions(
    val displayMode: SessionDisplayMode = SessionDisplayMode.AUTO,
    val visibleToUser: Boolean = false,
    val returnToVirtualAfterUser: Boolean = true,
    val debugForceMainDisplay: Boolean = false,
    val bootstrapTarget: SessionBootstrapTarget = SessionBootstrapTarget.NONE
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
    val virtualDisplayId: Int?,
    val surfaceDisplayId: Int?,
    val sessionTaskId: Int,
    val status: SessionStatus,
    val lastError: String?,
    val waitingQuestion: String?,
    val automationStep: Int,
    val displayMode: SessionDisplayMode,
    val visibleToUser: Boolean,
    val backgroundPreferred: Boolean,
    val returnToVirtualAfterUser: Boolean,
    val pendingReturnToVirtualDisplay: Boolean,
    val bootstrapTarget: SessionBootstrapTarget,
    val lastBootstrapCode: String?
) {
    fun isOnMainDisplay(): Boolean = executionDisplayId == Display.DEFAULT_DISPLAY
}

enum class SessionStatus {
    IDLE,
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
