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
    private const val KEY_LOCAL_PATH = "local_path"
    private const val KEY_REMOTE_URL = "remote_url"
    private const val KEY_PENDING_SYNC = "pending_sync"
    private const val STORAGE_DIR = "profile_photo"
    private const val FILE_BASENAME = "profile_current"

    fun getSnapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        val path = prefs.getString(KEY_LOCAL_PATH, null)?.trim().orEmpty()
        val file = if (path.isBlank()) null else File(path)
        if (file != null && !file.exists()) {
            prefs.edit().remove(KEY_LOCAL_PATH).apply()
            return Snapshot(
                localPhotoPath = null,
                remotePhotoUrl = prefs.getString(KEY_REMOTE_URL, null),
                pendingSync = false
            )
        }
        return Snapshot(
            localPhotoPath = file?.absolutePath,
            remotePhotoUrl = prefs.getString(KEY_REMOTE_URL, null),
            pendingSync = prefs.getBoolean(KEY_PENDING_SYNC, false) && file?.exists() == true
        )
    }

    fun saveLocalPhoto(
        context: Context,
        photoBytes: ByteArray,
        extension: String
    ): Snapshot {
        val appContext = context.applicationContext
        val cleanExtension = normalizeExtension(extension)
        val dir = File(appContext.filesDir, STORAGE_DIR)
        if (!dir.exists()) dir.mkdirs()

        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(FILE_BASENAME)) {
                file.delete()
            }
        }

        val targetFile = File(dir, "$FILE_BASENAME.$cleanExtension")
        targetFile.writeBytes(photoBytes)

        val currentSnapshot = getSnapshot(appContext)
        prefs(appContext).edit()
            .putString(KEY_LOCAL_PATH, targetFile.absolutePath)
            .putBoolean(KEY_PENDING_SYNC, true)
            .putString(KEY_REMOTE_URL, currentSnapshot.remotePhotoUrl)
            .apply()

        return getSnapshot(appContext)
    }

    fun markSynced(context: Context, remotePhotoUrl: String) {
        val appContext = context.applicationContext
        val cleanUrl = remotePhotoUrl.trim()
        prefs(appContext).edit()
            .putBoolean(KEY_PENDING_SYNC, false)
            .putString(KEY_REMOTE_URL, cleanUrl.ifBlank { null })
            .apply()
    }

    fun storeRemoteUrl(context: Context, remotePhotoUrl: String?) {
        val appContext = context.applicationContext
        prefs(appContext).edit()
            .putString(KEY_REMOTE_URL, remotePhotoUrl?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun hasPendingSync(context: Context): Boolean {
        return getSnapshot(context).pendingSync
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
}

