package com.example.agenthost.overlay.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.agenthost.R

class NeedUserCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val titleView: TextView
    private val messageView: TextView
    private val primaryButton: Button
    private val secondaryButton: Button

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_overlay_need_user_card, this, true)
        titleView = findViewById(R.id.overlay_need_user_title)
        messageView = findViewById(R.id.overlay_need_user_message)
        primaryButton = findViewById(R.id.overlay_need_user_primary)
        secondaryButton = findViewById(R.id.overlay_need_user_secondary)
    }

    fun bind(
        title: String,
        message: String,
        primaryLabel: String,
        secondaryLabel: String,
        recoveryStyle: Boolean
    ) {
        titleView.text = title
        messageView.text = message
        primaryButton.text = primaryLabel
        secondaryButton.text = secondaryLabel
        setBackgroundResource(
            if (recoveryStyle) {
                R.drawable.overlay_bg_recovery_card
            } else {
                R.drawable.overlay_bg_card
            }
        )
    }

    fun setPrimaryClickListener(listener: OnClickListener) {
        primaryButton.setOnClickListener(listener)
    }

    fun setSecondaryClickListener(listener: OnClickListener) {
        secondaryButton.setOnClickListener(listener)
    }
}
