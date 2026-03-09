package com.example.agenthost.automation

import android.media.ImageReader

sealed class ObservationResult {
    data class Captured(
        val base64Png: String,
        val width: Int,
        val height: Int
    ) : ObservationResult()

    data class Failed(
        val code: String,
        val message: String
    ) : ObservationResult()
}

data class ObservationRequest(
    val sessionId: String,
    val executionDisplayId: Int,
    val sessionTaskId: Int,
    val imageReader: ImageReader?
)

interface ObservationProvider {
    fun capture(request: ObservationRequest): ObservationResult
}
