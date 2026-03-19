package com.example.agenthost.automation

enum class CompletionVerdict {
    SATISFIED,
    UNSURE,
    NOT_SATISFIED
}

data class CompletionInput(
    val task: String,
    val beforeObservation: ObservationResult,
    val afterObservation: ObservationResult?,
    val recentSteps: List<ControlStepRecord>,
    val lastAction: CanonicalAction,
    val guardrailState: GuardrailState,
    val subgoalState: SubgoalState,
    val previousSubgoalState: SubgoalState? = null,
    val modelRequestedDone: Boolean,
    val observationChanged: Boolean?
)

fun interface TaskTerminalCondition {
    fun evaluate(input: CompletionInput): CompletionVerdict
}

class CompositeCompletionEvaluator(
    private val evaluators: List<TaskTerminalCondition>
) : TaskTerminalCondition {
    override fun evaluate(input: CompletionInput): CompletionVerdict {
        evaluators.forEach { evaluator ->
            when (val verdict = evaluator.evaluate(input)) {
                CompletionVerdict.NOT_SATISFIED -> Unit
                else -> return verdict
            }
        }
        return CompletionVerdict.NOT_SATISFIED
    }
}

class DefaultCompletionEvaluator : TaskTerminalCondition {
    private val delegate = CompositeCompletionEvaluator(
        listOf(
            TaskTerminalCondition { input ->
                if (input.subgoalState.phase == SubgoalPhase.DONE_ONLY) {
                    CompletionVerdict.SATISFIED
                } else {
                    CompletionVerdict.NOT_SATISFIED
                }
            },
            TaskTerminalCondition { input ->
                if (input.subgoalState.phase == SubgoalPhase.TARGET_CLICKED_PENDING_SETTLE &&
                    input.lastAction.type == CanonicalActionType.CLICK &&
                    input.observationChanged == true
                ) {
                    CompletionVerdict.SATISFIED
                } else {
                    CompletionVerdict.NOT_SATISFIED
                }
            },
            TaskTerminalCondition { input ->
                if (input.modelRequestedDone) {
                    CompletionVerdict.UNSURE
                } else {
                    CompletionVerdict.NOT_SATISFIED
                }
            }
        )
    )

    override fun evaluate(input: CompletionInput): CompletionVerdict = delegate.evaluate(input)
}
