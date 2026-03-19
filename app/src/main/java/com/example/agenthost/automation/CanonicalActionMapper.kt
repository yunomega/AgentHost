package com.example.agenthost.automation

class CanonicalActionMapper {
    fun map(
        action: GuiAction,
        rawResponseText: String?,
        sourceMode: String,
        fallbackSourceModel: String? = null
    ): CanonicalAction {
        val parsed = rawResponseText?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { JsonLite.parse(it) }.getOrNull()
        }
        val meta = (parsed as? JsonLiteValue.Obj)?.fields?.get("_meta") as? JsonLiteValue.Obj
        val sourceModel = meta?.optionalString("sourceModel")?.ifBlank { fallbackSourceModel } ?: fallbackSourceModel
        val metaSourceMode = meta?.optionalString("sourceMode")?.ifBlank { sourceMode } ?: sourceMode
        val rawModelAction = meta?.optionalString("rawModelAction")?.ifBlank { rawResponseText } ?: rawResponseText
        val sanitizerPath = meta?.optionalString("sanitizerPath")?.ifBlank { null }
        val coordSpace = parseCoordSpace(meta?.optionalString("coordSpace"))
        return action.toCanonicalAction(
            rawModelAction = rawModelAction,
            sanitizerPath = sanitizerPath,
            sourceModel = sourceModel,
            sourceMode = metaSourceMode,
            coordSpace = coordSpace
        )
    }

    private fun parseCoordSpace(raw: String?): CanonicalCoordSpace? {
        val normalized = raw?.trim()?.uppercase().orEmpty()
        return when (normalized) {
            "IMAGE_PIXEL" -> CanonicalCoordSpace.IMAGE_PIXEL
            "IMAGE_PERMILLE" -> CanonicalCoordSpace.IMAGE_PERMILLE
            "NONE" -> CanonicalCoordSpace.NONE
            else -> null
        }
    }
}
