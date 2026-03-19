package com.example.agenthost.overlay

enum class OverlayMode {
    IDLE,
    RUNNING,
    NEED_USER,
    HANDOFF_ACTIVE,
    RECOVERY,
    POST_COMPLETION
}

enum class BubbleTone {
    IDLE,
    RUNNING,
    NEED_USER,
    RECOVERY
}

enum class NewTaskEntryState {
    AVAILABLE,
    BLOCKED,
    HIDDEN
}

data class OverlayUiState(
    val mode: OverlayMode,
    val sessionId: String? = null,
    val bubbleText: String,
    val bubbleDetail: String,
    val bubbleTone: BubbleTone,
    val title: String? = null,
    val message: String? = null,
    val primaryLabel: String? = null,
    val secondaryLabel: String? = null,
    val requiresReplyInput: Boolean = false,
    val defaultReplyText: String = DEFAULT_REPLY_TEXT,
    val newTaskEntryState: NewTaskEntryState = NewTaskEntryState.HIDDEN,
    val newTaskMessage: String? = null,
    val newTaskHint: String = DEFAULT_NEW_TASK_HINT,
    val alreadyOnMain: Boolean = false,
    val isVirtualDisplaySession: Boolean = false,
    val lastMigrationCode: String? = null
) {
    companion object {
        const val DEFAULT_REPLY_TEXT = "已处理完成"
        const val DEFAULT_NEW_TASK_HINT = "让 agent 帮你做什么？"
    }
}
