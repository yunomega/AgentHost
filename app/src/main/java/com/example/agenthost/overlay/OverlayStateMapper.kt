package com.example.agenthost.overlay

import com.example.agenthost.session.SessionState
import com.example.agenthost.session.SessionStatus
import com.example.agenthost.migration.TaskMigrationPolicy
import com.example.agenthost.session.PostCompletionDisposition
import com.example.agenthost.session.PostCompletionState
import com.example.agenthost.session.SessionDisplayMode

class OverlayStateMapper {
    // This mapper is the only owner of canonical session -> overlay presentation mode.
    // It may consume canonical state only; controller-retained UI state stays outside.
    fun modeName(session: SessionState?): String = map(session).mode.name

    fun map(session: SessionState?): OverlayUiState {
        if (session == null || session.status in TERMINAL_STATUSES) {
            return OverlayUiState(
                mode = OverlayMode.IDLE,
                bubbleText = "空闲",
                bubbleDetail = "无活动会话",
                bubbleTone = BubbleTone.IDLE,
                title = "当前空闲",
                message = "主屏保持给用户使用，AgentHost 暂无后台任务。",
                newTaskEntryState = NewTaskEntryState.AVAILABLE,
                newTaskMessage = "当前空闲，可直接发起新的后台任务。"
            )
        }

        if (session.status == SessionStatus.COMPLETED) {
            return mapCompleted(session)
        }

        if (session.recoveryNeeded ||
            session.status == SessionStatus.FAILED ||
            TaskMigrationPolicy.requiresRecovery(session.lastMigrationCode)
        ) {
            return mapRecovery(session)
        }

        return when {
            session.status == SessionStatus.WAITING_USER && session.migratedToMain -> mapHandoff(session)
            session.status == SessionStatus.WAITING_USER -> mapNeedUser(session)
            else -> mapRunning(session)
        }
    }

    private fun mapRunning(session: SessionState): OverlayUiState {
        val detail = if (session.displayMode == SessionDisplayMode.MAIN_DISPLAY) {
            "主屏执行中"
        } else {
            "虚拟屏后台执行"
        }
        return OverlayUiState(
            mode = OverlayMode.RUNNING,
            sessionId = session.sessionId,
            bubbleText = "执行中",
            bubbleDetail = detail,
            bubbleTone = BubbleTone.RUNNING,
            title = "后台执行中",
            message = "主屏不接管真实任务，只有明确 handoff 时才会提示你接手。",
            newTaskEntryState = NewTaskEntryState.BLOCKED,
            newTaskMessage = "当前已有任务在执行或处理中，完成或取消后再发起新任务。",
            alreadyOnMain = session.displayMode == SessionDisplayMode.MAIN_DISPLAY,
            isVirtualDisplaySession = session.displayMode == SessionDisplayMode.VIRTUAL_DISPLAY,
            lastMigrationCode = session.lastMigrationCode
        )
    }

    private fun mapCompleted(session: SessionState): OverlayUiState {
        val resultCode = session.postCompletionResultCode ?: session.lastMigrationCode
        val disposition = when (session.postCompletionDisposition) {
            PostCompletionDisposition.RETURN_TASK_TO_MAIN -> PostCompletionDisposition.RETURN_TASK_TO_MAIN
            else -> PostCompletionDisposition.CLEANUP_IMMEDIATELY
        }
        val succeeded = session.postCompletionState == PostCompletionState.SUCCEEDED
        val returnedToMain = disposition == PostCompletionDisposition.RETURN_TASK_TO_MAIN && succeeded
        val bubbleDetail = when {
            returnedToMain -> "已回主屏"
            disposition == PostCompletionDisposition.RETURN_TASK_TO_MAIN -> "已安全结束"
            else -> "会话已清理"
        }
        val message = buildString {
            when {
                returnedToMain -> {
                    append("任务已完成，目标任务已迁回主屏。")
                }

                disposition == PostCompletionDisposition.RETURN_TASK_TO_MAIN -> {
                    append("任务已完成，但未能将目标任务迁回主屏。会话已安全结束。")
                }

                else -> {
                    append("任务已完成，后台会话已清理。")
                }
            }
            resultCode
                ?.takeIf {
                    disposition == PostCompletionDisposition.RETURN_TASK_TO_MAIN && !returnedToMain
                }
                ?.let {
                    append("\n失败码: ").append(it)
                }
        }
        return OverlayUiState(
            mode = OverlayMode.POST_COMPLETION,
            sessionId = session.sessionId,
            bubbleText = "已完成",
            bubbleDetail = bubbleDetail,
            bubbleTone = BubbleTone.IDLE,
            title = "任务已完成",
            message = message,
            primaryLabel = if (returnedToMain) "查看主屏" else "知道了",
            secondaryLabel = "收起",
            newTaskEntryState = NewTaskEntryState.AVAILABLE,
            newTaskMessage = "上一项任务已结束，可直接发起新任务。",
            alreadyOnMain = returnedToMain || session.isOnMainDisplay(),
            isVirtualDisplaySession = session.displayMode == SessionDisplayMode.VIRTUAL_DISPLAY,
            lastMigrationCode = resultCode
        )
    }

