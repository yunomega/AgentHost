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

class NewTaskInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val titleView: TextView
    private val messageView: TextView
    private val inputView: EditText
    private val primaryButton: Button
    private val secondaryButton: Button
    private var internalTextUpdate = false
    private var inputChangedListener: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_overlay_new_task_input, this, true)
        setBackgroundResource(R.drawable.overlay_bg_card)
        titleView = findViewById(R.id.overlay_new_task_title)
        messageView = findViewById(R.id.overlay_new_task_message)
        inputView = findViewById(R.id.overlay_new_task_input)
        primaryButton = findViewById(R.id.overlay_new_task_primary)
        secondaryButton = findViewById(R.id.overlay_new_task_secondary)
        inputView.addTextChangedListener(
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
                        inputChangedListener?.invoke(s?.toString().orEmpty())
                    }
                }
            }
        )
    }

    fun bind(
        title: String,
        message: String,
        hint: String,
        inputText: String,
        inputEnabled: Boolean,
        primaryEnabled: Boolean,
        primaryLabel: String,
        secondaryLabel: String
    ) {
        titleView.text = title
        messageView.text = message
        messageView.visibility = if (message.isBlank()) GONE else VISIBLE
        inputView.hint = hint
        inputView.isEnabled = inputEnabled
        inputView.isFocusable = inputEnabled
        inputView.isFocusableInTouchMode = inputEnabled
        inputView.isCursorVisible = inputEnabled
        primaryButton.text = primaryLabel
        primaryButton.isEnabled = primaryEnabled
        secondaryButton.text = secondaryLabel
        if (inputView.text.toString() != inputText) {
            internalTextUpdate = true
            inputView.setText(inputText)
            inputView.setSelection(inputView.text.length)
            internalTextUpdate = false
        }
    }

    fun setOnInputChangedListener(listener: (String) -> Unit) {
        inputChangedListener = listener
    }

    fun setPrimaryClickListener(listener: OnClickListener) {
        primaryButton.setOnClickListener(listener)
    }

    fun setSecondaryClickListener(listener: OnClickListener) {
        secondaryButton.setOnClickListener(listener)
    }

    fun currentInputText(): String = inputView.text?.toString().orEmpty()
}
