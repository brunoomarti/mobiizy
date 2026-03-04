package com.brunocodex.kotlinproject.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.brunocodex.kotlinproject.navigation.ProfileNavigation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        if (user.isAnonymous) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        db.collection("users").document(user.uid).get()
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
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
    }
}
