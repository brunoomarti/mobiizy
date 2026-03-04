package com.brunocodex.kotlinproject.utils

import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.textfield.TextInputEditText

object BankPriceInputFormatter {

    fun attach(
        input: TextInputEditText,
        initialValue: String?,
        onValueChanged: ((String) -> Unit)? = null
    ): TextWatcher {
        val initialFormatted = format(initialValue)
        input.setText(initialFormatted)
        input.setSelection(initialFormatted.length)
        onValueChanged?.invoke(initialFormatted)

        var isFormatting = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (isFormatting) return

                val currentValue = editable?.toString().orEmpty()
                val formattedValue = format(currentValue)

                if (currentValue == formattedValue) {
                    onValueChanged?.invoke(formattedValue)
                    return
                }

                isFormatting = true
                input.setText(formattedValue)
                input.setSelection(formattedValue.length)
                isFormatting = false

                onValueChanged?.invoke(formattedValue)
            }
        }

        input.addTextChangedListener(watcher)
        return watcher
    }

    fun format(value: String?): String {
        val digits = value.orEmpty()
            .filter(Char::isDigit)
            .trimStart('0')

        val normalizedDigits = if (digits.isBlank()) "0" else digits
        val normalizedWithCents = normalizedDigits.padStart(3, '0')

        val integerPart = normalizedWithCents
            .dropLast(2)
            .trimStart('0')
            .ifBlank { "0" }

        val centsPart = normalizedWithCents.takeLast(2)
        return "$integerPart,$centsPart"
    }
}
