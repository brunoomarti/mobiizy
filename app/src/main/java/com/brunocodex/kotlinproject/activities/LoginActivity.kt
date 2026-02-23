package com.brunocodex.kotlinproject.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.brunocodex.kotlinproject.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var googleSignInClient: GoogleSignInClient
    private val googleWebClientId by lazy { getString(R.string.default_web_client_id).trim() }

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                handleGoogleAccount(account)
            } catch (e: ApiException) {
                val statusName = GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)
                val details = e.message?.takeIf { it.isNotBlank() } ?: "sem detalhes"
                Toast.makeText(
                    this,
                    "Falha no login Google (code=${e.statusCode}, status=$statusName): $details",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (googleWebClientId.isNotBlank()) {
            gsoBuilder.requestIdToken(googleWebClientId)
        }
        val gso = gsoBuilder.build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    override fun onStart() {
        super.onStart()

        val user = auth.currentUser
        if (user != null) {
            routeAfterAuth(user.uid, user.isAnonymous)
        }
    }

    fun signIn(view: View) {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()

        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Preencha e-mail e senha", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                routeAfterAuth(uid, false)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erro ao entrar: ${e.message}", Toast.LENGTH_LONG).show()
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
                val target = if (profileCompleted) MainActivity::class.java else RegisterActivity::class.java
                startActivity(Intent(this, target))
                finish()
            }
            .addOnFailureListener {
                // fallback
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
                "Configure default_web_client_id no google-services.json para habilitar login Google.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun handleGoogleAccount(account: GoogleSignInAccount) {
        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            Toast.makeText(this, "Nao foi possivel obter token do Google.", Toast.LENGTH_LONG).show()
            return
        }

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                routeAfterAuth(uid, false)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erro ao autenticar Google: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun forgotPassword(view: View) {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        if (email.isBlank()) {
            Toast.makeText(this, "Digite seu e-mail para recuperar a senha", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Toast.makeText(this, "E-mail de recuperação enviado", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}
