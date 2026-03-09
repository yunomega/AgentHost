package com.example.agenthost.model

import com.example.agenthost.automation.JsonLite
import com.example.agenthost.automation.JsonLiteValue
import com.example.agenthost.automation.asObjectOrNull
import com.example.agenthost.automation.optionalString
import com.example.agenthost.config.ModelConfig
import com.example.agenthost.core.AgentHostLog
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class HttpModelClient(
    private val config: ModelConfig
) : ModelClient {
    companion object {
        private const val TAG = "HttpModelClient"
    }

    override fun complete(request: ModelRequest): ModelResponse {
        val endpoint = config.endpoint?.trim().orEmpty()
        if (endpoint.isEmpty()) {
            return ModelResponse.Failure(
                code = "MODEL_UNAVAILABLE",
                message = "model endpoint not configured"
            )
        }
        val apiKey = config.resolveApiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            return ModelResponse.Failure(
                code = "MODEL_UNAVAILABLE",
                message = "model api key not configured"
            )
        }

        val attempts = config.maxRetries.coerceAtLeast(0) + 1
        var lastFailure: ModelResponse.Failure? = null
        repeat(attempts) { attemptIndex ->
            val attempt = attemptIndex + 1
            val response = try {
                executeOnce(endpoint, apiKey, request)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                ModelResponse.Failure(
                    code = "MODEL_TIMEOUT",
                    message = "model request interrupted"
                )
            }
            if (response is ModelResponse.Success) {
                return response
            }
            lastFailure = response as ModelResponse.Failure
            AgentHostLog.w(
                TAG,
                "event=model_request_failed sessionId=${request.context.sessionId} " +
                    "executionDisplayId=${request.context.vdDisplayId} sessionTaskId=${request.context.sessionTaskId} " +
                    "step=${request.context.step} plannerMode=CLIENT actionType=NONE code=${lastFailure?.code} " +
                    "extras=attempt=$attempt totalAttempts=$attempts details=${lastFailure?.message}"
            )
            val shouldRetry = lastFailure?.code == "MODEL_TIMEOUT" || lastFailure?.code == "MODEL_UNAVAILABLE"
            if (!shouldRetry) {
                return lastFailure as ModelResponse.Failure
            }
        }
        return lastFailure ?: ModelResponse.Failure(
            code = "MODEL_UNAVAILABLE",
            message = "unknown model client failure"
        )
    }

    private fun executeOnce(endpoint: String, apiKey: String, request: ModelRequest): ModelResponse {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs.toInt()
            readTimeout = config.timeoutMs.toInt()
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val body = buildRequestBody(request)
        return try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val rawBody = readBody(connection, status)
            parseResponse(status, rawBody, request.maxResponseChars)
        } catch (t: Throwable) {
            when (t) {
                is SocketTimeoutException,
                is InterruptedIOException -> ModelResponse.Failure(
                    code = "MODEL_TIMEOUT",
                    message = t.message ?: "model request timeout"
                )

                is IOException -> ModelResponse.Failure(
                    code = "MODEL_UNAVAILABLE",
                    message = t.message ?: "model request failed"
                )

                else -> throw t
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun parseResponse(status: Int, rawBody: String, maxResponseChars: Int): ModelResponse {
        val parsed = try {
            JsonLite.parse(rawBody)
        } catch (t: Throwable) {
            return ModelResponse.Failure(
                code = if (status in 200..299) "MODEL_OUTPUT_INVALID" else "MODEL_UNAVAILABLE",
                message = t.message ?: "invalid gateway response",
                rawResponseSnippet = truncateForLogs(rawBody, maxResponseChars)
            )
        }

        val obj = parsed.asObjectOrNull()
            ?: return ModelResponse.Failure(
                code = if (status in 200..299) "MODEL_OUTPUT_INVALID" else "MODEL_UNAVAILABLE",
                message = "gateway response must be a JSON object",
                rawResponseSnippet = truncateForLogs(rawBody, maxResponseChars)
            )

        val code = obj.optionalString("code").trim().uppercase()
        val message = obj.optionalString("message").ifBlank { obj.optionalString("details") }
        val refusal = (obj.fields["refusal"] as? JsonLiteValue.Bool)?.value == true || code == "REFUSED"
        if (refusal) {
            return ModelResponse.Failure(
                code = "MODEL_REFUSED",
                message = if (message.isBlank()) "model refused request" else message,
                rawResponseSnippet = truncateForLogs(rawBody, maxResponseChars)
            )
        }

        val outputText = obj.optionalString("outputText").ifBlank { obj.optionalString("text") }
        if (status !in 200..299) {
            return ModelResponse.Failure(
                code = "MODEL_UNAVAILABLE",
                message = if (message.isBlank()) "gateway http_status=$status" else message,
                rawResponseSnippet = truncateForLogs(rawBody, maxResponseChars)
            )
        }
        if (outputText.isBlank()) {
            return ModelResponse.Failure(
                code = "MODEL_OUTPUT_INVALID",
                message = "gateway response missing outputText",
                rawResponseSnippet = truncateForLogs(rawBody, maxResponseChars)
            )
        }
        return ModelResponse.Success(outputText = outputText)
    }

    private fun buildRequestBody(request: ModelRequest): String {
        val context = request.context
        val observation = context.observation
        val recentActions = context.recentActions.joinToString(separator = ",") { action ->
            "{" +
                "\"step\":${action.step}," +
                "\"actionType\":\"${jsonEscape(action.actionType)}\"," +
                "\"code\":\"${jsonEscape(action.code)}\"," +
                "\"summary\":\"${jsonEscape(action.summary)}\"" +
                "}"
        }
        val capability = context.capabilityProfile
        return buildString {
            append("{")
            append("\"sessionId\":\"").append(jsonEscape(context.sessionId)).append("\",")
            append("\"step\":").append(context.step).append(',')
            append("\"maxResponseChars\":").append(request.maxResponseChars).append(',')
            append("\"systemPrompt\":\"").append(jsonEscape(request.systemPrompt)).append("\",")
            append("\"userPrompt\":\"").append(jsonEscape(request.userPrompt)).append("\",")
            append("\"context\":{")
            append("\"sessionId\":\"").append(jsonEscape(context.sessionId)).append("\",")
            append("\"command\":\"").append(jsonEscape(context.command)).append("\",")
            append("\"vdDisplayId\":").append(context.vdDisplayId).append(',')
            append("\"sessionTaskId\":").append(context.sessionTaskId).append(',')
            append("\"step\":").append(context.step).append(',')
            append("\"capabilitySummary\":\"").append(jsonEscape(context.capabilitySummary)).append("\",")
            append("\"capabilityProfile\":{")
            append("\"captureSupported\":${capability.captureSupported},")
            append("\"clickSupported\":${capability.clickSupported},")
            append("\"swipeSupported\":${capability.swipeSupported},")
            append("\"longPressSupported\":${capability.longPressSupported},")
            append("\"backSupported\":${capability.backSupported},")
            append("\"homeSupported\":${capability.homeSupported},")
            append("\"textSupportLevel\":\"${capability.textSupportLevel.name}\",")
            append("\"askUserSupported\":${capability.askUserSupported},")
            append("\"doneSupported\":${capability.doneSupported},")
            append("\"waitSupported\":${capability.waitSupported}")
            append("},")
            append("\"observation\":{")
            append("\"status\":\"").append(jsonEscape(observation.status)).append("\",")
            append("\"base64Png\":")
            if (observation.base64Png == null) append("null") else append("\"").append(jsonEscape(observation.base64Png)).append("\"")
            append(',')
            append("\"width\":").append(observation.width ?: -1).append(',')
            append("\"height\":").append(observation.height ?: -1).append(',')
            append("\"code\":")
            if (observation.code == null) append("null") else append("\"").append(jsonEscape(observation.code)).append("\"")
            append(',')
            append("\"message\":")
            if (observation.message == null) append("null") else append("\"").append(jsonEscape(observation.message)).append("\"")
            append("},")
            append("\"recentActions\":[").append(recentActions).append("],")
            append("\"lastFailureCode\":")
            if (context.lastFailureCode == null) append("null") else append("\"").append(jsonEscape(context.lastFailureCode)).append("\"")
            append(',')
            append("\"lastUserReply\":")
            if (context.lastUserReply == null) append("null") else append("\"").append(jsonEscape(context.lastUserReply)).append("\"")
            append("}")
            append("}")
        }
    }
}
