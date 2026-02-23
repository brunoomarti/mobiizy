package com.brunocodex.kotlinproject.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.brunocodex.kotlinproject.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            prefs.edit().putBoolean(KEY_PERMISSIONS_PROMPTED, true).apply()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        maybeRequestRuntimePermissions()
    }

    fun onLogoutClicked(view: View) {
        val webClientId = getString(R.string.default_web_client_id).trim()
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        if (webClientId.isNotBlank()) {
            gsoBuilder.requestIdToken(webClientId)
        }

        val googleClient = GoogleSignIn.getClient(this, gsoBuilder.build())
        googleClient.signOut().addOnCompleteListener {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun maybeRequestRuntimePermissions() {
        if (prefs.getBoolean(KEY_PERMISSIONS_PROMPTED, false)) return

        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                missing += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                missing += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                missing += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        if (missing.isEmpty()) {
            prefs.edit().putBoolean(KEY_PERMISSIONS_PROMPTED, true).apply()
            return
        }

        permissionsLauncher.launch(missing.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val KEY_PERMISSIONS_PROMPTED = "runtime_permissions_prompted"
    }
}
