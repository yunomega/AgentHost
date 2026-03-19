package com.example.agenthost.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayPostCompletionRetentionTest {
    private var nowMs: Long = 0L
    private val retention = OverlayPostCompletionRetention(
        ttlMs = 3_000L,
        nowMs = { nowMs }
    )

    @Test
    fun completedUiIsRetainedAcrossNullUntilTtlExpires() {
        val completed = postCompletionUi()
        val idle = idleUi()

        assertEquals(completed, retention.resolve(sessionPresent = true, mapped = completed))
        assertEquals(completed, retention.resolve(sessionPresent = false, mapped = idle))

        nowMs = 3_100L

        assertEquals(idle, retention.resolve(sessionPresent = false, mapped = idle))
        assertNull(retention.remainingMs())
    }

    @Test
    fun dismissClearsRetainedCompletionImmediately() {
        val completed = postCompletionUi()
        val idle = idleUi()

        retention.resolve(sessionPresent = true, mapped = completed)
        retention.dismiss()

        assertEquals(idle, retention.resolve(sessionPresent = false, mapped = idle))
    }

    @Test
    fun newLiveSessionClearsRetainedCompletion() {
        val completed = postCompletionUi()
        val idle = idleUi()
        val running = runningUi()

        retention.resolve(sessionPresent = true, mapped = completed)
        assertEquals(completed, retention.resolve(sessionPresent = false, mapped = idle))

        assertEquals(running, retention.resolve(sessionPresent = true, mapped = running))
        assertEquals(idle, retention.resolve(sessionPresent = false, mapped = idle))
    }

    private fun postCompletionUi(): OverlayUiState {
        return OverlayUiState(
            mode = OverlayMode.POST_COMPLETION,
            sessionId = "session-1",
            bubbleText = "已完成",
            bubbleDetail = "已回主屏",
            bubbleTone = BubbleTone.IDLE
        )
    }

    private fun idleUi(): OverlayUiState {
        return OverlayUiState(
            mode = OverlayMode.IDLE,
            bubbleText = "空闲",
            bubbleDetail = "无活动会话",
            bubbleTone = BubbleTone.IDLE
        )
    }

    private fun runningUi(): OverlayUiState {
        return OverlayUiState(
            mode = OverlayMode.RUNNING,
            sessionId = "session-2",
            bubbleText = "执行中",
            bubbleDetail = "虚拟屏后台执行",
            bubbleTone = BubbleTone.RUNNING
        )
    }
}
