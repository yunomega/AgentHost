package com.example.agenthost.overlay

import com.example.agenthost.automation.PlanningMode
import com.example.agenthost.config.ModelConfig
import com.example.agenthost.config.PlannerConfig

object OverlayModelPlannerConfig {
    internal const val DEFAULT_PROXY_ENDPOINT = "http://127.0.0.1:8080/plan"
    internal const val DEFAULT_PROXY_API_KEY = "dummy"
    internal const val DEFAULT_TIMEOUT_MS = 30_000L
    internal const val DEFAULT_MAX_RETRIES = 0
    internal const val DEFAULT_MAX_RESPONSE_CHARS = 4_096

    fun create(): PlannerConfig {
        return PlannerConfig(
            mode = PlanningMode.MODEL,
            modelConfig = ModelConfig(
                endpoint = DEFAULT_PROXY_ENDPOINT,
                apiKey = DEFAULT_PROXY_API_KEY,
                timeoutMs = DEFAULT_TIMEOUT_MS,
                maxRetries = DEFAULT_MAX_RETRIES,
                maxResponseChars = DEFAULT_MAX_RESPONSE_CHARS
            )
        )
    }
}
