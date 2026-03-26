package com.brunocodex.kotlinproject.activities

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.navigation.ProfileNavigation
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val credentialManager by lazy { CredentialManager.create(this) }

    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var loginLoading: ProgressBar
    private var isLoginInProgress: Boolean = false
    private val googleWebClientId by lazy { getString(R.string.default_web_client_id).trim() }

    companion object {
        private const val TAG = "LoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        loginLoading = findViewById(R.id.loginLoading)
    }

    override fun onStart() {
        super.onStart()

        val user = auth.currentUser
        if (user != null) {
            routeAfterAuth(user.uid, user.isAnonymous)
        }
    }

    fun signIn(view: View) {
        if (isLoginInProgress) return

        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()

        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, getString(R.string.login_error_fill_email_password), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasInternetConnection()) {
            Toast.makeText(this, getString(R.string.error_no_internet_check), Toast.LENGTH_LONG).show()
            return
        }

        setLoginLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid
                if (uid == null) {
                    setLoginLoading(false)
                    Toast.makeText(this, getString(R.string.login_error_user_not_identified), Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                routeAfterAuth(uid, false)
            }
            .addOnFailureListener { e ->
                setLoginLoading(false)
                Toast.makeText(this, buildLoginErrorMessage(e), Toast.LENGTH_LONG).show()
            }
    }

    private fun routeAfterAuth(uid: String, isAnonymous: Boolean) {
        if (isAnonymous) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val profileCompleted = doc.getBoolean("profileCompleted") == true
                val profileType = ProfileNavigation.parseProfileType(doc.getString("profileType"))

                if (!profileCompleted || profileType == null) {
                    startActivity(Intent(this, RegisterActivity::class.java))
                    finish()
                    return@addOnSuccessListener
                }

                ProfileNavigation.goToHome(
                    activity = this,
                    profileType = profileType,
                    clearTask = true
                )
            }
            .addOnFailureListener {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
    }

    fun goToRegisterPage(view: View) {
        startActivity(Intent(this, RegisterActivity::class.java))
    }

    fun signInWithGoogle(view: View) {
        if (googleWebClientId.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.login_error_configure_google_client_id),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!hasInternetConnection()) {
            Toast.makeText(this, getString(R.string.error_no_internet_check), Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(googleWebClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            try {
                val result = credentialManager.getCredential(
                    context = this@LoginActivity,
                    request = request
                )
                handleGoogleCredential(result.credential)
            } catch (e: GetCredentialException) {
                Toast.makeText(
                    this@LoginActivity,
                    humanizeCredentialError(e),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleGoogleCredential(credential: Credential) {
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Toast.makeText(this, getString(R.string.login_error_google_credential_invalid), Toast.LENGTH_LONG).show()
            return
        }

        val idToken = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            Toast.makeText(this, getString(R.string.login_error_google_token_read), Toast.LENGTH_LONG).show()
            return
        }

        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(firebaseCredential)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                routeAfterAuth(uid, false)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, humanizeAuthError(e), Toast.LENGTH_LONG).show()
            }
    }

    fun forgotPassword(view: View) {
        showForgotPasswordDialog()
    }

    private fun showForgotPasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_forgot_password, null)
        val emailLayout = dialogView.findViewById<TextInputLayout>(R.id.forgotPasswordEmailLayout)
        val recoveryEmailInput = dialogView.findViewById<TextInputEditText>(R.id.forgotPasswordEmailInput)
        val prefilledEmail = emailInput.text?.toString()?.trim().orEmpty()

        if (prefilledEmail.isNotBlank()) {
            recoveryEmailInput.setText(prefilledEmail)
            recoveryEmailInput.setSelection(prefilledEmail.length)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.forgot_password_dialog_title)
            .setMessage(R.string.forgot_password_dialog_message)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.forgot_password_dialog_send_button, null)
            .create()

        dialog.setOnShowListener {
            val sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            sendButton.setOnClickListener {
                val email = recoveryEmailInput.text?.toString()?.trim().orEmpty()
                when {
                    email.isBlank() -> {
                        emailLayout.error = getString(R.string.please_enter_your_email)
                        return@setOnClickListener
                    }

                    !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                        emailLayout.error = getString(R.string.error_enter_valid_email)
                        return@setOnClickListener
                    }
                }

                emailLayout.error = null

                if (!hasInternetConnection()) {
                    Toast.makeText(this, getString(R.string.error_no_internet_check), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                sendButton.isEnabled = false
                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        if (prefilledEmail.isBlank()) {
                            emailInput.setText(email)
                        }
                        Toast.makeText(
                            this,
                            getString(R.string.login_reset_email_generic_result),
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                    }
                    .addOnFailureListener { e ->
                        if (e is FirebaseAuthInvalidUserException) {
                            Toast.makeText(
                                this,
                                getString(R.string.login_reset_email_generic_result),
                                Toast.LENGTH_LONG
                            ).show()
                            dialog.dismiss()
                            return@addOnFailureListener
                        }

                        sendButton.isEnabled = true
                        Toast.makeText(this, humanizeAuthError(e), Toast.LENGTH_LONG).show()
                    }
            }
        }

        dialog.show()
    }

    private fun hasInternetConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun humanizeCredentialError(e: GetCredentialException): String {
        val msg = e.message.orEmpty()
        Log.w(TAG, "CredentialManager error: $msg", e)
        return when {
            msg.contains("network", ignoreCase = true) -> getString(R.string.error_no_internet_try_again)
            msg.contains("canceled", ignoreCase = true) -> getString(R.string.login_error_google_cancelled)
            msg.contains("No credentials available", ignoreCase = true) ->
                getString(R.string.login_error_google_no_accounts)
            else -> getString(R.string.login_error_google_failed)
        }
    }

    private fun humanizeAuthError(e: Exception): String {
        val authCode = (e as? FirebaseAuthException)?.errorCode
        val firestoreCode = (e as? FirebaseFirestoreException)?.code?.name

        Log.e(TAG, "Auth error authCode=$authCode firestoreCode=$firestoreCode message=${e.message}", e)

        if (e is IOException) {
            return getString(R.string.error_no_internet_check)
        }

        return when (authCode) {
            "ERROR_INVALID_EMAIL" -> getString(R.string.error_invalid_email)
            "ERROR_USER_NOT_FOUND" -> getString(R.string.error_user_not_found)
            "ERROR_WRONG_PASSWORD" -> getString(R.string.error_wrong_password)
            "ERROR_INVALID_CREDENTIAL" -> getString(R.string.error_invalid_credentials)
            "ERROR_INVALID_LOGIN_CREDENTIAL" -> getString(R.string.error_email_or_password_incorrect)
            "ERROR_USER_DISABLED" -> getString(R.string.error_account_disabled)
            "ERROR_TOO_MANY_REQUESTS" -> getString(R.string.error_too_many_attempts)
            "ERROR_NETWORK_REQUEST_FAILED" -> getString(R.string.error_no_internet_check)
            else -> when (firestoreCode) {
                "UNAVAILABLE" -> getString(R.string.error_no_internet_now)
                else -> getString(R.string.login_error_generic)
            }
        }
    }

    private fun buildLoginErrorMessage(e: Exception): String {
        val base = humanizeAuthError(e)
        val authCode = (e as? FirebaseAuthException)?.errorCode.orEmpty()

        val shouldSuggestGoogle = authCode in setOf(
            "ERROR_WRONG_PASSWORD",
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_INVALID_LOGIN_CREDENTIAL"
        )

        if (!shouldSuggestGoogle) return base

        return getString(
            R.string.login_error_with_google_hint,
            base,
            getString(R.string.continue_with_google)
        )
    }

    private fun setLoginLoading(loading: Boolean) {
        isLoginInProgress = loading
        loginButton.isEnabled = !loading
        loginButton.text = if (loading) "" else getString(R.string.login)
        loginLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
