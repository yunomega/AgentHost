package com.example.agenthost.automation

import kotlin.math.abs

data class ObservationFingerprint(
    val width: Int,
    val height: Int,
    val hash: String
)

/**
 * Control-step result taxonomy only.
 *
 * These values summarize automation progress for planner/report consumers and
 * must not be treated as canonical session lifecycle states.
 */
enum class StepResultCategory {
    SUCCESS_CHANGED,
    SUCCESS_NO_CHANGE,
    GUARDRAIL_BLOCKED,
    COMPLETION_SATISFIED,
    LOW_YIELD_HANDOFF,
    PLATFORM_FAILED,
    SYNTHETIC_DONE,
    WAIT_ELAPSED
}

data class StepResultSummary(
    val category: StepResultCategory,
    val code: String,
    val message: String,
    val observationChanged: Boolean? = null,
    val lowYield: Boolean = false,
    val guardrailRule: String? = null
) {
    fun categoryName(): String = category.name
}

data class ControlStepRecord(
    val step: Int,
    val proposedActionType: String,
    val finalActionType: String,
    val approxPoint: CanonicalPoint? = null,
    val resultCategory: String,
    val summary: String,
    val guardrailRule: String? = null,
    val guardrailDecision: String? = null,
    val pageChanged: Boolean? = null,
    val phaseBefore: String? = null,
    val phaseAfter: String? = null,
    val platformCode: String? = null
)

data class GuardrailState(
    val subgoalState: SubgoalState,
    val recentSteps: ArrayDeque<ControlStepRecord> = ArrayDeque(),
    val lastObservationFingerprint: ObservationFingerprint? = null,
    val lastResult: StepResultSummary? = null,
    val lastSurfaceContext: SurfaceContext = SurfaceContext(),
    val lastProgressStep: Int? = null,
    val consecutiveNoChangeCount: Int = 0,
    val consecutiveLowYieldCount: Int = 0,
    val consecutiveBackHomeBlocks: Int = 0,
    val lastClickPoint: CanonicalPoint? = null,
    val nearbyRepeatClickCount: Int = 0,
    val lastLocallyConfirmedClickPoint: CanonicalPoint? = null,
    val lastLocallyConfirmedClickStep: Int? = null,
    val lastGoalStatus: GoalSatisfactionStatus = GoalSatisfactionStatus.UNSURE,
    val lastGoalReason: String? = null
) {
    fun append(record: ControlStepRecord, maxRecentSteps: Int): GuardrailState {
        val updated = ArrayDeque(recentSteps)
        updated.addLast(record)
        while (updated.size > maxRecentSteps) {
            updated.removeFirst()
        }
        return copy(recentSteps = updated)
    }

    fun isRecentProgress(step: Int, window: Int): Boolean {
        val progressStep = lastProgressStep ?: return false
        return step - progressStep <= window
    }
}

data class GuardrailConfig(
    val repeatClickBlockThreshold: Int = 3,
    val noChangeThreshold: Int = 3,
    val lowYieldFuseThreshold: Int = 4,
    val recentProgressBackBlockWindow: Int = 2,
    val backHomeBlockEscalationThreshold: Int = 2,
    val toggleRepeatSuppressionWindow: Int = 1,
    val maxRecentSteps: Int = 6
) {
    fun nearbyClickRadiusPx(observation: ObservationResult): Int {
        val captured = observation as? ObservationResult.Captured ?: return 48
        val shortEdge = minOf(captured.width, captured.height).coerceAtLeast(1)
        return maxOf(36, (shortEdge * 0.04f).toInt())
    }
}

internal fun CanonicalPoint.isNear(other: CanonicalPoint, radiusPx: Int): Boolean {
    return abs(x - other.x) <= radiusPx && abs(y - other.y) <= radiusPx
}
