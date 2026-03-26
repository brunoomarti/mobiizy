package com.brunocodex.kotlinproject.fragments

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.utils.StepValidatable
import com.brunocodex.kotlinproject.utils.StepValidationUtils
import com.brunocodex.kotlinproject.viewmodels.RegisterViewModel

class CredentialsInfoFragment : Fragment(R.layout.fragment_credentials_info), StepValidatable {

    private var tvHeaderStep: TextView? = null
    private val registerViewModel: RegisterViewModel by activityViewModels()
    private lateinit var emailInputFragment: EmailInputFragment
    private lateinit var passwordInputFragment: PasswordInputFragment
    private lateinit var confirmPasswordInputFragment: PasswordInputFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvHeaderStep = view.findViewById(R.id.tvStepTitle)
        view.findViewById<TextView>(R.id.stepHeadline).text = getString(R.string.credentials_step_headline)
        view.findViewById<TextView>(R.id.stepSubtitle).text =
            getString(R.string.credentials_step_subtitle)

        ensureEmailInputFragment()
        ensurePasswordInputFragments()
        configureEmailInputFragment()
        configurePasswordInputFragments()

        emailInputFragment.setFieldText(registerViewModel.email.orEmpty())
        passwordInputFragment.setFieldText(registerViewModel.password.orEmpty())
        confirmPasswordInputFragment.setFieldText("")

        setupLiveValidation()
        applyProviderUiLocks()
    }

    private fun ensureEmailInputFragment() {
        emailInputFragment = ensureEmailInputFragment(R.id.emailFieldContainer)
    }

    private fun ensureEmailInputFragment(containerId: Int): EmailInputFragment {
        val existing = childFragmentManager.findFragmentById(containerId) as? EmailInputFragment
        if (existing != null) return existing

        val fragment = EmailInputFragment()
        childFragmentManager.beginTransaction()
            .replace(containerId, fragment)
            .commitNow()
        return fragment
    }

    private fun ensurePasswordInputFragments() {
        passwordInputFragment = ensurePasswordInputFragment(R.id.passwordFieldContainer)
        confirmPasswordInputFragment = ensurePasswordInputFragment(R.id.confirmPasswordFieldContainer)
    }

    private fun ensurePasswordInputFragment(containerId: Int): PasswordInputFragment {
        val existing = childFragmentManager.findFragmentById(containerId) as? PasswordInputFragment
        if (existing != null) return existing

        val fragment = PasswordInputFragment()
        childFragmentManager.beginTransaction()
            .replace(containerId, fragment)
            .commitNow()
        return fragment
    }

    private fun configureEmailInputFragment() {
        emailInputFragment.configure(
            hintText = getString(R.string.email),
            inputTag = FIELD_TAG_EMAIL
        )
    }

    private fun configurePasswordInputFragments() {
        passwordInputFragment.configure(
            hintText = getString(R.string.password),
            inputTag = FIELD_TAG_PASSWORD
        )
        confirmPasswordInputFragment.configure(
            hintText = getString(R.string.hint_confirm_password),
            inputTag = FIELD_TAG_CONFIRM_PASSWORD
        )
    }

    private fun setupLiveValidation() {
        emailInputFragment.setOnValueChangedListener { value ->
            registerViewModel.email = value
            emailInputFragment.clearFieldError()
        }
        passwordInputFragment.setOnValueChangedListener { value ->
            registerViewModel.password = value
            passwordInputFragment.clearFieldError()
            validatePasswordMatch()
        }
        confirmPasswordInputFragment.setOnValueChangedListener {
            confirmPasswordInputFragment.clearFieldError()
            validatePasswordMatch()
        }
    }

    private fun applyProviderUiLocks() {
        if (registerViewModel.lockEmailFromProvider) {
            emailInputFragment.setFieldText(registerViewModel.email.orEmpty())
            emailInputFragment.setFieldEnabled(false)
            emailInputFragment.setHelperText(getString(R.string.helper_prefilled_google_account))
        } else {
            emailInputFragment.setFieldEnabled(true)
            emailInputFragment.setHelperText(null)
        }

        if (!registerViewModel.passwordRequired) {
            registerViewModel.password = null
            passwordInputFragment.setFieldText("")
            confirmPasswordInputFragment.setFieldText("")
            passwordInputFragment.setValidationEnabled(false)
            confirmPasswordInputFragment.setValidationEnabled(false)
            passwordInputFragment.setFieldVisible(false)
            confirmPasswordInputFragment.setFieldVisible(false)
        } else {
            passwordInputFragment.setValidationEnabled(true)
            confirmPasswordInputFragment.setValidationEnabled(true)
            passwordInputFragment.setFieldVisible(true)
            confirmPasswordInputFragment.setFieldVisible(true)
        }
    }

    private fun validatePasswordMatch() {
        val p = passwordInputFragment.getFieldText()
        val c = confirmPasswordInputFragment.getFieldText()

        if (c.isBlank()) {
            confirmPasswordInputFragment.clearFieldError()
            return
        }

        confirmPasswordInputFragment.setFieldError(
            if (p != c) getString(R.string.error_passwords_do_not_match) else null
        )
    }

    override fun validateStep(showErrors: Boolean): Boolean {
        val root = view ?: return false

        val emailValue = emailInputFragment.getFieldText().trim()

        var ok = emailValue.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()
        if (showErrors && !ok) {
            emailInputFragment.setFieldError(getString(R.string.error_enter_valid_email))
        } else {
            emailInputFragment.clearFieldError()
        }

        if (!registerViewModel.passwordRequired) {
            return ok
        }

        ok = ok && StepValidationUtils.validateRequiredFields(root, showErrors)

        val p = passwordInputFragment.getFieldText()
        val c = confirmPasswordInputFragment.getFieldText()
        val match = p.isNotBlank() && p == c
        if (!match) {
            ok = false
            if (showErrors) {
                confirmPasswordInputFragment.setFieldError(getString(R.string.error_passwords_do_not_match))
            }
        } else {
            confirmPasswordInputFragment.clearFieldError()
        }

        return ok
    }

    override fun onDestroyView() {
        if (::emailInputFragment.isInitialized) {
            emailInputFragment.setOnValueChangedListener(null)
        }
        if (::passwordInputFragment.isInitialized) {
            passwordInputFragment.setOnValueChangedListener(null)
        }
        if (::confirmPasswordInputFragment.isInitialized) {
            confirmPasswordInputFragment.setOnValueChangedListener(null)
        }
        super.onDestroyView()
        tvHeaderStep = null
    }

    companion object {
        private const val FIELD_TAG_EMAIL = "email"
        private const val FIELD_TAG_PASSWORD = "password"
        private const val FIELD_TAG_CONFIRM_PASSWORD = "confirm_password"
    }
}
