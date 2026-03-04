package com.brunocodex.kotlinproject.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.brunocodex.kotlinproject.navigation.ProfileNavigation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AuthGateActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = auth.currentUser

        if (user == null) {
            goTo(LoginActivity::class.java)
            return
        }

        if (user.isAnonymous) {
            goTo(RegisterActivity::class.java)
            return
        }

        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val profileCompleted = doc.getBoolean("profileCompleted") == true
                val profileType = ProfileNavigation.parseProfileType(doc.getString("profileType"))

                if (!profileCompleted || profileType == null) {
                    goTo(RegisterActivity::class.java)
                    return@addOnSuccessListener
                }

                ProfileNavigation.goToHome(
                    activity = this,
                    profileType = profileType,
                    clearTask = true
                )
            }
            .addOnFailureListener {
                goTo(LoginActivity::class.java)
            }
    }

    private fun goTo(target: Class<*>) {
        startActivity(Intent(this, target))
        finish()
    }
}
