package com.example.agenthost.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import com.example.agenthost.overlay.view.BubbleView
import com.example.agenthost.overlay.view.HandoffStripView
import com.example.agenthost.overlay.view.NeedUserCardView
import com.example.agenthost.session.SessionState

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
    }
    private val bubbleView = BubbleView(appContext)
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
    private var snoozedSessionId: String? = null
    private var acknowledgedHandoffSessionId: String? = null
    private var replyDraftSessionId: String? = null
    private var replyDraft: String = OverlayUiState.DEFAULT_REPLY_TEXT
    private var windowReplyInteractive = false
    private var dragging = false
    private var longPressTriggered = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0

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
        rootView.addView(needUserCardView, cardLayoutParams)
        rootView.addView(handoffStripView, cardLayoutParams)
        bubbleView.setOnClickListener { toggleExpansionFromBubble() }
        bubbleView.setOnTouchListener(::handleBubbleTouch)
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
        val mapped = stateMapper.map(session)
        syncLocalUiState(mapped)
        currentUiState = mapped
        val showPromptPanel = shouldShowPromptPanel(mapped)
        val showHandoffStrip = showPromptPanel &&
            mapped.mode == OverlayMode.HANDOFF_ACTIVE &&
            acknowledgedHandoffSessionId == mapped.sessionId
        val showNeedUserPanel = showPromptPanel && !showHandoffStrip &&
            mapped.mode in setOf(
                OverlayMode.NEED_USER,
                OverlayMode.HANDOFF_ACTIVE,
                OverlayMode.RECOVERY
            )
        val bubbleExpanded = isExpanded && !showPromptPanel &&
            mapped.mode in setOf(OverlayMode.IDLE, OverlayMode.RUNNING)

        bubbleView.bind(mapped, bubbleExpanded)
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
            handoffStripView.bind(
                title = "处理完成后返回后台执行",
                message = mapped.message.orEmpty(),
                replyText = replyDraft.ifBlank { mapped.defaultReplyText },
                primaryLabel = "提交并返回后台",
                secondaryLabel = "取消任务"
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
        updateWindowInteractivity(showHandoffStrip)
        if (isAttached) {
            rootView.post { snapToEdge() }
        }
    }

    private fun syncLocalUiState(mapped: OverlayUiState) {
        val previousSessionId = currentUiState.sessionId
        val newSessionId = mapped.sessionId
        val interactive = mapped.mode in setOf(
            OverlayMode.NEED_USER,
            OverlayMode.HANDOFF_ACTIVE,
            OverlayMode.RECOVERY
        )
        val sessionChanged = previousSessionId != newSessionId
        val modeChanged = currentUiState.mode != mapped.mode

        if (sessionChanged) {
            snoozedSessionId = null
            acknowledgedHandoffSessionId = null
            replyDraftSessionId = null
            replyDraft = OverlayUiState.DEFAULT_REPLY_TEXT
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
            isExpanded = false
            snoozedSessionId = null
            return
        }

        if ((sessionChanged || modeChanged) && snoozedSessionId != newSessionId) {
            isExpanded = true
        }
    }

    private fun toggleExpansionFromBubble() {
        val state = currentUiState
        if (state.mode in setOf(OverlayMode.NEED_USER, OverlayMode.HANDOFF_ACTIVE, OverlayMode.RECOVERY)) {
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
        val session = currentSession ?: return
        when (currentUiState.mode) {
            OverlayMode.NEED_USER -> {
                if (currentUiState.alreadyOnMain) {
                    isExpanded = false
                } else {
                    actionDispatcher.requestMoveToMain(session)
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
                actionDispatcher.requestRecoveryPrimary(session)
            }

            else -> Unit
        }
        render(currentSession)
    }

    private fun handleNeedUserSecondary() {
        if (currentUiState.mode == OverlayMode.RECOVERY) {
            actionDispatcher.cancelSession()
        } else {
            snoozedSessionId = currentUiState.sessionId
            isExpanded = false
            render(currentSession)
        }
    }

    private fun handleHandoffPrimary() {
        val session = currentSession ?: return
        val reply = handoffStripView.currentReplyText()
            .trim()
            .ifBlank { currentUiState.defaultReplyText }
        replyDraft = reply
        actionDispatcher.submitReply(session.sessionId, reply)
    }

    private fun handleHandoffSecondary() {
        actionDispatcher.cancelSession()
    }

    private fun shouldShowPromptPanel(state: OverlayUiState): Boolean {
        if (!isExpanded || snoozedSessionId == state.sessionId) {
            return false
        }
        return state.mode in setOf(
            OverlayMode.NEED_USER,
            OverlayMode.HANDOFF_ACTIVE,
            OverlayMode.RECOVERY
        )
    }

    private fun updateWindowInteractivity(replyInteractive: Boolean) {
        if (windowReplyInteractive == replyInteractive) {
            return
        }
        windowReplyInteractive = replyInteractive
        layoutParams.flags = baseWindowFlags(replyInteractive)
        if (isAttached) {
            try {
                windowManager.updateViewLayout(rootView, layoutParams)
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
