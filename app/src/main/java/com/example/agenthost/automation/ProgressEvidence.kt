package com.example.agenthost.automation

enum class RecentActionResult {
    SUCCESS,
    NO_CHANGE,
    BLOCKED,
    REJECTED,
    PLATFORM_FAILED,
    WAIT,
    DONE,
    ASK_USER
}

enum class GoalRelation {
    AT_TARGET,
    CLOSER,
    OFF_TRACK,
    UNKNOWN
}

data class SurfaceContext(
    val topPackage: String? = null,
    val topActivity: String? = null,
    val pageTitle: String? = null,
    val keywordHits: List<String> = emptyList()
) {
    fun normalizedTokens(): Set<String> {
        return buildSet {
            appendNormalized(topPackage)
            appendNormalized(topActivity)
            appendNormalized(pageTitle)
            keywordHits.forEach { appendNormalized(it) }
        }
    }

    private fun MutableSet<String>.appendNormalized(raw: String?) {
        raw?.lowercase()
            ?.replace(Regex("[^a-z0-9._/]+"), " ")
            ?.split(' ', '.', '/', '_')
            ?.map { it.trim() }
            ?.filter { it.length >= 2 }
            ?.forEach(::add)
    }
}

data class ObservationDeltaEvidence(
    val globalChanged: Boolean?,
    val localRoiChanged: Boolean,
    val surfaceChanged: Boolean
) {
    val effectiveChanged: Boolean?
        get() = if (localRoiChanged) true else globalChanged
}

data class ProgressEvidence(
    val observationDelta: ObservationDeltaEvidence,
    val surfaceContext: SurfaceContext,
    val recentActionResult: RecentActionResult,
    val recentActionCode: String,
    val recentActionMessage: String,
    val consecutiveNoChangeCount: Int,
    val consecutiveLowYieldCount: Int,
    val recentLowYield: Boolean,
    val recentNoProgress: Boolean,
    val recentBackIntent: Boolean,
    val clickSuppressed: Boolean,
    val repeatTargetPoint: CanonicalPoint? = null,
    val phaseProgressed: Boolean,
    val phaseRegressed: Boolean,
    val goalRelation: GoalRelation,
    val modelRequestedDone: Boolean,
    val needsUserConfirmation: Boolean = false
) {
    val observationChanged: Boolean?
        get() = observationDelta.effectiveChanged

    val strongProgressSignal: Boolean
        get() = observationDelta.localRoiChanged ||
            phaseProgressed ||
            goalRelation == GoalRelation.AT_TARGET

    val noProgressSignal: Boolean
        get() = recentNoProgress || (!strongProgressSignal && recentActionResult == RecentActionResult.NO_CHANGE)
}
