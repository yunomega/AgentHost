package com.example.agenthost.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.example.agenthost.overlay.view.BubbleView
import com.example.agenthost.overlay.view.HandoffStripView
import com.example.agenthost.overlay.view.NewTaskInputView
import com.example.agenthost.overlay.view.NeedUserCardView
import com.example.agenthost.session.SessionState
import com.example.agenthost.session.WaitingUserDisposition

class OverlayController(
    context: Context,
    private val stateMapper: OverlayStateMapper,
    private val actionDispatcher: OverlayActionDispatcher
) {
    companion object {
        private const val TAG = "OverlayController"
    }

    private enum class AnchorSide {
        START,
        END
    }

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val marginPx = dp(12)
    private val panelWidthPx = dp(280)
    private val initialYPx = dp(160)

    private val rootView = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }
    private val bubbleView = BubbleView(appContext)
    private val newTaskInputView = NewTaskInputView(appContext).apply {
        visibility = View.GONE
    }
    private val needUserCardView = NeedUserCardView(appContext).apply {
        visibility = View.GONE
    }
    private val handoffStripView = HandoffStripView(appContext).apply {
        visibility = View.GONE
    }
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayWindowType(),
        baseWindowFlags(replyInteractive = false),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = marginPx
        y = initialYPx
    }

    private var isAttached = false
    private var isExpanded = false
    private var anchorSide = AnchorSide.END
    private var currentSession: SessionState? = null
    private var currentUiState: OverlayUiState = stateMapper.map(null)
    // These fields are controller-local retained UI state. They intentionally do
    // not participate in canonical session state or shared classification helpers.
    private val postCompletionRetention = OverlayPostCompletionRetention(
        nowMs = { SystemClock.elapsedRealtime() }
    )
    private var dismissedPostCompletionSessionId: String? = null
    private var snoozedSessionId: String? = null
    private var acknowledgedHandoffSessionId: String? = null
    private var replyDraftSessionId: String? = null
    private var replyDraft: String = OverlayUiState.DEFAULT_REPLY_TEXT
    private var newTaskDraft: String = ""
    private var windowInteractive = false
    private var dragging = false
    private var longPressTriggered = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private val clearRetainedPostCompletionRunnable = Runnable {
        if (postCompletionRetention.expireIfNeeded()) {
            render(currentSession)
        } else {
            scheduleRetainedPostCompletionExpiry()
        }
    }

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        dragging = true
        bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        val cardLayoutParams = LinearLayout.LayoutParams(
            panelWidthPx,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
        rootView.addView(
            bubbleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rootView.addView(newTaskInputView, cardLayoutParams)
        rootView.addView(needUserCardView, cardLayoutParams)
        rootView.addView(handoffStripView, cardLayoutParams)
        bubbleView.setOnClickListener { toggleExpansionFromBubble() }
        bubbleView.setOnTouchListener(::handleBubbleTouch)
        newTaskInputView.setPrimaryClickListener { handleNewTaskPrimary() }
        newTaskInputView.setSecondaryClickListener { handleNewTaskSecondary() }
        newTaskInputView.setOnInputChangedListener { text ->
            newTaskDraft = text
            bindNewTaskPanel(currentUiState)
        }
        needUserCardView.setPrimaryClickListener { handleNeedUserPrimary() }
        needUserCardView.setSecondaryClickListener { handleNeedUserSecondary() }
        handoffStripView.setPrimaryClickListener { handleHandoffPrimary() }
        handoffStripView.setSecondaryClickListener { handleHandoffSecondary() }
        handoffStripView.setOnReplyChangedListener { text ->
            replyDraft = text
        }
    }

    fun show() {
        if (isAttached || !canDrawOverlay()) {
            return
        }
        try {
            windowManager.addView(rootView, layoutParams)
            isAttached = true
            Log.i(TAG, "overlay_window_added x=${layoutParams.x} y=${layoutParams.y}")
            rootView.post {
                anchorSide = AnchorSide.END
                snapToEdge()
                render(currentSession)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to add overlay window", t)
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isAttached) {
            return
        }
        try {
            windowManager.removeView(rootView)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to remove overlay window", t)
        } finally {
            isAttached = false
        }
    }

    fun render(session: SessionState?) {
        currentSession = session
        val mapped = resolvePresentedState(session, stateMapper.map(session))
        syncLocalUiState(mapped)
        currentUiState = mapped
        val showPromptPanel = shouldShowPromptPanel(mapped)
        val showNewTaskPanel = isExpanded &&
            !showPromptPanel &&
            mapped.newTaskEntryState != NewTaskEntryState.HIDDEN
        val showHandoffStrip = showPromptPanel &&
            mapped.requiresReplyInput &&
            (
                acknowledgedHandoffSessionId == mapped.sessionId ||
                    isCompletionConfirmationPending(session)
            )
        val showNeedUserPanel = showPromptPanel && !showHandoffStrip &&
            mapped.mode in setOf(
                OverlayMode.NEED_USER,
                OverlayMode.HANDOFF_ACTIVE,
                OverlayMode.RECOVERY,
                OverlayMode.POST_COMPLETION
            )
        val bubbleExpanded = isExpanded && !showPromptPanel && !showNewTaskPanel &&
            mapped.mode in setOf(OverlayMode.IDLE, OverlayMode.RUNNING)

        bubbleView.bind(mapped, bubbleExpanded)
        if (showNewTaskPanel) {
            bindNewTaskPanel(mapped)
            newTaskInputView.visibility = View.VISIBLE
        } else {
            newTaskInputView.visibility = View.GONE
        }
        if (showNeedUserPanel) {
            needUserCardView.bind(
                title = mapped.title.orEmpty(),
                message = mapped.message.orEmpty(),
                primaryLabel = mapped.primaryLabel.orEmpty(),
                secondaryLabel = mapped.secondaryLabel.orEmpty(),
                recoveryStyle = mapped.mode == OverlayMode.RECOVERY
            )
            needUserCardView.visibility = View.VISIBLE
        } else {
            needUserCardView.visibility = View.GONE
        }
        if (showHandoffStrip) {
            val completionConfirmation = isCompletionConfirmationPending(session)
            handoffStripView.bind(
                title = if (completionConfirmation) {
                    mapped.title ?: "继续后台执行"
                } else {
                    "处理完成后返回后台执行"
                },
                message = mapped.message.orEmpty(),
                replyText = replyDraft.ifBlank { mapped.defaultReplyText },
                primaryLabel = if (completionConfirmation) "提交并继续后台" else "提交并返回后台",
                secondaryLabel = if (completionConfirmation) {
                    mapped.secondaryLabel ?: "稍后"
                } else {
                    "取消任务"
                }
            )
            handoffStripView.visibility = View.VISIBLE
        } else {
            handoffStripView.visibility = View.GONE
        }
        rootView.gravity = if (anchorSide == AnchorSide.END) {
            Gravity.END
        } else {
            Gravity.START
        }
        updateWindowInteractivity(
            showHandoffStrip ||
                showNeedUserPanel ||
                (showNewTaskPanel && mapped.newTaskEntryState == NewTaskEntryState.AVAILABLE)
        )
        scheduleRetainedPostCompletionExpiry()
        if (isAttached) {
            rootView.post { snapToEdge() }
        }
    }

    private fun resolvePresentedState(session: SessionState?, mapped: OverlayUiState): OverlayUiState {
        if (session != null &&
            mapped.mode == OverlayMode.POST_COMPLETION &&
            session.sessionId == dismissedPostCompletionSessionId
        ) {
            postCompletionRetention.dismiss()
            handler.removeCallbacks(clearRetainedPostCompletionRunnable)
            return stateMapper.map(null)
        }
        if (session != null &&
            mapped.mode != OverlayMode.POST_COMPLETION &&
            session.sessionId != dismissedPostCompletionSessionId
        ) {
            dismissedPostCompletionSessionId = null
        }
        return postCompletionRetention.resolve(sessionPresent = session != null, mapped = mapped)
    }

    private fun scheduleRetainedPostCompletionExpiry() {
        handler.removeCallbacks(clearRetainedPostCompletionRunnable)
        val remaining = postCompletionRetention.remainingMs() ?: return
        handler.postDelayed(clearRetainedPostCompletionRunnable, remaining)
    }

    private fun syncLocalUiState(mapped: OverlayUiState) {
        val previousSessionId = currentUiState.sessionId
        val newSessionId = mapped.sessionId
        val interactive = mapped.mode in setOf(
            OverlayMode.NEED_USER,
            OverlayMode.HANDOFF_ACTIVE,
            OverlayMode.RECOVERY,
            OverlayMode.POST_COMPLETION
        )
        val wasInteractive = currentUiState.mode in setOf(
            OverlayMode.NEED_USER,
            OverlayMode.HANDOFF_ACTIVE,
            OverlayMode.RECOVERY,
            OverlayMode.POST_COMPLETION
        )
        val sessionChanged = previousSessionId != newSessionId
        val modeChanged = currentUiState.mode != mapped.mode

        if (sessionChanged) {
            snoozedSessionId = null
            acknowledgedHandoffSessionId = null
            replyDraftSessionId = null
            replyDraft = OverlayUiState.DEFAULT_REPLY_TEXT
            newTaskDraft = ""
        }

        if (mapped.mode != OverlayMode.HANDOFF_ACTIVE && acknowledgedHandoffSessionId == newSessionId) {
            acknowledgedHandoffSessionId = null
        }

        if (mapped.requiresReplyInput && replyDraftSessionId != newSessionId) {
            replyDraftSessionId = newSessionId
            replyDraft = mapped.defaultReplyText
        }
        if (!mapped.requiresReplyInput && replyDraftSessionId == newSessionId) {
            replyDraftSessionId = null
            replyDraft = OverlayUiState.DEFAULT_REPLY_TEXT
        }

        if (!interactive) {
            if (sessionChanged || wasInteractive) {
                isExpanded = false
            }
            if (wasInteractive) {
                snoozedSessionId = null
            }
            return
        }

        if ((sessionChanged || modeChanged) && snoozedSessionId != newSessionId) {
            isExpanded = true
        }
    }

    private fun toggleExpansionFromBubble() {
        val state = currentUiState
        if (state.mode in setOf(
                OverlayMode.NEED_USER,
                OverlayMode.HANDOFF_ACTIVE,
                OverlayMode.RECOVERY,
                OverlayMode.POST_COMPLETION
            )
        ) {
            if (snoozedSessionId == state.sessionId) {
                snoozedSessionId = null
            }
            isExpanded = !isExpanded
        } else {
            isExpanded = !isExpanded
        }
        render(currentSession)
    }

    private fun handleNeedUserPrimary() {
        if (currentUiState.mode == OverlayMode.POST_COMPLETION) {
            dismissPostCompletion()
            return
        }
        val session = currentSession ?: return
        when (currentUiState.mode) {
            OverlayMode.NEED_USER -> {
                if (isCompletionConfirmationPending(session)) {
                    acknowledgedHandoffSessionId = session.sessionId
                    if (replyDraftSessionId != session.sessionId) {
                        replyDraftSessionId = session.sessionId
                        replyDraft = currentUiState.defaultReplyText
                    }
                    isExpanded = true
                } else if (currentUiState.alreadyOnMain) {
                    isExpanded = false
                } else {
                    actionDispatcher.requestMoveToMain(session.sessionId, currentUiState)
                }
            }

            OverlayMode.HANDOFF_ACTIVE -> {
                acknowledgedHandoffSessionId = session.sessionId
                if (replyDraftSessionId != session.sessionId) {
                    replyDraftSessionId = session.sessionId
                    replyDraft = currentUiState.defaultReplyText
                }
                isExpanded = true
            }

            OverlayMode.RECOVERY -> {
                when (OverlayRecoverySupport.derivePrimaryRoute(session)) {
                    RecoveryPrimaryRoute.NONE -> {
                        isExpanded = false
                    }

                    else -> {
                        actionDispatcher.requestRecoveryPrimary(session.sessionId, currentUiState)
                    }
                }
            }

            else -> Unit
        }
        render(currentSession)
    }

    private fun handleNewTaskPrimary() {
        if (currentUiState.newTaskEntryState != NewTaskEntryState.AVAILABLE) {
            return
        }
        val command = newTaskInputView.currentInputText().trim()
        if (command.isBlank()) {
            return
        }
        newTaskDraft = command
        actionDispatcher.submitNewTask(command)
    }

    private fun handleNewTaskSecondary() {
        isExpanded = false
        render(currentSession)
    }

    private fun handleNeedUserSecondary() {
        if (currentUiState.mode == OverlayMode.POST_COMPLETION) {
            dismissPostCompletion()
        } else if (currentUiState.mode == OverlayMode.NEED_USER &&
            isCompletionConfirmationPending(currentSession)
        ) {
            val session = currentSession ?: return
            if (currentUiState.alreadyOnMain) {
                snoozedSessionId = currentUiState.sessionId
                isExpanded = false
                render(currentSession)
            } else {
                actionDispatcher.requestReturnToMain(session.sessionId, currentUiState)
            }
        } else if (currentUiState.mode == OverlayMode.RECOVERY) {
            actionDispatcher.cancelSession(currentSession?.sessionId, currentUiState)
        } else {
            snoozedSessionId = currentUiState.sessionId
            isExpanded = false
            render(currentSession)
        }
    }

    private fun dismissPostCompletion() {
        dismissedPostCompletionSessionId = currentUiState.sessionId
        postCompletionRetention.dismiss()
        handler.removeCallbacks(clearRetainedPostCompletionRunnable)
        isExpanded = false
        render(currentSession)
    }

    private fun handleHandoffPrimary() {
        val session = currentSession ?: return
        val reply = handoffStripView.currentReplyText()
            .trim()
            .ifBlank { currentUiState.defaultReplyText }
        replyDraft = reply
        actionDispatcher.submitReply(session.sessionId, reply, currentUiState)
    }

    private fun handleHandoffSecondary() {
        if (isCompletionConfirmationPending(currentSession)) {
            val session = currentSession ?: return
            if (currentUiState.alreadyOnMain) {
                snoozedSessionId = currentUiState.sessionId
                isExpanded = false
                render(currentSession)
            } else {
                actionDispatcher.requestReturnToMain(session.sessionId, currentUiState)
            }
        } else {
            actionDispatcher.cancelSession(currentSession?.sessionId, currentUiState)
        }
    }

    private fun isCompletionConfirmationPending(session: SessionState?): Boolean {
        return session?.waitingUserDisposition == WaitingUserDisposition.COMPLETION_CONFIRMATION
    }

    private fun shouldShowPromptPanel(state: OverlayUiState): Boolean {
        if (!isExpanded || snoozedSessionId == state.sessionId) {
            return false
        }
        return state.mode in setOf(
            OverlayMode.NEED_USER,
            OverlayMode.HANDOFF_ACTIVE,
            OverlayMode.RECOVERY,
            OverlayMode.POST_COMPLETION
        )
    }

    private fun bindNewTaskPanel(state: OverlayUiState) {
        val inputEnabled = state.newTaskEntryState == NewTaskEntryState.AVAILABLE
        val draft = newTaskDraft
        newTaskInputView.bind(
            title = "新任务",
            message = state.newTaskMessage.orEmpty(),
            hint = state.newTaskHint,
            inputText = draft,
            inputEnabled = inputEnabled,
            primaryEnabled = inputEnabled && draft.trim().isNotEmpty(),
            primaryLabel = "开始",
            secondaryLabel = "收起"
        )
    }

    private fun updateWindowInteractivity(inputInteractive: Boolean) {
        if (windowInteractive == inputInteractive) {
            return
        }
        windowInteractive = inputInteractive
        layoutParams.flags = baseWindowFlags(inputInteractive)
        if (isAttached) {
            try {
                windowManager.updateViewLayout(rootView, layoutParams)
                if (inputInteractive) {
                    // Keep initial focus on the overlay container so cards with EditText
                    // do not auto-pop the IME when the window becomes interactive.
                    rootView.requestFocus()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "failed to update overlay interactivity", t)
            }
        }
    }

    private fun handleBubbleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams.x
                downY = layoutParams.y
                dragging = false
                longPressTriggered = false
                handler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!longPressTriggered &&
                    (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)
                ) {
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    layoutParams.x = downX + dx
                    layoutParams.y = downY + dy
                    clampWithinBounds()
                    updateViewLayoutSafely()
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val wasDragging = dragging
                dragging = false
                longPressTriggered = false
                if (wasDragging) {
                    snapToEdge()
                } else {
                    view.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateViewLayoutSafely() {
        if (!isAttached) {
            return
        }
        try {
            windowManager.updateViewLayout(rootView, layoutParams)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to update overlay position", t)
        }
    }

    private fun snapToEdge() {
        val bounds = screenBounds()
        val width = rootView.width.takeIf { it > 0 } ?: rootView.measuredWidth
        val height = rootView.height.takeIf { it > 0 } ?: rootView.measuredHeight
        if (width <= 0) {
            return
        }
        val centerX = layoutParams.x + width / 2
        anchorSide = if (centerX >= bounds.width() / 2) {
            AnchorSide.END
        } else {
            AnchorSide.START
        }
        layoutParams.x = if (anchorSide == AnchorSide.END) {
            bounds.width() - width - marginPx
        } else {
            marginPx
        }
        val maxY = (bounds.height() - height - marginPx).coerceAtLeast(marginPx)
        layoutParams.y = layoutParams.y.coerceIn(marginPx, maxY)
        updateViewLayoutSafely()
    }

    private fun clampWithinBounds() {
        val bounds = screenBounds()
        val width = rootView.width.takeIf { it > 0 } ?: rootView.measuredWidth
        val height = rootView.height.takeIf { it > 0 } ?: rootView.measuredHeight
        val maxX = (bounds.width() - width - marginPx).coerceAtLeast(marginPx)
        val maxY = (bounds.height() - height - marginPx).coerceAtLeast(marginPx)
        layoutParams.x = layoutParams.x.coerceIn(marginPx, maxX)
        layoutParams.y = layoutParams.y.coerceIn(marginPx, maxY)
    }

    private fun screenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            Rect(
                0,
                0,
                appContext.resources.displayMetrics.widthPixels,
                appContext.resources.displayMetrics.heightPixels
            )
        }
    }

    private fun canDrawOverlay(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val allowed = Settings.canDrawOverlays(appContext)
        if (!allowed) {
            Log.w(
                TAG,
                "SYSTEM_ALERT_WINDOW app-op is not allowed, skip overlay host " +
                    "package=${appContext.packageName} " +
                    "action=${Settings.ACTION_MANAGE_OVERLAY_PERMISSION}"
            )
        }
        return allowed
    }

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun baseWindowFlags(replyInteractive: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!replyInteractive) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return flags
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt()
    }
}
