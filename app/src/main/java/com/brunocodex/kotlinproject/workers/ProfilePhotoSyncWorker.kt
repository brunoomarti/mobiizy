package com.brunocodex.kotlinproject.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brunocodex.kotlinproject.services.ProfilePhotoLocalStore
import com.brunocodex.kotlinproject.services.ProfilePhotoSyncService

class ProfilePhotoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!ProfilePhotoLocalStore.hasPendingSync(applicationContext)) {
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

