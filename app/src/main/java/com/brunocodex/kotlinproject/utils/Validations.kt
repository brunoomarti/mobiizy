package com.brunocodex.kotlinproject.utils

import android.util.Patterns
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
                    nameInput.error = "Name is required"
                    nameInput.requestFocus()
                    return false
                }
            }

            if (email.isEmpty()) {
                emailInput.error = "Email is required"
                emailInput.requestFocus()
                return false
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailInput.error = "Please enter a valid email"
                emailInput.requestFocus()
                return false
            }

            if (password.isEmpty()) {
                passwordInput.error = "Password is required"
                passwordInput.requestFocus()
                return false
            }

            if (password.length < 6) {
                passwordInput.error = "Password must be at least 6 characters"
                passwordInput.requestFocus()
                return false
            }

            return true
        }
    }
}