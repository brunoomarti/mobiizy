package com.brunocodex.kotlinproject.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brunocodex.kotlinproject.services.ProfilePhotoLocalStore
import com.brunocodex.kotlinproject.services.ProfilePhotoSyncService
import com.google.firebase.auth.FirebaseAuth

class ProfilePhotoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        if (!ProfilePhotoLocalStore.hasPendingSync(applicationContext, userId)) {
            return Result.success()
        }

        return when (ProfilePhotoSyncService.syncPendingPhoto(applicationContext)) {
            is ProfilePhotoSyncService.SyncResult.NoPendingPhoto,
            is ProfilePhotoSyncService.SyncResult.Synced -> Result.success()

            is ProfilePhotoSyncService.SyncResult.RetryableFailure -> Result.retry()
            is ProfilePhotoSyncService.SyncResult.PermanentFailure -> Result.failure()
        }
    }
}
