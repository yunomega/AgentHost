package com.example.agenthost.overlay.view

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.agenthost.R

class HandoffStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val titleView: TextView
    private val messageView: TextView
    private val replyInput: EditText
    private val primaryButton: Button
    private val secondaryButton: Button
    private var internalTextUpdate = false
    private var replyChangedListener: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_overlay_handoff_strip, this, true)
        setBackgroundResource(R.drawable.overlay_bg_strip)
        titleView = findViewById(R.id.overlay_handoff_title)
        messageView = findViewById(R.id.overlay_handoff_message)
        replyInput = findViewById(R.id.overlay_handoff_input)
        primaryButton = findViewById(R.id.overlay_handoff_primary)
        secondaryButton = findViewById(R.id.overlay_handoff_secondary)
        replyInput.hint = "输入验证码或简短回复"
        replyInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (!internalTextUpdate) {
                        replyChangedListener?.invoke(s?.toString().orEmpty())
                    }
                }
            }
        )
    }

    fun bind(
        title: String,
        message: String,
        replyText: String,
        primaryLabel: String,
        secondaryLabel: String
    ) {
        titleView.text = title
        messageView.text = message
        primaryButton.text = primaryLabel
        secondaryButton.text = secondaryLabel
        if (replyInput.text.toString() != replyText) {
            internalTextUpdate = true
            replyInput.setText(replyText)
            replyInput.setSelection(replyInput.text.length)
            internalTextUpdate = false
        }
    }

    fun setOnReplyChangedListener(listener: (String) -> Unit) {
        replyChangedListener = listener
    }

    fun setPrimaryClickListener(listener: OnClickListener) {
        primaryButton.setOnClickListener(listener)
    }

    fun setSecondaryClickListener(listener: OnClickListener) {
        secondaryButton.setOnClickListener(listener)
    }

    fun currentReplyText(): String = replyInput.text?.toString().orEmpty()
}
