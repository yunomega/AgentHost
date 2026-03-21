package com.example.agenthost.platform

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.example.agenthost.core.AgentHostLog
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

interface PlatformAutomationGateway {
    fun isAvailable(): Boolean

    fun captureDisplay(sessionId: String, displayId: Int): PlatformCaptureResponse

    fun injectTap(sessionId: String, displayId: Int, x: Int, y: Int): PlatformActionResponse

    fun injectSwipe(
        sessionId: String,
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long
    ): PlatformActionResponse

    fun injectKey(sessionId: String, displayId: Int, keyCode: Int): PlatformActionResponse

    fun injectText(sessionId: String, displayId: Int, text: String): PlatformActionResponse

    fun injectLongPress(
        sessionId: String,
        displayId: Int,
        x: Int,
        y: Int,
        durationMs: Long
    ): PlatformActionResponse
}

interface ExecutionDisplayPolicyGateway {
    fun registerExecutionDisplaySession(
        sessionId: String,
        executionDisplayId: Int
    ): ExecutionDisplayPolicyResponse

    fun updateExecutionDisplaySessionState(
        sessionId: String,
        executionDisplayId: Int,
        state: String,
        reason: String
    ): ExecutionDisplayPolicyResponse

    fun clearExecutionDisplaySession(
        sessionId: String,
        reason: String
    ): ExecutionDisplayPolicyResponse

    fun startExecutionDisplayStartupTarget(
        sessionId: String,
        executionDisplayId: Int,
        startupMode: String,
        componentName: String?,
        reason: String
    ): ExecutionDisplayStartupResponse
}

data class PlatformCaptureResponse(
    val ok: Boolean,
    val code: String,
    val details: String,
    val width: Int = 0,
    val height: Int = 0,
    val pngBytes: ByteArray? = null
)

data class PlatformActionResponse(
    val ok: Boolean,
    val code: String,
    val details: String
)

data class ExecutionDisplayPolicyResponse(
    val ok: Boolean,
    val code: String,
    val details: String
)

data class ExecutionDisplayStartupResponse(
    val ok: Boolean,
    val code: String,
    val details: String,
    val requestedDisplayId: Int = -1,
    val boundDisplayId: Int = -1,
    val taskId: Int = -1,
    val topActivity: String? = null,
    val topPackage: String? = null,
    val resolvedComponent: String? = null
)

object ExecutionDisplayStartupMode {
    const val SECONDARY_HOME = "SECONDARY_HOME"
    const val EXPLICIT_COMPONENT_ON_DISPLAY = "EXPLICIT_COMPONENT_ON_DISPLAY"
}

object PlatformResultCodes {
    const val OK = "OK"
    const val DISPLAY_NOT_FOUND = "DISPLAY_NOT_FOUND"
    const val CAPTURE_FAILED = "CAPTURE_FAILED"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val INJECTION_REJECTED = "INJECTION_REJECTED"
}

private object PlatformBundleKeys {
    const val CODE = "code"
    const val DETAILS = "details"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val PNG_FD = "png_fd"
    const val TASK_ID = "taskId"
    const val TOP_ACTIVITY = "topActivity"
    const val TOP_PACKAGE = "topPackage"
    const val REQUESTED_DISPLAY_ID = "requestedDisplayId"
    const val BOUND_DISPLAY_ID = "boundDisplayId"
    const val DISPLAY_ID = "displayId"
    const val RESOLVED_COMPONENT = "resolvedComponent"
}

