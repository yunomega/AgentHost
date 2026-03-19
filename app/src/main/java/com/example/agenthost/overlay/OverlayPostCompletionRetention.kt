package com.example.agenthost.overlay

internal class OverlayPostCompletionRetention(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long
) {
    companion object {
        const val DEFAULT_TTL_MS = 3_000L
    }

    private var retainedState: OverlayUiState? = null
    private var retainedUntilMs: Long = 0L

    fun resolve(sessionPresent: Boolean, mapped: OverlayUiState): OverlayUiState {
        if (sessionPresent && mapped.mode == OverlayMode.POST_COMPLETION) {
            retainedState = mapped
            retainedUntilMs = nowMs() + ttlMs
            return mapped
        }
        if (sessionPresent) {
            clear()
            return mapped
        }
        expireIfNeeded()
        return retainedState ?: mapped
    }

    fun remainingMs(): Long? {
        expireIfNeeded()
        retainedState ?: return null
        return (retainedUntilMs - nowMs()).coerceAtLeast(0L)
    }

    fun dismiss() {
        clear()
    }

    fun expireIfNeeded(): Boolean {
        if (retainedState == null) {
            return false
        }
        if (nowMs() < retainedUntilMs) {
            return false
        }
        clear()
        return true
    }

    private fun clear() {
        retainedState = null
        retainedUntilMs = 0L
    }
}
