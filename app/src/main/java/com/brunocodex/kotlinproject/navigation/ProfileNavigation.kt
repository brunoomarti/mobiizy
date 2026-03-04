package com.brunocodex.kotlinproject.navigation

import android.app.Activity
import android.content.Intent
import com.brunocodex.kotlinproject.activities.ProviderHomeActivity
import com.brunocodex.kotlinproject.activities.RenterHomeActivity
import com.brunocodex.kotlinproject.model.ProfileType

object ProfileNavigation {

    fun parseProfileType(raw: String?): ProfileType? {
        val normalized = raw?.trim()?.uppercase() ?: return null
        return runCatching { ProfileType.valueOf(normalized) }.getOrNull()
    }

    fun homeActivityFor(profileType: ProfileType): Class<out Activity> {
        return when (profileType) {
            ProfileType.RENTER -> RenterHomeActivity::class.java
            ProfileType.PROVIDER -> ProviderHomeActivity::class.java
        }
    }

    fun goToHome(activity: Activity, profileType: ProfileType, clearTask: Boolean) {
        val intent = Intent(activity, homeActivityFor(profileType))
        if (clearTask) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
