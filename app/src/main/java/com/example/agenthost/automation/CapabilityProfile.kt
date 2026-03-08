package com.example.agenthost.automation

enum class TextSupportLevel {
    NONE,
    ASCII_BASIC,
    FULL
}

data class CapabilityProfile(
    val captureSupported: Boolean,
    val clickSupported: Boolean,
    val swipeSupported: Boolean,
    val longPressSupported: Boolean,
    val backSupported: Boolean,
    val homeSupported: Boolean,
    val textSupportLevel: TextSupportLevel,
    val askUserSupported: Boolean,
    val doneSupported: Boolean,
    val waitSupported: Boolean
) {
    fun supportedActions(): List<String> {
        val actions = mutableListOf<String>()
        if (clickSupported) actions += "CLICK"
        if (swipeSupported) actions += "SWIPE"
        if (longPressSupported) actions += "LONG_PRESS"
        if (textSupportLevel != TextSupportLevel.NONE) actions += "TYPE_TEXT"
        if (backSupported) actions += "BACK"
        if (homeSupported) actions += "HOME"
        if (waitSupported) actions += "WAIT"
        if (askUserSupported) actions += "ASK_USER"
        if (doneSupported) actions += "DONE"
        return actions
    }

    fun unsupportedActions(): List<String> {
        val actions = mutableListOf<String>()
        if (!clickSupported) actions += "CLICK"
        if (!swipeSupported) actions += "SWIPE"
        if (!longPressSupported) actions += "LONG_PRESS"
        if (textSupportLevel == TextSupportLevel.NONE) actions += "TYPE_TEXT"
        if (!backSupported) actions += "BACK"
        if (!homeSupported) actions += "HOME"
        if (!waitSupported) actions += "WAIT"
        if (!askUserSupported) actions += "ASK_USER"
        if (!doneSupported) actions += "DONE"
        return actions
    }

    fun summaryText(): String {
        return buildString {
            append("supported actions: ")
            append(supportedActions().joinToString(", "))
            append('\n')
            append("unsupported actions: ")
            append(unsupportedActions().joinToString(", "))
            append('\n')
            append("text support: ")
            append(
                when (textSupportLevel) {
                    TextSupportLevel.NONE -> "none"
                    TextSupportLevel.ASCII_BASIC -> "ascii_basic"
                    TextSupportLevel.FULL -> "full"
                }
            )
            append('\n')
            append("no default-display fallback")
            append('\n')
            append("all actions must target current session display only")
        }
    }

    companion object {
        fun phase3PlatformReady(platformAvailable: Boolean): CapabilityProfile {
            return if (platformAvailable) {
                CapabilityProfile(
                    captureSupported = true,
                    clickSupported = true,
                    swipeSupported = true,
                    longPressSupported = true,
                    backSupported = true,
                    homeSupported = false,
                    textSupportLevel = TextSupportLevel.ASCII_BASIC,
                    askUserSupported = true,
                    doneSupported = true,
                    waitSupported = true
                )
            } else {
                CapabilityProfile(
                    captureSupported = false,
                    clickSupported = false,
                    swipeSupported = false,
                    longPressSupported = false,
                    backSupported = false,
                    homeSupported = false,
                    textSupportLevel = TextSupportLevel.NONE,
                    askUserSupported = true,
                    doneSupported = true,
                    waitSupported = true
                )
            }
        }
    }
}

fun TextSupportLevel.supports(text: String): Boolean {
    return when (this) {
        TextSupportLevel.NONE -> text.isEmpty()
        TextSupportLevel.FULL -> true
        TextSupportLevel.ASCII_BASIC -> text.all { ch ->
            ch == '\n' || ch == '\r' || ch == '\t' || (ch.code in 0x20..0x7E)
        }
    }
}
