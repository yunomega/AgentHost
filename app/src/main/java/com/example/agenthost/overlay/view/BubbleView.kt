package com.example.agenthost.overlay.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.agenthost.R
import com.example.agenthost.overlay.BubbleTone
import com.example.agenthost.overlay.OverlayUiState

class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val container: LinearLayout
    private val titleView: TextView
    private val detailView: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_overlay_bubble, this, true)
        container = findViewById(R.id.overlay_bubble_body)
        titleView = findViewById(R.id.overlay_bubble_title)
        detailView = findViewById(R.id.overlay_bubble_detail)
    }

    fun bind(state: OverlayUiState, expanded: Boolean) {
        titleView.text = state.bubbleText
        detailView.text = state.bubbleDetail
        detailView.visibility = if (expanded && state.bubbleDetail.isNotBlank()) {
            VISIBLE
        } else {
            GONE
        }
        container.setBackgroundResource(
            when (state.bubbleTone) {
                BubbleTone.IDLE -> R.drawable.overlay_bg_bubble_idle
                BubbleTone.RUNNING -> R.drawable.overlay_bg_bubble_running
                BubbleTone.NEED_USER -> R.drawable.overlay_bg_bubble_need_user
                BubbleTone.RECOVERY -> R.drawable.overlay_bg_bubble_recovery
            }
        )
    }
}
