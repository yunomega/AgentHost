package com.example.agenthost.session

data class SessionState(
    val sessionId: String,
    val command: String,
    val vdDisplayId: Int,
    val sessionTaskId: Int,
    val status: SessionStatus,
    val lastError: String?
)

enum class SessionStatus {
    IDLE,
    STARTING,
    READY,
    RUNNING,
    STOPPING,
    MIGRATED,
    CLOSED,
    FAILED
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
