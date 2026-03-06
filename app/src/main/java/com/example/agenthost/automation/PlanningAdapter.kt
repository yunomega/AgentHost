package com.example.agenthost.automation

data class PlanningRequest(
    val sessionId: String,
    val command: String,
    val step: Int,
    val observation: ObservationResult,
    val recentActions: List<HistoryActionRecord>,
    val lastFailureCode: String?,
    val lastUserReply: String?
)

interface PlanningAdapter {
    fun planNext(request: PlanningRequest): PlanResult

    fun clearSession(sessionId: String)
}
