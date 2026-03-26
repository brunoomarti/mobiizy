package com.brunocodex.kotlinproject.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.brunocodex.kotlinproject.workers.ProfilePhotoSyncWorker
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

object ProfilePhotoSyncScheduler {

    private const val WORK_NAME = "profile_photo_sync_work"

    fun enqueueIfPending(context: Context) {
        val appContext = context.applicationContext
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!ProfilePhotoLocalStore.hasPendingSync(appContext, userId)) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ProfilePhotoSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                20,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
