package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.brunocodex.kotlinproject.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class EmailInputFragment : Fragment(R.layout.fragment_email_input) {

    private data class FieldState(
        var hintText: CharSequence? = null,
        var inputTag: String = "",
        var validationTag: String = DEFAULT_VALIDATION_TAG,
        var textValue: String = "",
        var errorText: String? = null,
        var helperText: CharSequence? = null,
        var enabled: Boolean = true
    )

    private val fieldState = FieldState()
    private var onValueChanged: ((String) -> Unit)? = null
    private var isProgrammaticChange = false

    private lateinit var fieldLayout: TextInputLayout
    private lateinit var fieldInput: TextInputEditText

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fieldLayout = view.findViewById(R.id.emailFieldLayout)
        fieldInput = view.findViewById(R.id.emailFieldInput)

        fieldInput.doAfterTextChanged { value ->
            if (isProgrammaticChange) return@doAfterTextChanged
            val text = value?.toString().orEmpty()
            fieldState.textValue = text
            onValueChanged?.invoke(text)
        }

        applyFieldState()
    }

    fun configure(
        hintText: CharSequence,
        inputTag: String,
        validationTag: String = DEFAULT_VALIDATION_TAG
    ) {
        fieldState.hintText = hintText
        fieldState.inputTag = inputTag
        fieldState.validationTag = validationTag
        applyFieldState()
    }

    fun setOnValueChangedListener(listener: ((String) -> Unit)?) {
        onValueChanged = listener
    }

    fun setFieldText(value: String?) {
        fieldState.textValue = value.orEmpty()
        applyFieldState()
    }

    fun getFieldText(): String {
        return if (::fieldInput.isInitialized) {
            fieldInput.text?.toString().orEmpty()
        } else {
            fieldState.textValue
        }
    }

    fun setFieldError(error: String?) {
        fieldState.errorText = error
        applyFieldState()
    }

    fun clearFieldError() {
        setFieldError(null)
    }

    fun setFieldEnabled(enabled: Boolean) {
        fieldState.enabled = enabled
        applyFieldState()
    }

    fun setHelperText(helperText: CharSequence?) {
        fieldState.helperText = helperText
        applyFieldState()
    }

    private fun applyFieldState() {
        if (!::fieldLayout.isInitialized || !::fieldInput.isInitialized) return

        fieldLayout.hint = fieldState.hintText
        fieldLayout.tag = fieldState.validationTag
        fieldLayout.error = fieldState.errorText
        fieldLayout.helperText = fieldState.helperText
        fieldInput.tag = fieldState.inputTag

        val currentText = fieldInput.text?.toString().orEmpty()
        if (currentText != fieldState.textValue) {
            isProgrammaticChange = true
            fieldInput.setText(fieldState.textValue)
            fieldInput.setSelection(fieldInput.text?.length ?: 0)
            isProgrammaticChange = false
        }

        fieldInput.isEnabled = fieldState.enabled
        fieldInput.isFocusable = fieldState.enabled
        fieldInput.isFocusableInTouchMode = fieldState.enabled
        fieldInput.isClickable = fieldState.enabled
    }

    companion object {
        private const val DEFAULT_VALIDATION_TAG = "required|email"
    }
}
