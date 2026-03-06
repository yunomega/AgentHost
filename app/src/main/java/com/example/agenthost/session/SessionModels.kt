package com.example.agenthost.session

data class SessionState(
    val sessionId: String,
    val command: String,
    val vdDisplayId: Int,
    val sessionTaskId: Int,
    val status: SessionStatus,
    val lastError: String?,
    val waitingQuestion: String?,
    val automationStep: Int
)

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