class AgentHostPlatformClient(
    private val context: Context,
    private val captureTimeoutMs: Long = 4_000L,
    private val actionTimeoutMs: Long = 2_000L,
    private val binderExecutor: ExecutorService = Executors.newCachedThreadPool(),
    private val pipeReadTimeoutMs: Long = captureTimeoutMs
) : PlatformAutomationGateway, ExecutionDisplayPolicyGateway {
    companion object {
        private const val TAG = "AgentHostPlatformClient"
        private const val SERVICE_NAME = "agenthost_platform"
    }

    private val timedInputStreamReader = TimedInputStreamReader(binderExecutor)

    override fun isAvailable(): Boolean = resolveService() != null

    override fun captureDisplay(sessionId: String, displayId: Int): PlatformCaptureResponse {
        logBegin(sessionId, displayId, "CAPTURE")
        val service = resolveService() ?: return unavailableCapture(sessionId, displayId, "CAPTURE")
        return try {
            val callStartedAt = SystemClock.elapsedRealtime()
            val result = callWithTimeout(
                sessionId = sessionId,
                displayId = displayId,
                actionType = "CAPTURE",
                timeoutMs = captureTimeoutMs
            ) {
                service.captureDisplay(sessionId, displayId)
            }
            toCaptureResponse(
                sessionId = sessionId,
                displayId = displayId,
                actionType = "CAPTURE",
                result = result,
                bundleWaitMs = SystemClock.elapsedRealtime() - callStartedAt
            )
        } catch (_: TimeoutException) {
            failureCapture(
                sessionId,
                displayId,
                "CAPTURE",
                PlatformResultCodes.CAPTURE_FAILED,
                "platform call timeout after ${captureTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionCapture(sessionId, displayId, "CAPTURE", t)
        }
    }

    override fun registerExecutionDisplaySession(
        sessionId: String,
        executionDisplayId: Int
    ): ExecutionDisplayPolicyResponse {
        logPolicyBegin(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = "REGISTER_EXECUTION_DISPLAY_SESSION",
            extras = "state=EXECUTION"
        )
        val service = resolveService() ?: return unavailablePolicy(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = "REGISTER_EXECUTION_DISPLAY_SESSION"
        )
        return try {
            toExecutionDisplayPolicyResponse(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = "REGISTER_EXECUTION_DISPLAY_SESSION",
                result = callWithTimeout(
                    sessionId = sessionId,
                    displayId = executionDisplayId,
                    actionType = "REGISTER_EXECUTION_DISPLAY_SESSION",
                    timeoutMs = actionTimeoutMs
                ) {
                    service.registerExecutionDisplaySession(sessionId, executionDisplayId)
                }
            )
        } catch (_: TimeoutException) {
            failurePolicy(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = "REGISTER_EXECUTION_DISPLAY_SESSION",
                code = PlatformResultCodes.INTERNAL_ERROR,
                details = "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionPolicy(sessionId, executionDisplayId, "REGISTER_EXECUTION_DISPLAY_SESSION", t)
        }
    }

    override fun updateExecutionDisplaySessionState(
        sessionId: String,
        executionDisplayId: Int,
        state: String,
        reason: String
    ): ExecutionDisplayPolicyResponse {
        logPolicyBegin(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = "UPDATE_EXECUTION_DISPLAY_SESSION",
            extras = "state=$state reason=$reason"
        )
        val service = resolveService() ?: return unavailablePolicy(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = "UPDATE_EXECUTION_DISPLAY_SESSION"
        )
        return try {
            toExecutionDisplayPolicyResponse(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = "UPDATE_EXECUTION_DISPLAY_SESSION",
                result = callWithTimeout(
                    sessionId = sessionId,
                    displayId = executionDisplayId,
                    actionType = "UPDATE_EXECUTION_DISPLAY_SESSION",
                    timeoutMs = actionTimeoutMs
                ) {
                    service.updateExecutionDisplaySessionState(
                        sessionId,
                        executionDisplayId,
                        state,
                        reason
                    )
                }
            )
        } catch (_: TimeoutException) {
            failurePolicy(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = "UPDATE_EXECUTION_DISPLAY_SESSION",
                code = PlatformResultCodes.INTERNAL_ERROR,
                details = "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionPolicy(sessionId, executionDisplayId, "UPDATE_EXECUTION_DISPLAY_SESSION", t)
        }
    }

    override fun clearExecutionDisplaySession(
        sessionId: String,
        reason: String
    ): ExecutionDisplayPolicyResponse {
        logPolicyBegin(
            sessionId = sessionId,
            executionDisplayId = -1,
            actionType = "CLEAR_EXECUTION_DISPLAY_SESSION",
            extras = "reason=$reason"
        )
        val service = resolveService() ?: return unavailablePolicy(
            sessionId = sessionId,
            executionDisplayId = -1,
            actionType = "CLEAR_EXECUTION_DISPLAY_SESSION"
        )
        return try {
            toExecutionDisplayPolicyResponse(
                sessionId = sessionId,
                executionDisplayId = -1,
                actionType = "CLEAR_EXECUTION_DISPLAY_SESSION",
                result = callWithTimeout(
                    sessionId = sessionId,
                    displayId = -1,
                    actionType = "CLEAR_EXECUTION_DISPLAY_SESSION",
                    timeoutMs = actionTimeoutMs
                ) {
                    service.clearExecutionDisplaySession(sessionId, reason)
                }
            )
        } catch (_: TimeoutException) {
            failurePolicy(
                sessionId = sessionId,
                executionDisplayId = -1,
                actionType = "CLEAR_EXECUTION_DISPLAY_SESSION",
                code = PlatformResultCodes.INTERNAL_ERROR,
                details = "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionPolicy(sessionId, -1, "CLEAR_EXECUTION_DISPLAY_SESSION", t)
        }
    }

    override fun startExecutionDisplayStartupTarget(
        sessionId: String,
        executionDisplayId: Int,
        startupMode: String,
        componentName: String?,
        reason: String
    ): ExecutionDisplayStartupResponse {
        val safeComponentName = componentName ?: ""
        logPolicyBegin(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = "START_EXECUTION_DISPLAY_STARTUP_TARGET",
            extras = "startupMode=$startupMode componentName=$safeComponentName reason=$reason"
        )
        val service = resolveService() ?: return unavailableStartup(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = "START_EXECUTION_DISPLAY_STARTUP_TARGET"
        )
        return try {
            toExecutionDisplayStartupResponse(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = "START_EXECUTION_DISPLAY_STARTUP_TARGET",
                result = callWithTimeout(
                    sessionId = sessionId,
                    displayId = executionDisplayId,
                    actionType = "START_EXECUTION_DISPLAY_STARTUP_TARGET",
                    timeoutMs = actionTimeoutMs + 4_000L
                ) {
                    service.startExecutionDisplayStartupTarget(
                        sessionId,
                        executionDisplayId,
                        startupMode,
                        safeComponentName,
                        reason
                    )
                }
            )
        } catch (_: TimeoutException) {
            failureStartup(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = "START_EXECUTION_DISPLAY_STARTUP_TARGET",
                code = PlatformResultCodes.INTERNAL_ERROR,
                details = "platform call timeout after ${actionTimeoutMs + 4_000L}ms"
            )
        } catch (t: Throwable) {
            exceptionStartup(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = "START_EXECUTION_DISPLAY_STARTUP_TARGET",
                error = t
            )
        }
    }

    override fun injectTap(
        sessionId: String,
        displayId: Int,
        x: Int,
        y: Int
    ): PlatformActionResponse {
        logBegin(sessionId, displayId, "TAP")
        val service = resolveService() ?: return unavailableAction(sessionId, displayId, "TAP")
        return try {
            toActionResponse(
                sessionId,
                displayId,
                "TAP",
                callWithTimeout(sessionId, displayId, "TAP", actionTimeoutMs) {
                    service.injectTap(sessionId, displayId, x, y)
                }
            )
        } catch (_: TimeoutException) {
            failureAction(
                sessionId,
                displayId,
                "TAP",
                PlatformResultCodes.INTERNAL_ERROR,
                "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionAction(sessionId, displayId, "TAP", t)
        }
    }

    override fun injectSwipe(
        sessionId: String,
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long
    ): PlatformActionResponse {
        logBegin(sessionId, displayId, "SWIPE")
        val service = resolveService() ?: return unavailableAction(sessionId, displayId, "SWIPE")
        return try {
            toActionResponse(
                sessionId,
                displayId,
                "SWIPE",
                callWithTimeout(sessionId, displayId, "SWIPE", actionTimeoutMs) {
                    service.injectSwipe(sessionId, displayId, x1, y1, x2, y2, durationMs)
                }
            )
        } catch (_: TimeoutException) {
            failureAction(
                sessionId,
                displayId,
                "SWIPE",
                PlatformResultCodes.INTERNAL_ERROR,
                "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionAction(sessionId, displayId, "SWIPE", t)
        }
    }

    override fun injectKey(
        sessionId: String,
        displayId: Int,
        keyCode: Int
    ): PlatformActionResponse {
        logBegin(sessionId, displayId, "KEY")
        val service = resolveService() ?: return unavailableAction(sessionId, displayId, "KEY")
        return try {
            toActionResponse(
                sessionId,
                displayId,
                "KEY",
                callWithTimeout(sessionId, displayId, "KEY", actionTimeoutMs) {
                    service.injectKey(sessionId, displayId, keyCode)
                }
            )
        } catch (_: TimeoutException) {
            failureAction(
                sessionId,
                displayId,
                "KEY",
                PlatformResultCodes.INTERNAL_ERROR,
                "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionAction(sessionId, displayId, "KEY", t)
        }
    }

    override fun injectText(
        sessionId: String,
        displayId: Int,
        text: String
    ): PlatformActionResponse {
        logBegin(sessionId, displayId, "TEXT")
        val service = resolveService() ?: return unavailableAction(sessionId, displayId, "TEXT")
        return try {
            toActionResponse(
                sessionId,
                displayId,
                "TEXT",
                callWithTimeout(sessionId, displayId, "TEXT", actionTimeoutMs) {
                    service.injectText(sessionId, displayId, text)
                }
            )
        } catch (_: TimeoutException) {
            failureAction(
                sessionId,
                displayId,
                "TEXT",
                PlatformResultCodes.INTERNAL_ERROR,
                "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionAction(sessionId, displayId, "TEXT", t)
        }
    }

    override fun injectLongPress(
        sessionId: String,
        displayId: Int,
        x: Int,
        y: Int,
        durationMs: Long
    ): PlatformActionResponse {
        logBegin(sessionId, displayId, "LONG_PRESS")
        val service = resolveService() ?: return unavailableAction(sessionId, displayId, "LONG_PRESS")
        return try {
            toActionResponse(
                sessionId,
                displayId,
                "LONG_PRESS",
                callWithTimeout(sessionId, displayId, "LONG_PRESS", actionTimeoutMs) {
                    service.injectLongPress(sessionId, displayId, x, y, durationMs)
                }
            )
        } catch (_: TimeoutException) {
            failureAction(
                sessionId,
                displayId,
                "LONG_PRESS",
                PlatformResultCodes.INTERNAL_ERROR,
                "platform call timeout after ${actionTimeoutMs}ms"
            )
        } catch (t: Throwable) {
            exceptionAction(sessionId, displayId, "LONG_PRESS", t)
        }
    }

    private fun resolveService(): IAgentHostPlatformService? {
        val binder = context.getSystemService(SERVICE_NAME) as? IBinder ?: return null
        return IAgentHostPlatformService.Stub.asInterface(binder)
    }

    private fun toCaptureResponse(
        sessionId: String,
        displayId: Int,
        actionType: String,
        result: Bundle?,
        bundleWaitMs: Long
    ): PlatformCaptureResponse {
        if (result == null) {
            return failureCapture(sessionId, displayId, actionType, PlatformResultCodes.INTERNAL_ERROR, "null bundle")
        }
        val code = result.getString(PlatformBundleKeys.CODE).orEmpty().ifEmpty { PlatformResultCodes.INTERNAL_ERROR }
        val details = result.getString(PlatformBundleKeys.DETAILS).orEmpty().ifEmpty { "no details" }
        val width = result.getInt(PlatformBundleKeys.WIDTH, 0)
        val height = result.getInt(PlatformBundleKeys.HEIGHT, 0)
        @Suppress("DEPRECATION")
        val pngFd = result.getParcelable(PlatformBundleKeys.PNG_FD) as? ParcelFileDescriptor
        val pipeReadStartedAt = SystemClock.elapsedRealtime()
        val pngBytes = try {
            readBytesWithTimeout(pngFd, pipeReadTimeoutMs)
        } catch (_: TimeoutException) {
            val detail = buildString {
                append("png_fd read timeout after ")
                append(pipeReadTimeoutMs)
                append("ms")
            }
            return failureCapture(
                sessionId = sessionId,
                displayId = displayId,
                actionType = actionType,
                code = PlatformResultCodes.CAPTURE_FAILED,
                details = detail
            )
        } catch (_: InterruptedException) {
            return failureCapture(
                sessionId = sessionId,
                displayId = displayId,
                actionType = actionType,
                code = PlatformResultCodes.CAPTURE_FAILED,
                details = "png_fd read interrupted"
            )
        } catch (t: Throwable) {
            val detail = buildString {
                append("failed reading png_fd after ")
                append(SystemClock.elapsedRealtime() - pipeReadStartedAt)
                append("ms: ")
                append(t.message ?: t.javaClass.simpleName)
            }
            return failureCapture(
                sessionId = sessionId,
                displayId = displayId,
                actionType = actionType,
                code = PlatformResultCodes.INTERNAL_ERROR,
                details = detail
            )
        }
        val pipeReadMs = SystemClock.elapsedRealtime() - pipeReadStartedAt
        val ok = code == PlatformResultCodes.OK && pngBytes != null
        if (!ok) {
            val failureCode = if (code == PlatformResultCodes.OK) PlatformResultCodes.INTERNAL_ERROR else code
            val failureDetails = if (code == PlatformResultCodes.OK) "missing png_fd" else details
            return failureCapture(sessionId, displayId, actionType, failureCode, failureDetails)
        }
        logSuccess(
            sessionId = sessionId,
            displayId = displayId,
            actionType = actionType,
            extras = "bundleWaitMs=$bundleWaitMs pipeReadMs=$pipeReadMs width=$width " +
                "height=$height pngBytes=${pngBytes.size}"
        )
        return PlatformCaptureResponse(
            ok = true,
            code = code,
            details = details,
            width = width,
            height = height,
            pngBytes = pngBytes
        )
    }

    private fun toActionResponse(
        sessionId: String,
        displayId: Int,
        actionType: String,
        result: Bundle?
    ): PlatformActionResponse {
        if (result == null) {
            return failureAction(sessionId, displayId, actionType, PlatformResultCodes.INTERNAL_ERROR, "null bundle")
        }
        val code = result.getString(PlatformBundleKeys.CODE).orEmpty().ifEmpty { PlatformResultCodes.INTERNAL_ERROR }
        val details = result.getString(PlatformBundleKeys.DETAILS).orEmpty().ifEmpty { "no details" }
        val ok = code == PlatformResultCodes.OK
        if (!ok) {
            return failureAction(sessionId, displayId, actionType, code, details)
        }
        return PlatformActionResponse(ok = true, code = code, details = details)
    }

    private fun toExecutionDisplayPolicyResponse(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        result: Bundle?
    ): ExecutionDisplayPolicyResponse {
        if (result == null) {
            return failurePolicy(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = actionType,
                code = PlatformResultCodes.INTERNAL_ERROR,
                details = "null bundle"
            )
        }
        val code = result.getString(PlatformBundleKeys.CODE).orEmpty()
            .ifEmpty { PlatformResultCodes.INTERNAL_ERROR }
        val details = result.getString(PlatformBundleKeys.DETAILS).orEmpty()
            .ifEmpty { "no details" }
        val ok = isExecutionDisplayPolicySuccess(code)
        if (!ok) {
            return failurePolicy(sessionId, executionDisplayId, actionType, code, details)
        }
        logPolicySuccess(sessionId, executionDisplayId, actionType, "details=$details code=$code")
        return ExecutionDisplayPolicyResponse(ok = true, code = code, details = details)
    }

    private fun toExecutionDisplayStartupResponse(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        result: Bundle?
    ): ExecutionDisplayStartupResponse {
        if (result == null) {
            return failureStartup(
                sessionId = sessionId,
                executionDisplayId = executionDisplayId,
                actionType = actionType,
                code = PlatformResultCodes.INTERNAL_ERROR,
                details = "null bundle"
            )
        }
        val code = result.getString(PlatformBundleKeys.CODE).orEmpty()
            .ifEmpty { PlatformResultCodes.INTERNAL_ERROR }
        val details = result.getString(PlatformBundleKeys.DETAILS).orEmpty()
            .ifEmpty { "no details" }
        val requestedDisplayId = result.getInt(
            PlatformBundleKeys.REQUESTED_DISPLAY_ID,
            result.getInt(PlatformBundleKeys.DISPLAY_ID, executionDisplayId)
        )
        val boundDisplayId = result.getInt(PlatformBundleKeys.BOUND_DISPLAY_ID, -1)
        val taskId = result.getInt(PlatformBundleKeys.TASK_ID, -1)
        val topActivity = result.getString(PlatformBundleKeys.TOP_ACTIVITY)
        val topPackage = result.getString(PlatformBundleKeys.TOP_PACKAGE)
        val resolvedComponent = result.getString(PlatformBundleKeys.RESOLVED_COMPONENT)
        if (code != "EXECUTION_DISPLAY_STARTUP_OK") {
            return failureStartup(
                sessionId = sessionId,
                executionDisplayId = requestedDisplayId,
                actionType = actionType,
                code = code,
                details = details,
                requestedDisplayId = requestedDisplayId,
                boundDisplayId = boundDisplayId,
                taskId = taskId,
                topActivity = topActivity,
                topPackage = topPackage,
                resolvedComponent = resolvedComponent
            )
        }
        logPolicySuccess(
            sessionId,
            requestedDisplayId,
            actionType,
            "details=$details code=$code requestedDisplayId=$requestedDisplayId " +
                "boundDisplayId=$boundDisplayId taskId=$taskId resolvedComponent=$resolvedComponent"
        )
        return ExecutionDisplayStartupResponse(
            ok = true,
            code = code,
            details = details,
            requestedDisplayId = requestedDisplayId,
            boundDisplayId = boundDisplayId,
            taskId = taskId,
            topActivity = topActivity,
            topPackage = topPackage,
            resolvedComponent = resolvedComponent
        )
    }

    private fun unavailableCapture(
        sessionId: String,
        displayId: Int,
        actionType: String
    ): PlatformCaptureResponse {
        return failureCapture(
            sessionId,
            displayId,
            actionType,
            PlatformResultCodes.UNSUPPORTED,
            "agenthost_platform service unavailable"
        )
    }

    private fun unavailableAction(
        sessionId: String,
        displayId: Int,
        actionType: String
    ): PlatformActionResponse {
        return failureAction(
            sessionId,
            displayId,
            actionType,
            PlatformResultCodes.UNSUPPORTED,
            "agenthost_platform service unavailable"
        )
    }

    private fun unavailablePolicy(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String
    ): ExecutionDisplayPolicyResponse {
        return failurePolicy(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = actionType,
            code = PlatformResultCodes.UNSUPPORTED,
            details = "agenthost_platform service unavailable"
        )
    }

    private fun unavailableStartup(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String
    ): ExecutionDisplayStartupResponse {
        return failureStartup(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = actionType,
            code = PlatformResultCodes.UNSUPPORTED,
            details = "agenthost_platform service unavailable"
        )
    }

    private fun exceptionCapture(
        sessionId: String,
        displayId: Int,
        actionType: String,
        error: Throwable
    ): PlatformCaptureResponse {
        val details = error.message ?: error.javaClass.simpleName
        return failureCapture(sessionId, displayId, actionType, PlatformResultCodes.INTERNAL_ERROR, details)
    }

    private fun exceptionAction(
        sessionId: String,
        displayId: Int,
        actionType: String,
        error: Throwable
    ): PlatformActionResponse {
        val details = error.message ?: error.javaClass.simpleName
        return failureAction(sessionId, displayId, actionType, PlatformResultCodes.INTERNAL_ERROR, details)
    }

    private fun exceptionPolicy(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        error: Throwable
    ): ExecutionDisplayPolicyResponse {
        val details = error.message ?: error.javaClass.simpleName
        return failurePolicy(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = actionType,
            code = PlatformResultCodes.INTERNAL_ERROR,
            details = details
        )
    }

    private fun exceptionStartup(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        error: Throwable
    ): ExecutionDisplayStartupResponse {
        return failureStartup(
            sessionId = sessionId,
            executionDisplayId = executionDisplayId,
            actionType = actionType,
            code = PlatformResultCodes.INTERNAL_ERROR,
            details = error.message ?: error.javaClass.simpleName
        )
    }

    private fun failureCapture(
        sessionId: String,
        displayId: Int,
        actionType: String,
        code: String,
        details: String
    ): PlatformCaptureResponse {
        logFailed(sessionId, displayId, actionType, code, details)
        return PlatformCaptureResponse(ok = false, code = code, details = details)
    }

    private fun failureAction(
        sessionId: String,
        displayId: Int,
        actionType: String,
        code: String,
        details: String
    ): PlatformActionResponse {
        logFailed(sessionId, displayId, actionType, code, details)
        return PlatformActionResponse(ok = false, code = code, details = details)
    }

    private fun failurePolicy(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        code: String,
        details: String
    ): ExecutionDisplayPolicyResponse {
        logPolicyFailed(sessionId, executionDisplayId, actionType, code, details)
        return ExecutionDisplayPolicyResponse(ok = false, code = code, details = details)
    }

    private fun failureStartup(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        code: String,
        details: String,
        requestedDisplayId: Int = executionDisplayId,
        boundDisplayId: Int = -1,
        taskId: Int = -1,
        topActivity: String? = null,
        topPackage: String? = null,
        resolvedComponent: String? = null
    ): ExecutionDisplayStartupResponse {
        logPolicyFailed(sessionId, executionDisplayId, actionType, code, details)
        return ExecutionDisplayStartupResponse(
            ok = false,
            code = code,
            details = details,
            requestedDisplayId = requestedDisplayId,
            boundDisplayId = boundDisplayId,
            taskId = taskId,
            topActivity = topActivity,
            topPackage = topPackage,
            resolvedComponent = resolvedComponent
        )
    }

    private fun readBytesWithTimeout(
        parcelFileDescriptor: ParcelFileDescriptor?,
        timeoutMs: Long
    ): ByteArray? {
        val fd = parcelFileDescriptor ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            timedInputStreamReader.readAll(input, timeoutMs)
        }
    }

    private fun <T> callWithTimeout(
        sessionId: String,
        displayId: Int,
        actionType: String,
        timeoutMs: Long,
        block: () -> T
    ): T {
        val future: Future<T> = binderExecutor.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            logTimeout(sessionId, displayId, actionType, timeoutMs)
            throw e
        }
    }

    private fun logBegin(sessionId: String, displayId: Int, actionType: String) {
        AgentHostLog.i(
            TAG,
            "event=platform_call_begin sessionId=$sessionId executionDisplayId=$displayId " +
                "${displayAliasToken(actionType, displayId)} " +
                "sessionTaskId=-1 step=0 actionType=$actionType code=CALL_BEGIN extras={}"
        )
    }

    private fun logFailed(
        sessionId: String,
        displayId: Int,
        actionType: String,
        code: String,
        details: String
    ) {
        val message = "event=platform_call_failed sessionId=$sessionId executionDisplayId=$displayId " +
            "${displayAliasToken(actionType, displayId)} " +
            "sessionTaskId=-1 step=0 actionType=$actionType code=$code extras=details=$details"
        if (code == PlatformResultCodes.PERMISSION_DENIED || code == PlatformResultCodes.INTERNAL_ERROR) {
            AgentHostLog.e(TAG, message)
        } else {
            AgentHostLog.w(TAG, message)
        }
    }

    private fun logTimeout(sessionId: String, displayId: Int, actionType: String, timeoutMs: Long) {
        AgentHostLog.e(
            TAG,
            "event=platform_call_timeout sessionId=$sessionId executionDisplayId=$displayId " +
                "${displayAliasToken(actionType, displayId)} " +
                "sessionTaskId=-1 step=0 actionType=$actionType code=CALL_TIMEOUT " +
                "extras=timeoutMs=$timeoutMs"
        )
    }

    private fun logSuccess(sessionId: String, displayId: Int, actionType: String, extras: String) {
        AgentHostLog.i(
            TAG,
            "event=platform_call_success sessionId=$sessionId executionDisplayId=$displayId " +
                "${displayAliasToken(actionType, displayId)} " +
                "sessionTaskId=-1 step=0 actionType=$actionType code=CALL_OK extras=$extras"
        )
    }

    private fun logPolicyBegin(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        extras: String
    ) {
        AgentHostLog.i(
            TAG,
            "event=platform_policy_call_begin sessionId=$sessionId " +
                "executionDisplayId=$executionDisplayId sessionTaskId=-1 step=0 " +
                "actionType=$actionType code=CALL_BEGIN extras=$extras"
        )
    }

    private fun logPolicySuccess(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        extras: String
    ) {
        AgentHostLog.i(
            TAG,
            "event=platform_policy_call_success sessionId=$sessionId " +
                "executionDisplayId=$executionDisplayId sessionTaskId=-1 step=0 " +
                "actionType=$actionType code=CALL_OK extras=$extras"
        )
    }

    private fun logPolicyFailed(
        sessionId: String,
        executionDisplayId: Int,
        actionType: String,
        code: String,
        details: String
    ) {
        val message = "event=platform_policy_call_failed sessionId=$sessionId " +
            "executionDisplayId=$executionDisplayId sessionTaskId=-1 step=0 " +
            "actionType=$actionType code=$code extras=details=$details"
        if (code == PlatformResultCodes.PERMISSION_DENIED || code == PlatformResultCodes.INTERNAL_ERROR) {
            AgentHostLog.e(TAG, message)
        } else {
            AgentHostLog.w(TAG, message)
        }
    }

    private fun isExecutionDisplayPolicySuccess(code: String): Boolean {
        return code == "EXECUTION_SESSION_REGISTERED" ||
            code == "EXECUTION_SESSION_UPDATED" ||
            code == "EXECUTION_SESSION_UNCHANGED" ||
            code == "EXECUTION_SESSION_CLEARED" ||
            code == "EXECUTION_SESSION_ALREADY_CLEARED"
    }

    private fun displayAliasToken(actionType: String, displayId: Int): String {
        return if (actionType == "CAPTURE") {
            "captureDisplayId=$displayId"
        } else {
            "injectDisplayId=$displayId"
        }
    }
}
