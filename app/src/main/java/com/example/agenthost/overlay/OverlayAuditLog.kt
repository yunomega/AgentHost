package com.example.agenthost.overlay

import com.example.agenthost.session.SessionState

internal object OverlayAuditLog {
    private const val INVALID_TASK_ID = -1

    fun build(
        event: String,
        action: String,
        overlayUiState: String,
        sessionId: String? = null,
        activeSession: SessionState? = null,
        session: SessionState? = activeSession,
        extras: String? = null
    ): String {
        val resolved = session ?: activeSession
        val resolvedSessionId = sessionId ?: resolved?.sessionId ?: "null"
        val resolvedStatus = resolved?.status?.name ?: activeSession?.status?.name ?: "null"
        val activeSessionId = activeSession?.sessionId ?: "null"
        val taskId = resolved?.sessionTaskId ?: INVALID_TASK_ID
        val targetTaskId = resolved?.lastKnownTaskId ?: INVALID_TASK_ID
        val displayId = resolved?.currentVisibleDisplayId ?: resolved?.executionDisplayId ?: -1
        val returnPolicy = resolved?.completionReturnPolicy?.name ?: "null"
        val ext = extras ?: "{}"
        return buildString {
            append("event=").append(event)
            append(" action=").append(action)
            append(" sessionId=").append(resolvedSessionId)
            append(" sessionStatus=").append(resolvedStatus)
            append(" activeSessionId=").append(activeSessionId)
            append(" taskId=").append(taskId)
            append(" targetTaskId=").append(targetTaskId)
            append(" displayId=").append(displayId)
            append(" returnPolicy=").append(returnPolicy)
            append(" overlayUiState=").append(overlayUiState)
            append(" extras=").append(ext)
        }
    }
}
