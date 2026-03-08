package com.example.agenthost.model

import com.example.agenthost.automation.CapabilityProfile

data class ModelObservation(
    val status: String,
    val base64Png: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val code: String? = null,
    val message: String? = null
)

data class ModelActionHistoryEntry(
    val step: Int,
    val actionType: String,
    val code: String,
    val summary: String
)

data class ModelContext(
    val sessionId: String,
    val command: String,
    val vdDisplayId: Int,
    val sessionTaskId: Int,
    val step: Int,
    val capabilityProfile: CapabilityProfile,
    val capabilitySummary: String,
    val observation: ModelObservation,
    val recentActions: List<ModelActionHistoryEntry>,
    val lastFailureCode: String?,
    val lastUserReply: String?
)

data class ModelRequest(
    val context: ModelContext,
    val systemPrompt: String,
    val userPrompt: String,
    val maxResponseChars: Int
)

sealed class ModelResponse {
    data class Success(
        val outputText: String
    ) : ModelResponse()

    data class Failure(
        val code: String,
        val message: String,
        val rawResponseSnippet: String? = null
    ) : ModelResponse()
}

internal fun truncateForLogs(text: String?, maxChars: Int): String? {
    if (text == null) {
        return null
    }
    val safeLimit = maxChars.coerceAtLeast(32)
    return if (text.length <= safeLimit) {
        text
    } else {
        text.take(safeLimit) + "…"
    }
}

internal fun jsonEscape(text: String): String {
    val out = StringBuilder(text.length + 16)
    text.forEach { ch ->
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000c' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
    }
    return out.toString()
}
