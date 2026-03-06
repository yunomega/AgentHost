package com.example.agenthost.automation

data class HistoryActionRecord(
    val step: Int,
    val actionType: String,
    val code: String,
    val summary: String,
    val timestampMs: Long
)

class AutomationHistory(
    private val maxActionsPerSession: Int = 20
) {
    private data class SessionHistory(
        var nextStep: Int = 1,
        var scriptCursor: Int = 0,
        var lastFailureCode: String? = null,
        var lastUserReply: String? = null,
        var pendingQuestion: String? = null,
        val actions: ArrayDeque<HistoryActionRecord> = ArrayDeque()
    )

    private val lock = Any()
    private val bySession = mutableMapOf<String, SessionHistory>()

    fun claimNextStep(sessionId: String): Int = synchronized(lock) {
        val state = bySession.getOrPut(sessionId) { SessionHistory() }
        val step = state.nextStep
        state.nextStep += 1
        step
    }

    fun getScriptCursor(sessionId: String): Int = synchronized(lock) {
        bySession[sessionId]?.scriptCursor ?: 0
    }

    fun setScriptCursor(sessionId: String, cursor: Int) = synchronized(lock) {
        val state = bySession.getOrPut(sessionId) { SessionHistory() }
        state.scriptCursor = cursor
    }

    fun recordAction(
        sessionId: String,
        step: Int,
        actionType: String,
        code: String,
        summary: String
    ) = synchronized(lock) {
        val state = bySession.getOrPut(sessionId) { SessionHistory() }
        state.actions.addLast(
            HistoryActionRecord(
                step = step,
                actionType = actionType,
                code = code,
                summary = summary,
                timestampMs = System.currentTimeMillis()
            )
        )
        while (state.actions.size > maxActionsPerSession) {
            state.actions.removeFirst()
        }
    }

    fun recentActions(sessionId: String): List<HistoryActionRecord> = synchronized(lock) {
        bySession[sessionId]?.actions?.toList() ?: emptyList()
    }

    fun setLastFailureCode(sessionId: String, code: String?) = synchronized(lock) {
        val state = bySession.getOrPut(sessionId) { SessionHistory() }
        state.lastFailureCode = code
    }

    fun lastFailureCode(sessionId: String): String? = synchronized(lock) {
        bySession[sessionId]?.lastFailureCode
    }

    fun setLastUserReply(sessionId: String, reply: String) = synchronized(lock) {
        val state = bySession.getOrPut(sessionId) { SessionHistory() }
        state.lastUserReply = reply
    }

    fun lastUserReply(sessionId: String): String? = synchronized(lock) {
        bySession[sessionId]?.lastUserReply
    }

    fun setPendingQuestion(sessionId: String, question: String?) = synchronized(lock) {
        val state = bySession.getOrPut(sessionId) { SessionHistory() }
        state.pendingQuestion = question
    }

    fun pendingQuestion(sessionId: String): String? = synchronized(lock) {
        bySession[sessionId]?.pendingQuestion
    }

    fun clearSession(sessionId: String): Unit = synchronized(lock) {
        bySession.remove(sessionId)
    }
}
