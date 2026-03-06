package com.example.agenthost.automation

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ShellDisplayActionExecutor : DisplayActionExecutor {
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 4_000L
    }

    private val capability: ExecutorCapabilities = detectCapabilities()

    override fun capabilities(): ExecutorCapabilities = capability

    override fun execute(vdDisplayId: Int, action: GuiAction): ActionExecutionResult {
        if (!capability.supported) {
            return ActionExecutionResult(
                ok = false,
                code = "EXECUTOR_UNSUPPORTED",
                details = capability.details
            )
        }

        val flag = capability.displayFlag ?: return ActionExecutionResult(
            ok = false,
            code = "EXECUTOR_UNSUPPORTED",
            details = "display flag is missing"
        )

        val args = actionToCommandArgs(flag, vdDisplayId, action)
            ?: return ActionExecutionResult(
                ok = false,
                code = "EXECUTOR_ACTION_UNSUPPORTED",
                details = "action=${action.actionType()}"
            )

        return runCommand(args)
    }

    private fun detectCapabilities(): ExecutorCapabilities {
        val probe = runCommand(listOf("input", "--help"), timeoutMs = 2_000L)
        val output = probe.details
        val flag = when {
            output.contains("--display") -> "--display"
            output.contains(" -d") || output.contains("\n-d") -> "-d"
            else -> null
        }

        return if (flag == null) {
            ExecutorCapabilities(
                supported = false,
                displayFlag = null,
                code = "EXECUTOR_UNSUPPORTED",
                details = if (probe.ok) {
                    "input command does not expose --display/-d"
                } else {
                    "failed to probe input command: ${probe.details}"
                }
            )
        } else {
            ExecutorCapabilities(
                supported = true,
                displayFlag = flag,
                code = "EXECUTOR_READY",
                details = "display flag=$flag"
            )
        }
    }

    private fun actionToCommandArgs(flag: String, vdDisplayId: Int, action: GuiAction): List<String>? {
        val display = vdDisplayId.toString()
        return when (action) {
            is GuiAction.Click -> listOf(
                "input", flag, display,
                "tap", action.x.toString(), action.y.toString()
            )

            is GuiAction.LongPress -> listOf(
                "input", flag, display,
                "swipe",
                action.x.toString(), action.y.toString(),
                action.x.toString(), action.y.toString(),
                action.durationMs.toString()
            )

            is GuiAction.Swipe -> listOf(
                "input", flag, display,
                "swipe",
                action.x1.toString(), action.y1.toString(),
                action.x2.toString(), action.y2.toString(),
                action.durationMs.toString()
            )

            is GuiAction.TypeText -> listOf(
                "input", flag, display,
                "text", encodeInputText(action.text)
            )

            GuiAction.Back -> listOf(
                "input", flag, display,
                "keyevent", "KEYCODE_BACK"
            )

            GuiAction.Home -> listOf(
                "input", flag, display,
                "keyevent", "KEYCODE_HOME"
            )

            is GuiAction.Wait,
            is GuiAction.AskUser,
            is GuiAction.Done -> null
        }
    }

    private fun encodeInputText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        return trimmed.replace(" ", "%s")
    }

    private fun runCommand(
        args: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ActionExecutionResult {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ActionExecutionResult(
                    ok = false,
                    code = "EXECUTOR_TIMEOUT",
                    details = "command timeout args=${args.joinToString(" ")}"
                )
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ActionExecutionResult(
                    ok = true,
                    code = "ACTION_EXECUTED",
                    details = output.trim().ifEmpty { "ok" }
                )
            } else {
                ActionExecutionResult(
                    ok = false,
                    code = "ACTION_EXECUTE_FAILED",
                    details = "exit=$exitCode output=${output.trim()}"
                )
            }
        } catch (t: Throwable) {
            ActionExecutionResult(
                ok = false,
                code = "ACTION_EXECUTE_EXCEPTION",
                details = t.message ?: "unknown"
            )
        }
    }
}
