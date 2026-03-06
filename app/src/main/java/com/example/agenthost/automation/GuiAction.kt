package com.example.agenthost.automation

sealed class GuiAction {
    data class Click(val x: Int, val y: Int) : GuiAction()

    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long
    ) : GuiAction()

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long
    ) : GuiAction()

    data class TypeText(val text: String) : GuiAction()

    data object Back : GuiAction()

    data object Home : GuiAction()

    data class Wait(val durationMs: Long) : GuiAction()

    data class AskUser(val question: String) : GuiAction()

    data class Done(val reason: String) : GuiAction()
}

fun GuiAction.actionType(): String {
    return when (this) {
        is GuiAction.Click -> "CLICK"
        is GuiAction.LongPress -> "LONG_PRESS"
        is GuiAction.Swipe -> "SWIPE"
        is GuiAction.TypeText -> "TYPE_TEXT"
        GuiAction.Back -> "BACK"
        GuiAction.Home -> "HOME"
        is GuiAction.Wait -> "WAIT"
        is GuiAction.AskUser -> "ASK_USER"
        is GuiAction.Done -> "DONE"
    }
}
