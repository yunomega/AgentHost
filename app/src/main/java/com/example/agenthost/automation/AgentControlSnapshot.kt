package com.example.agenthost.automation

data class AgentControlRecentAction(
    val step: Int,
    val actionType: String,
    val resultCategory: String,
    val summary: String,
    val approxPoint: CanonicalPoint? = null,
    val phaseAfter: String? = null
)

data class AgentControlSnapshot(
    val globalTask: String,
    val currentPhase: String,
    val parentHint: String?,
    val targetHint: String?,
    val allowedActions: List<String>,
    val blockedActions: List<String>,
    val recentActions: List<AgentControlRecentAction>,
    val lastResult: String?,
    val completionState: String,
    val doneOnly: Boolean
)
