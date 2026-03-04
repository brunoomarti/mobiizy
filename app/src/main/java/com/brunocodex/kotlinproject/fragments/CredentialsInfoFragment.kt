package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.RegisterViewModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class CredentialsInfoFragment : Fragment(R.layout.fragment_credentials_info), StepValidatable {

    private var tvHeaderStep: TextView? = null
    private val registerViewModel: RegisterViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvHeaderStep = view.findViewById(R.id.tvStepTitle)
        view.findViewById<TextView>(R.id.stepHeadline).text = getString(R.string.credentials_step_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.credentials_step_subtitle)

        val emailLayout = view.findViewById<TextInputLayout>(R.id.emailLayout)
        val passwordLayout = view.findViewById<TextInputLayout>(R.id.passwordLayout)
        val confirmPasswordLayout = view.findViewById<TextInputLayout>(R.id.confirmPasswordLayout)

        val email = view.findViewById<TextInputEditText>(R.id.emailInput)
        val pass = view.findViewById<TextInputEditText>(R.id.passwordInput)
        val confirm = view.findViewById<TextInputEditText>(R.id.confirmPasswordInput)

        email?.setText(registerViewModel.email.orEmpty())
        pass?.setText(registerViewModel.password.orEmpty())

        email?.doAfterTextChanged { registerViewModel.email = it?.toString() }
        pass?.doAfterTextChanged { registerViewModel.password = it?.toString() }

        setupLiveValidation(email, pass, confirm)
        applyProviderUiLocks(emailLayout, passwordLayout, confirmPasswordLayout, email, pass, confirm)
    }

    private fun setupLiveValidation(
        email: TextInputEditText?,
        pass: TextInputEditText?,
        confirm: TextInputEditText?
    ) {
        email?.doAfterTextChanged { email.error = null }
        pass?.doAfterTextChanged {
            pass.error = null
            validatePasswordMatch(pass, confirm)
        }
        confirm?.doAfterTextChanged {
            confirm.error = null
            validatePasswordMatch(pass, confirm)
        }
    }

    private fun applyProviderUiLocks(
        emailLayout: TextInputLayout,
        passwordLayout: TextInputLayout,
        confirmPasswordLayout: TextInputLayout,
        email: TextInputEditText?,
        pass: TextInputEditText?,
        confirm: TextInputEditText?
    ) {
        if (registerViewModel.lockEmailFromProvider) {
            email?.setText(registerViewModel.email.orEmpty())
            email?.isEnabled = false
            email?.isFocusable = false
            email?.isFocusableInTouchMode = false
            email?.isClickable = false
            emailLayout.helperText = getString(R.string.helper_prefilled_google_account)
        } else {
            email?.isEnabled = true
            email?.isFocusable = true
            email?.isFocusableInTouchMode = true
            email?.isClickable = true
            emailLayout.helperText = null
        }

        if (!registerViewModel.passwordRequired) {
            registerViewModel.password = null
            pass?.setText("")
            confirm?.setText("")

            passwordLayout.tag = ""
            confirmPasswordLayout.tag = ""
            passwordLayout.visibility = View.GONE
            confirmPasswordLayout.visibility = View.GONE
        } else {
            passwordLayout.tag = "required|min:6"
            confirmPasswordLayout.tag = "required|min:6"
            passwordLayout.visibility = View.VISIBLE
            confirmPasswordLayout.visibility = View.VISIBLE
        }
    }

    private fun validatePasswordMatch(pass: android.widget.EditText?, confirm: android.widget.EditText?) {
        if (pass == null || confirm == null) return

        val p = pass.text?.toString().orEmpty()
        val c = confirm.text?.toString().orEmpty()

        if (c.isBlank()) {
            confirm.error = null
            return
        }

        confirm.error = if (p != c) getString(R.string.error_passwords_do_not_match) else null
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false

        val email = root.findViewById<TextInputEditText>(R.id.emailInput)
        val emailValue = email?.text?.toString()?.trim().orEmpty()

        var ok = emailValue.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()
        if (showErrors && !ok) {
            email?.error = getString(R.string.error_enter_valid_email)
        } else {
            email?.error = null
        }

        if (!registerViewModel.passwordRequired) {
            return ok
        }

        ok = ok && StepValidationUtils.validateRequiredFields(root, showErrors)

        val pass = root.findViewById<TextInputEditText>(R.id.passwordInput)
        val confirm = root.findViewById<TextInputEditText>(R.id.confirmPasswordInput)

        if (pass != null && confirm != null) {
            val p = pass.text?.toString().orEmpty()
            val c = confirm.text?.toString().orEmpty()

            val match = p.isNotBlank() && p == c
            if (!match) {
                ok = false
                if (showErrors) confirm.error = getString(R.string.error_passwords_do_not_match)
            } else {
                confirm.error = null
            }
        }

        return ok
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tvHeaderStep = null
    }
}
