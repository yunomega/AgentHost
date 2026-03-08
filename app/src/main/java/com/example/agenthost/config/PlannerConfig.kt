package com.example.agenthost.config

import com.example.agenthost.automation.PlanningMode

data class PlannerConfig(
    val mode: PlanningMode = PlanningMode.SCRIPTED,
    val modelConfig: ModelConfig = ModelConfig()
)

data class ModelConfig(
    val endpoint: String? = null,
    val apiKey: String? = null,
    val apiKeyEnvName: String? = null,
    val timeoutMs: Long = 10_000L,
    val maxRetries: Int = 0,
    val maxResponseChars: Int = 4_096,
    val mockResponse: String? = null
) {
    fun resolveApiKey(): String? {
        val direct = apiKey?.trim().orEmpty()
        if (direct.isNotEmpty()) {
            return direct
        }
        val envName = apiKeyEnvName?.trim().orEmpty()
        if (envName.isEmpty()) {
            return null
        }
        val envValue = System.getenv(envName)?.trim().orEmpty()
        if (envValue.isNotEmpty()) {
            return envValue
        }
        val propertyValue = System.getProperty(envName)?.trim().orEmpty()
        if (propertyValue.isNotEmpty()) {
            return propertyValue
        }
        return null
    }

    fun hasEndpoint(): Boolean = !endpoint.isNullOrBlank()

    fun hasResolvedApiKey(): Boolean = !resolveApiKey().isNullOrBlank()

    fun sanitizedSummary(): String {
        return "endpointConfigured=${hasEndpoint()} apiKeyConfigured=${hasResolvedApiKey()} " +
            "timeoutMs=$timeoutMs maxRetries=$maxRetries maxResponseChars=$maxResponseChars " +
            "mockResponseConfigured=${!mockResponse.isNullOrBlank()}"
    }
}