    private fun mapNeedUser(session: SessionState): OverlayUiState {
        val onMain = session.displayMode == SessionDisplayMode.MAIN_DISPLAY
        val question = session.waitingQuestion?.takeIf { it.isNotBlank() }
        val message = buildString {
            append(question ?: "这一步需要你人工确认后继续。")
            if (!onMain) {
                append("\n去主屏处理后，处理完成再返回这里继续。")
            }
        }
        return OverlayUiState(
            mode = OverlayMode.NEED_USER,
            sessionId = session.sessionId,
            bubbleText = "等你",
            bubbleDetail = if (onMain) "主屏等待处理" else "需要切到主屏处理",
            bubbleTone = BubbleTone.NEED_USER,
            title = if (onMain) "需要你继续处理" else "需要你接手",
            message = message,
            primaryLabel = if (onMain) "继续处理" else "去主屏处理",
            secondaryLabel = "稍后",
            alreadyOnMain = onMain,
            isVirtualDisplaySession = session.displayMode == SessionDisplayMode.VIRTUAL_DISPLAY,
            lastMigrationCode = session.lastMigrationCode
        )
    }

    private fun mapHandoff(session: SessionState): OverlayUiState {
        val question = session.waitingQuestion?.takeIf { it.isNotBlank() }
        val message = buildString {
            append(question ?: "主屏任务已经交给你处理。")
            append("\n处理完成后，在这里提交验证码或简短回复，让后台继续执行。")
        }
        return OverlayUiState(
            mode = OverlayMode.HANDOFF_ACTIVE,
            sessionId = session.sessionId,
            bubbleText = "等你",
            bubbleDetail = "主屏处理中",
            bubbleTone = BubbleTone.NEED_USER,
            title = "主屏接手中",
            message = message,
            primaryLabel = "继续处理",
            secondaryLabel = "稍后",
            requiresReplyInput = true,
            alreadyOnMain = true,
            isVirtualDisplaySession = true,
            lastMigrationCode = session.lastMigrationCode
        )
    }

    private fun mapRecovery(session: SessionState): OverlayUiState {
        val alreadyOnMain = OverlayRecoverySupport.isAlreadyOnMain(session)
        val fallbackToIdle = OverlayRecoverySupport.shouldFallbackToIdle(session)
        val message = buildString {
            append(
                when {
                    fallbackToIdle -> "后台任务目标已失效，当前无法恢复到原任务。返回空闲后重新发起。"
                    session.migratedToMain -> "任务切换出现异常，当前真实任务仍在主屏。"
                    else -> "后台任务状态异常，需要人工确认后再继续。"
                }
            )
            session.lastMigrationCode?.let {
                append("\n错误码: ").append(it)
            }
            session.lastMigrationConflictReason
                ?.takeIf { it.isNotBlank() }
                ?.let { append("\n原因: ").append(it) }
        }
        val primaryLabel = when {
            fallbackToIdle -> "返回空闲"
            session.displayMode != SessionDisplayMode.VIRTUAL_DISPLAY -> "继续处理"
            session.migratedToMain -> "返回后台执行"
            alreadyOnMain -> "继续处理"
            else -> "去主屏处理"
        }
        return OverlayUiState(
            mode = OverlayMode.RECOVERY,
            sessionId = session.sessionId,
            bubbleText = "异常",
            bubbleDetail = "需要人工接手",
            bubbleTone = BubbleTone.RECOVERY,
            title = "需要人工恢复",
            message = message,
            primaryLabel = primaryLabel,
            secondaryLabel = "取消任务",
            alreadyOnMain = alreadyOnMain,
            isVirtualDisplaySession = session.displayMode == SessionDisplayMode.VIRTUAL_DISPLAY,
            lastMigrationCode = session.lastMigrationCode
        )
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            SessionStatus.CLOSED,
            SessionStatus.MIGRATED
        )
    }
}
