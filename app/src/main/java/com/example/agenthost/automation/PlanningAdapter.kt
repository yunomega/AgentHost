package com.example.agenthost.automation

data class PlanningRequest(
    val sessionId: String,
    val command: String,
    val step: Int,
    val executionDisplayId: Int,
    val sessionTaskId: Int,
    val planningMode: PlanningMode,
    val capabilityProfile: CapabilityProfile,
    val observation: ObservationResult,
    val recentActions: List<HistoryActionRecord>,
    val lastFailureCode: String?,
    val lastUserReply: String?
)

interface PlanningAdapter {
    fun modeForSession(sessionId: String): PlanningMode

    fun planNext(request: PlanningRequest): PlanResult

    fun clearSession(sessionId: String)
}
