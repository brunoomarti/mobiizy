package com.brunocodex.kotlinproject.utils

import android.util.Patterns
import com.brunocodex.kotlinproject.R
import com.google.android.material.textfield.TextInputEditText

class Validations {
    companion object {
        @JvmStatic
        fun validateUserInput(
            emailInput: TextInputEditText,
            passwordInput: TextInputEditText,
            nameInput: TextInputEditText? = null,
        ) : Boolean {

            nameInput?.error = null
            emailInput.error = null
            passwordInput.error = null

            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (nameInput != null) {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameInput.error = nameInput.context.getString(R.string.validation_name_required)
                    nameInput.requestFocus()
                    return false
                }
            }

            if (email.isEmpty()) {
                emailInput.error = emailInput.context.getString(R.string.validation_email_required)
                emailInput.requestFocus()
                return false
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailInput.error = emailInput.context.getString(R.string.validation_email_invalid)
                emailInput.requestFocus()
                return false
            }

            if (password.isEmpty()) {
                passwordInput.error = passwordInput.context.getString(R.string.validation_password_required)
                passwordInput.requestFocus()
                return false
            }

            if (password.length < 6) {
                passwordInput.error = passwordInput.context.getString(R.string.validation_password_min_6)
                passwordInput.requestFocus()
                return false
            }

            return true
        }
    }
}
