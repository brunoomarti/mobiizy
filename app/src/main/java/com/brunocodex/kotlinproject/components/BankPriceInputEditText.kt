package com.brunocodex.kotlinproject.components

import android.content.Context
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.util.AttributeSet
import com.brunocodex.kotlinproject.utils.BankPriceInputFormatter
import com.google.android.material.textfield.TextInputEditText

class BankPriceInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    private var bankPriceWatcher: TextWatcher? = null

    init {
        inputType = InputType.TYPE_CLASS_NUMBER
        keyListener = DigitsKeyListener.getInstance("0123456789")
    }

    fun bindBankPrice(
        initialValue: String?,
        onValueChanged: ((String) -> Unit)? = null
    ) {
        bankPriceWatcher?.let(::removeTextChangedListener)
        bankPriceWatcher = BankPriceInputFormatter.attach(
            input = this,
            initialValue = initialValue,
            onValueChanged = onValueChanged
        )
    }
}
