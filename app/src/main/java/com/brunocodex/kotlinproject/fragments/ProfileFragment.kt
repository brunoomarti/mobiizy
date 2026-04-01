package com.brunocodex.kotlinproject.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.brunocodex.kotlinproject.R
import com.brunocodex.kotlinproject.activities.BaseHomeActivity
import com.brunocodex.kotlinproject.activities.LanguageSettingsActivity
import com.brunocodex.kotlinproject.activities.SettingsActivity
import com.brunocodex.kotlinproject.services.ProfilePhotoLocalStore
import com.brunocodex.kotlinproject.services.ProfilePhotoSyncScheduler
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var photoLoadToken: Int = 0
    private var logoutInProgress: Boolean = false
    private lateinit var btnProfileLogout: MaterialButton
    private lateinit var progressProfileLogout: CircularProgressIndicator

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindProfileHeader(view)
        bindQuickActions(view)
        ProfilePhotoSyncScheduler.enqueueIfPending(requireContext())
        btnProfileLogout = view.findViewById(R.id.btnProfileLogout)
        progressProfileLogout = view.findViewById(R.id.progressProfileLogout)
        setLogoutLoading(loading = false)

        btnProfileLogout.setOnClickListener { clicked ->
            if (logoutInProgress) return@setOnClickListener
            logoutInProgress = true
            setLogoutLoading(loading = true)

            val host = activity as? BaseHomeActivity
            if (host == null) {
                logoutInProgress = false
                setLogoutLoading(loading = false)
                return@setOnClickListener
            }
            host.onLogoutClicked(clicked)
        }
    }

    override fun onResume() {
        super.onResume()
        ProfilePhotoSyncScheduler.enqueueIfPending(requireContext())
        view?.let { bindProfileHeader(it) }
    }

    private fun bindProfileHeader(root: View) {
        val user = FirebaseAuth.getInstance().currentUser
        val displayName = resolveDisplayName(
            rawName = user?.displayName,
            email = user?.email
        )
        val email = user?.email
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_email_unavailable)
        val initial = displayName
            .firstOrNull()
            ?.toString()
            ?.uppercase(Locale.getDefault())
            ?: getString(R.string.profile_default_initial)

        root.findViewById<TextView>(R.id.tvProfileHeroName).text = displayName
        root.findViewById<TextView>(R.id.tvProfileHeroEmail).text = email

        val fallback = root.findViewById<TextView>(R.id.tvProfileHeroInitial)
        fallback.text = initial

        renderProfilePhoto(
            root = root,
            authPhotoUrl = user?.photoUrl?.toString(),
            userId = user?.uid
        )
    }

    private fun renderProfilePhoto(root: View, authPhotoUrl: String?, userId: String?) {
        val photoView = root.findViewById<ImageView>(R.id.ivProfileHeroPhoto)
        val fallbackView = root.findViewById<TextView>(R.id.tvProfileHeroInitial)
        val syncBadgeView = root.findViewById<View>(R.id.profileHeroPhotoSyncBadge)

        val snapshot = userId?.let { id ->
            ProfilePhotoLocalStore.getSnapshot(requireContext(), id)
        } ?: ProfilePhotoLocalStore.Snapshot(
            localPhotoPath = null,
            remotePhotoUrl = null,
            pendingSync = false
        )
        val localFile = snapshot.localFileOrNull()
        if (localFile != null) {
            photoLoadToken++
            val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
            if (bitmap != null) {
                photoView.setImageBitmap(bitmap)
                photoView.visibility = View.VISIBLE
                fallbackView.visibility = View.GONE
                syncBadgeView.visibility = if (snapshot.pendingSync) View.VISIBLE else View.GONE
            } else {
                photoView.setImageDrawable(null)
                photoView.visibility = View.GONE
                fallbackView.visibility = View.VISIBLE
                syncBadgeView.visibility = View.GONE
            }
            return
        }

        syncBadgeView.visibility = View.GONE

        val photoUrl = snapshot.remotePhotoUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: authPhotoUrl?.trim()?.takeIf { it.isNotBlank() }

        if (photoUrl.isNullOrBlank()) {
            photoLoadToken++
            photoView.setImageDrawable(null)
            photoView.visibility = View.GONE
            fallbackView.visibility = View.VISIBLE
            return
        }

        val requestToken = ++photoLoadToken
        photoView.setImageDrawable(null)
        photoView.visibility = View.GONE
        fallbackView.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = runCatching {
                withContext(Dispatchers.IO) { loadBitmapFromUrl(photoUrl) }
            }.getOrNull()

            if (requestToken != photoLoadToken) return@launch

            if (bitmap == null) {
                photoView.setImageDrawable(null)
                photoView.visibility = View.GONE
                fallbackView.visibility = View.VISIBLE
                return@launch
            }

            photoView.setImageBitmap(bitmap)
            photoView.visibility = View.VISIBLE
            fallbackView.visibility = View.GONE
        }
    }

    private fun loadBitmapFromUrl(photoUrl: String): Bitmap? {
        return URL(photoUrl).openConnection().apply {
            connectTimeout = 15000
            readTimeout = 15000
        }.getInputStream().use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    private fun bindQuickActions(root: View) {
        root.findViewById<View>(R.id.profileActionPersonalData).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        root.findViewById<View>(R.id.profileActionLanguage).setOnClickListener {
            startActivity(Intent(requireContext(), LanguageSettingsActivity::class.java))
        }
    }

    private fun resolveDisplayName(rawName: String?, email: String?): String {
        val cleanedName = rawName?.trim().orEmpty()
        if (cleanedName.isNotBlank()) return cleanedName
        val emailName = email
            ?.substringBefore("@")
            ?.replace(Regex("[._-]+"), " ")
            ?.trim()
            .orEmpty()
        return emailName.ifBlank { getString(R.string.dashboard_user_fallback_name) }
    }

    private fun setLogoutLoading(loading: Boolean) {
        btnProfileLogout.isEnabled = !loading
        btnProfileLogout.text = if (loading) "" else getString(R.string.profile_logout_button)
        progressProfileLogout.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
