package com.example.agenthost.automation

enum class CanonicalActionType {
    CLICK,
    LONG_PRESS,
    SWIPE,
    TYPE_TEXT,
    BACK,
    HOME,
    WAIT,
    ASK_USER,
    DONE
}

enum class CanonicalCoordSpace {
    NONE,
    IMAGE_PIXEL,
    IMAGE_PERMILLE
}

data class CanonicalPoint(
    val x: Int,
    val y: Int
)

data class CanonicalAction(
    val type: CanonicalActionType,
    val coordSpace: CanonicalCoordSpace = CanonicalCoordSpace.NONE,
    val point: CanonicalPoint? = null,
    val points: List<CanonicalPoint> = emptyList(),
    val durationMs: Long? = null,
    val text: String? = null,
    val question: String? = null,
    val reason: String? = null,
    val rawModelAction: String? = null,
    val sanitizerPath: String? = null,
    val sourceModel: String? = null,
    val sourceMode: String? = null
) {
    fun actionType(): String = type.name

    fun asReportArgs(): Map<String, Any?> {
        val args = linkedMapOf<String, Any?>(
            "coordSpace" to coordSpace.name,
            "sourceModel" to sourceModel,
            "sourceMode" to sourceMode
        )
        point?.let {
            args["x"] = it.x
            args["y"] = it.y
        }
        if (points.isNotEmpty()) {
            args["points"] = points.map { mapOf("x" to it.x, "y" to it.y) }
        }
        durationMs?.let { args["durationMs"] = it }
        text?.let { args["text"] = it }
        question?.let { args["question"] = it }
        reason?.let { args["reason"] = it }
        rawModelAction?.let { args["rawModelAction"] = it }
        sanitizerPath?.let { args["sanitizerPath"] = it }
        return args
    }
}

fun GuiAction.toCanonicalAction(
    rawModelAction: String? = null,
    sanitizerPath: String? = null,
    sourceModel: String? = null,
    sourceMode: String? = null,
    coordSpace: CanonicalCoordSpace? = null
): CanonicalAction {
    return when (this) {
        is GuiAction.Click -> CanonicalAction(
            type = CanonicalActionType.CLICK,
            coordSpace = coordSpace ?: CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(x = x, y = y),
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        is GuiAction.LongPress -> CanonicalAction(
            type = CanonicalActionType.LONG_PRESS,
            coordSpace = coordSpace ?: CanonicalCoordSpace.IMAGE_PIXEL,
            point = CanonicalPoint(x = x, y = y),
            durationMs = durationMs,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        is GuiAction.Swipe -> CanonicalAction(
            type = CanonicalActionType.SWIPE,
            coordSpace = coordSpace ?: CanonicalCoordSpace.IMAGE_PIXEL,
            points = listOf(
                CanonicalPoint(x = x1, y = y1),
                CanonicalPoint(x = x2, y = y2)
            ),
            durationMs = durationMs,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        is GuiAction.TypeText -> CanonicalAction(
            type = CanonicalActionType.TYPE_TEXT,
            text = text,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        GuiAction.Back -> CanonicalAction(
            type = CanonicalActionType.BACK,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        GuiAction.Home -> CanonicalAction(
            type = CanonicalActionType.HOME,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        is GuiAction.Wait -> CanonicalAction(
            type = CanonicalActionType.WAIT,
            durationMs = durationMs,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        is GuiAction.AskUser -> CanonicalAction(
            type = CanonicalActionType.ASK_USER,
            question = question,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )

        is GuiAction.Done -> CanonicalAction(
            type = CanonicalActionType.DONE,
            reason = reason,
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = sourceMode
        )
    }
}
