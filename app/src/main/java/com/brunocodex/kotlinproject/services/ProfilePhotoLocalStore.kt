package com.brunocodex.kotlinproject.services

import android.content.Context
import java.io.File
import java.util.Locale

object ProfilePhotoLocalStore {

    data class Snapshot(
        val localPhotoPath: String?,
        val remotePhotoUrl: String?,
        val pendingSync: Boolean
    ) {
        fun localFileOrNull(): File? {
            val path = localPhotoPath?.trim().orEmpty()
            if (path.isBlank()) return null
            val file = File(path)
            return file.takeIf { it.exists() && it.isFile }
        }
    }

    private const val PREFS_NAME = "profile_photo_store"
    private const val KEY_LOCAL_PATH_SUFFIX = "local_path"
    private const val KEY_REMOTE_URL_SUFFIX = "remote_url"
    private const val KEY_PENDING_SYNC_SUFFIX = "pending_sync"
    private const val STORAGE_DIR = "profile_photo"
    private const val FILE_BASENAME = "profile_current"

    fun getSnapshot(context: Context, userId: String): Snapshot {
        val cleanUserId = normalizeUserId(userId) ?: return Snapshot(
            localPhotoPath = null,
            remotePhotoUrl = null,
            pendingSync = false
        )
        val prefs = prefs(context)
        val localPathKey = keyForUser(cleanUserId, KEY_LOCAL_PATH_SUFFIX)
        val remoteUrlKey = keyForUser(cleanUserId, KEY_REMOTE_URL_SUFFIX)
        val pendingSyncKey = keyForUser(cleanUserId, KEY_PENDING_SYNC_SUFFIX)

        val path = prefs.getString(localPathKey, null)?.trim().orEmpty()
        val file = if (path.isBlank()) null else File(path)
        if (file != null && !file.exists()) {
            prefs.edit()
                .remove(localPathKey)
                .putBoolean(pendingSyncKey, false)
                .apply()
            return Snapshot(
                localPhotoPath = null,
                remotePhotoUrl = prefs.getString(remoteUrlKey, null),
                pendingSync = false
            )
        }
        return Snapshot(
            localPhotoPath = file?.absolutePath,
            remotePhotoUrl = prefs.getString(remoteUrlKey, null),
            pendingSync = prefs.getBoolean(pendingSyncKey, false) && file?.exists() == true
        )
    }

    fun saveLocalPhoto(
        context: Context,
        userId: String,
        photoBytes: ByteArray,
        extension: String
    ): Snapshot {
        val appContext = context.applicationContext
        val cleanUserId = normalizeUserId(userId)
            ?: throw IllegalArgumentException("userId invalido para salvar foto de perfil.")
        val cleanExtension = normalizeExtension(extension)
        val dir = File(appContext.filesDir, "$STORAGE_DIR/$cleanUserId")
        if (!dir.exists()) dir.mkdirs()

        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(FILE_BASENAME)) {
                file.delete()
            }
        }

        val targetFile = File(dir, "$FILE_BASENAME.$cleanExtension")
        targetFile.writeBytes(photoBytes)

        val localPathKey = keyForUser(cleanUserId, KEY_LOCAL_PATH_SUFFIX)
        val remoteUrlKey = keyForUser(cleanUserId, KEY_REMOTE_URL_SUFFIX)
        val pendingSyncKey = keyForUser(cleanUserId, KEY_PENDING_SYNC_SUFFIX)
        val currentSnapshot = getSnapshot(appContext, cleanUserId)
        prefs(appContext).edit()
            .putString(localPathKey, targetFile.absolutePath)
            .putBoolean(pendingSyncKey, true)
            .putString(remoteUrlKey, currentSnapshot.remotePhotoUrl)
            .apply()

        return getSnapshot(appContext, cleanUserId)
    }

    fun markSynced(context: Context, userId: String, remotePhotoUrl: String) {
        val appContext = context.applicationContext
        val cleanUserId = normalizeUserId(userId) ?: return
        val cleanUrl = remotePhotoUrl.trim()
        val remoteUrlKey = keyForUser(cleanUserId, KEY_REMOTE_URL_SUFFIX)
        val pendingSyncKey = keyForUser(cleanUserId, KEY_PENDING_SYNC_SUFFIX)
        prefs(appContext).edit()
            .putBoolean(pendingSyncKey, false)
            .putString(remoteUrlKey, cleanUrl.ifBlank { null })
            .apply()
    }

    fun storeRemoteUrl(context: Context, userId: String, remotePhotoUrl: String?) {
        val appContext = context.applicationContext
        val cleanUserId = normalizeUserId(userId) ?: return
        val remoteUrlKey = keyForUser(cleanUserId, KEY_REMOTE_URL_SUFFIX)
        prefs(appContext).edit()
            .putString(remoteUrlKey, remotePhotoUrl?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun hasPendingSync(context: Context, userId: String): Boolean {
        return getSnapshot(context, userId).pendingSync
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizeExtension(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        return when (value) {
            "jpeg" -> "jpg"
            "jpg", "png", "webp" -> value
            else -> "jpg"
        }
    }

    private fun normalizeUserId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return value.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }

    private fun keyForUser(userId: String, suffix: String): String {
        return "${userId}_$suffix"
    }
}
