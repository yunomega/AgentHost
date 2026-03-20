package com.example.agenthost.session

/**
 * Overlay render-diff snapshot derived from canonical session state.
 *
 * It intentionally tracks only fields that affect listener-driven UI refreshes.
 * This is not a canonical state snapshot and intentionally excludes fields such
 * as `automationStep`.
 */
internal data class SessionStateListenerSnapshot(
    val sessionId: String?,
    val status: SessionStatus?,
    val executionDisplayId: Int?,
    val currentVisibleDisplayId: Int?,
    val virtualDisplayId: Int?,
    val surfaceDisplayId: Int?,
    val displayMode: SessionDisplayMode?,
    val visibleToUser: Boolean?,
    val waitingQuestion: String?,
    val waitingUserDisposition: WaitingUserDisposition?,
    val completionReturnPolicy: CompletionReturnPolicy?,
    val migratedToMain: Boolean?,
    val lastMigrationCode: String?,
    val lastMigrationConflictCode: String?,
    val lastMigrationConflictReason: String?,
    val recoveryNeeded: Boolean?,
    val lastError: String?,
    val postCompletionDisposition: PostCompletionDisposition?,
    val postCompletionState: PostCompletionState?,
    val postCompletionResultCode: String?
) {
    companion object {
        fun forRenderDiff(state: SessionState?): SessionStateListenerSnapshot {
            return SessionStateListenerSnapshot(
                sessionId = state?.sessionId,
                status = state?.status,
                executionDisplayId = state?.executionDisplayId,
                currentVisibleDisplayId = state?.currentVisibleDisplayId,
                virtualDisplayId = state?.virtualDisplayId,
                surfaceDisplayId = state?.surfaceDisplayId,
                displayMode = state?.displayMode,
                visibleToUser = state?.visibleToUser,
                waitingQuestion = state?.waitingQuestion,
                waitingUserDisposition = state?.waitingUserDisposition,
                completionReturnPolicy = state?.completionReturnPolicy,
                migratedToMain = state?.migratedToMain,
                lastMigrationCode = state?.lastMigrationCode,
                lastMigrationConflictCode = state?.lastMigrationConflictCode,
                lastMigrationConflictReason = state?.lastMigrationConflictReason,
                recoveryNeeded = state?.recoveryNeeded,
                lastError = state?.lastError,
                postCompletionDisposition = state?.postCompletionDisposition,
                postCompletionState = state?.postCompletionState,
                postCompletionResultCode = state?.postCompletionResultCode
            )
        }
    }
}
