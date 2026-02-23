package com.brunocodex.kotlinproject.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AuthGateActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = auth.currentUser

        // 1) Não logado
        if (user == null) {
            goTo(LoginActivity::class.java)
            return
        }

        // 2) Usuário anônimo (rascunho de cadastro)
        if (user.isAnonymous) {
            goTo(RegisterActivity::class.java)
            return
        }

        // 3) Usuário real -> verifica se perfil foi concluído
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val profileCompleted = doc.getBoolean("profileCompleted") == true
                if (profileCompleted) {
                    goTo(MainActivity::class.java)
                } else {
                    goTo(RegisterActivity::class.java)
                }
            }
            .addOnFailureListener {
                // fallback simples
                goTo(LoginActivity::class.java)
            }
    }

    private fun goTo(target: Class<*>) {
        startActivity(Intent(this, target))
        finish()
    }
}