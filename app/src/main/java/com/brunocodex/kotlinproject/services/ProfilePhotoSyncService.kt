package com.brunocodex.kotlinproject.services

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ProfilePhotoSyncService {

    sealed class SyncResult {
        data object NoPendingPhoto : SyncResult()
        data class Synced(val remotePhotoUrl: String) : SyncResult()
        data class RetryableFailure(val cause: Throwable) : SyncResult()
        data class PermanentFailure(val cause: Throwable) : SyncResult()
    }

    suspend fun syncPendingPhoto(context: Context): SyncResult {
        val appContext = context.applicationContext
        val snapshot = ProfilePhotoLocalStore.getSnapshot(appContext)
        if (!snapshot.pendingSync) return SyncResult.NoPendingPhoto

        if (!SupabaseStorageService.isReady()) {
            return SyncResult.PermanentFailure(
                IllegalStateException("Supabase nao configurado para sincronizar foto de perfil.")
            )
        }

        val localFile = snapshot.localFileOrNull()
            ?: return SyncResult.PermanentFailure(
                IllegalStateException("Foto local pendente nao encontrada no armazenamento do app.")
            )

        val user = FirebaseAuth.getInstance().currentUser
            ?: return SyncResult.RetryableFailure(
                IllegalStateException("Usuario nao autenticado para sincronizar foto de perfil.")
            )

        return runCatching {
            val bytes = withContext(Dispatchers.IO) { localFile.readBytes() }
            val extension = localFile.extension.ifBlank { "jpg" }

            val remoteUrl = SupabaseStorageService.uploadProfilePhoto(
                ownerId = user.uid,
                bytes = bytes,
                fileExtension = extension
            )

            val payload = mapOf(
                "photoUrl" to remoteUrl,
                "photoURL" to remoteUrl,
                "profilePhotoUrl" to remoteUrl,
                "avatarUrl" to remoteUrl,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.uid)
                .set(payload, SetOptions.merge())
                .awaitResult()

            runCatching {
                user.updateProfile(
                    UserProfileChangeRequest.Builder()
                        .setPhotoUri(Uri.parse(remoteUrl))
                        .build()
                ).awaitResult()
            }

            ProfilePhotoLocalStore.markSynced(appContext, remoteUrl)
            remoteUrl
        }.fold(
            onSuccess = { remoteUrl -> SyncResult.Synced(remoteUrl) },
            onFailure = { throwable -> SyncResult.RetryableFailure(throwable) }
        )
    }

    private suspend fun <T> Task<T>.awaitResult(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                continuation.resume(result)
            }
            addOnFailureListener { error ->
                continuation.resumeWithException(error)
            }
            addOnCanceledListener {
                continuation.cancel()
            }
        }
    }
}

