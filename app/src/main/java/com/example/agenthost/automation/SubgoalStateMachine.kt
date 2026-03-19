package com.example.agenthost.automation

enum class SubgoalPhase {
    GENERAL_NAVIGATION,
    NEED_OPEN_PARENT,
    NEED_CLICK_TARGET,
    TARGET_CLICKED_PENDING_SETTLE,
    DONE_ONLY
}

data class SubgoalSpec(
    val parentHint: String?,
    val targetHint: String?
)

data class SubgoalState(
    val spec: SubgoalSpec?,
    val phase: SubgoalPhase
)

class SubgoalStateMachine {
    fun initialState(command: String): SubgoalState {
        val spec = extractSpec(command)
        return if (spec == null) {
            SubgoalState(spec = null, phase = SubgoalPhase.GENERAL_NAVIGATION)
        } else {
            SubgoalState(spec = spec, phase = SubgoalPhase.NEED_OPEN_PARENT)
        }
    }

    fun extractSpec(command: String): SubgoalSpec? {
        val text = command.trim()
        if (text.isBlank()) {
            return null
        }
        val targetMatch = SECOND_CLICK_PATTERNS.asSequence()
            .mapNotNull { it.find(text) }
            .firstOrNull()
            ?: return null
        val targetHint = sanitizeHint(targetMatch.groups[1]?.value)
        if (targetHint.isNullOrBlank()) {
            return null
        }
        val parentHint = sanitizeHint(text.substring(0, targetMatch.range.first))
            ?.replace(PARENT_SUFFIX_REGEX, "")
            ?.trim()
            ?.ifBlank { null }
        return SubgoalSpec(parentHint = parentHint, targetHint = targetHint)
    }

    fun advancePhase(
        state: SubgoalState,
        action: CanonicalAction,
        observationChanged: Boolean?
    ): SubgoalState {
        return when (state.phase) {
            SubgoalPhase.GENERAL_NAVIGATION -> state

            SubgoalPhase.NEED_OPEN_PARENT -> {
                if (action.type == CanonicalActionType.CLICK && observationChanged == true) {
                    state.copy(phase = SubgoalPhase.NEED_CLICK_TARGET)
                } else {
                    state
                }
            }

            SubgoalPhase.NEED_CLICK_TARGET -> {
                if (action.type == CanonicalActionType.CLICK && observationChanged == true) {
                    state.copy(phase = SubgoalPhase.TARGET_CLICKED_PENDING_SETTLE)
                } else {
                    state
                }
            }

            SubgoalPhase.TARGET_CLICKED_PENDING_SETTLE -> {
                when {
                    observationChanged == false -> state.copy(phase = SubgoalPhase.NEED_CLICK_TARGET)
                    else -> state
                }
            }

            SubgoalPhase.DONE_ONLY -> state
        }
    }

    fun finalizePhase(
        candidateState: SubgoalState,
        completionVerdict: CompletionVerdict
    ): SubgoalState {
        return if (completionVerdict == CompletionVerdict.SATISFIED) {
            candidateState.copy(phase = SubgoalPhase.DONE_ONLY)
        } else {
            candidateState
        }
    }

    fun transition(
        state: SubgoalState,
        action: CanonicalAction,
        observationChanged: Boolean?,
        completionVerdict: CompletionVerdict
    ): SubgoalState {
        val candidateState = advancePhase(
            state = state,
            action = action,
            observationChanged = observationChanged
        )
        return finalizePhase(candidateState, completionVerdict)
    }

    fun allowedActions(
        phase: SubgoalPhase,
        capabilityProfile: CapabilityProfile
    ): List<String> {
        val base = capabilityProfile.supportedActions().toMutableSet()
        when (phase) {
            SubgoalPhase.GENERAL_NAVIGATION -> Unit
            SubgoalPhase.NEED_OPEN_PARENT -> {
                base.remove("BACK")
                base.remove("HOME")
            }
            SubgoalPhase.NEED_CLICK_TARGET -> {
                base.remove("BACK")
                base.remove("HOME")
            }
            SubgoalPhase.TARGET_CLICKED_PENDING_SETTLE -> {
                base.retainAll(setOf("DONE", "ASK_USER", "WAIT"))
            }
            SubgoalPhase.DONE_ONLY -> {
                base.retainAll(setOf("DONE", "ASK_USER"))
            }
        }
        return base.toList().sorted()
    }

    fun blockedActions(
        phase: SubgoalPhase,
        capabilityProfile: CapabilityProfile
    ): List<String> {
        val supported = capabilityProfile.supportedActions().toSet()
        val allowed = allowedActions(phase, capabilityProfile).toSet()
        return supported.minus(allowed).toList().sorted()
    }

    private fun sanitizeHint(raw: String?): String? {
        return raw
            ?.trim()
            ?.trim('"', '\'', '`', ' ', '\t', '\n', '\r')
            ?.trimEnd('。', '，', ',', ';', '；', ':', '：')
            ?.trim()
            ?.ifBlank { null }
            ?.take(MAX_HINT_LENGTH)
    }

    private companion object {
        private const val MAX_HINT_LENGTH = 120
        private val PARENT_SUFFIX_REGEX = Regex("""(?:然后|再|并且|接着)\s*$""")
        private val SECOND_CLICK_PATTERNS = listOf(
            Regex("""(?:点开后|打开后|进入后|进去后|然后|再)\s*点击(?:一次)?\s*[\"“]?([^，。,；;\n\"”]+)"""),
            Regex("""(?:点开后|打开后|进入后|进去后|然后|再)\s*点(?:一下|一下一次|一次)?\s*[\"“]?([^，。,；;\n\"”]+)"""),
            Regex("""(?:then|after that|afterwards)\s+click\s+[\"“]?([^,\.;\n\"”]+)""", RegexOption.IGNORE_CASE)
        )
    }
}
