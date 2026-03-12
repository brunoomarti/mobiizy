package com.brunocodex.kotlinproject.services

import com.brunocodex.kotlinproject.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object SupabaseStorageService {

    private const val DEFAULT_BUCKET = "vehicle-media"
    private const val PROFILE_PHOTO_BUCKET = "profile_photo"

    private val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private val resolvedBucketName: String
        get() = BuildConfig.SUPABASE_BUCKET.ifBlank { DEFAULT_BUCKET }

    private val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Storage)
        }
    }

    fun isReady(): Boolean = isConfigured

    fun missingConfigurationKeys(): List<String> {
        val missing = mutableListOf<String>()
        if (BuildConfig.SUPABASE_URL.isBlank()) missing += "SUPABASE_URL"
        if (BuildConfig.SUPABASE_ANON_KEY.isBlank()) missing += "SUPABASE_ANON_KEY"
        return missing
    }

    fun getBucketName(): String = resolvedBucketName

    fun getProfilePhotoBucketName(): String = PROFILE_PHOTO_BUCKET

    suspend fun uploadVehiclePhoto(
        localFile: File,
        ownerId: String,
        vehiclePlate: String,
        mediaCategory: String
    ): String {
        require(localFile.exists()) { "File does not exist: ${localFile.absolutePath}" }
        ensureConfigured()

        val extension = localFile.extension.lowercase(Locale.ROOT).ifBlank { "jpg" }
        return uploadVehiclePhotoByPlate(
            ownerId = ownerId,
            vehiclePlate = vehiclePlate,
            mediaCategory = mediaCategory,
            bytes = localFile.readBytes(),
            fileExtension = extension
        )
    }

    suspend fun uploadVehiclePhotoByPlate(
        ownerId: String,
        vehiclePlate: String,
        mediaCategory: String,
        bytes: ByteArray,
        fileExtension: String = "jpg"
    ): String {
        ensureConfigured()
        val cleanOwner = sanitizePathPart(ownerId.ifBlank { "anonymous" })
        val cleanPlate = sanitizePlate(vehiclePlate)
        val cleanCategory = sanitizePathPart(mediaCategory.ifBlank { "photo" })
        val cleanExtension = sanitizePathPart(fileExtension.lowercase(Locale.ROOT)).ifBlank { "jpg" }
        val remotePath = "clients/$cleanOwner/$cleanPlate/${cleanCategory}_${System.currentTimeMillis()}.$cleanExtension"

        return uploadBytes(remotePath = remotePath, bytes = bytes, upsert = true)
    }

    suspend fun uploadBytes(
        remotePath: String,
        bytes: ByteArray,
        upsert: Boolean = true
    ): String {
        ensureConfigured()
        return uploadBytesToBucket(
            bucketName = resolvedBucketName,
            remotePath = remotePath,
            bytes = bytes,
            upsert = upsert
        )
    }

    suspend fun uploadProfilePhoto(
        ownerId: String,
        bytes: ByteArray,
        fileExtension: String = "jpg"
    ): String {
        ensureConfigured()
        val cleanOwner = sanitizePathPart(ownerId.ifBlank { "anonymous" })
        val cleanExtension = sanitizePathPart(fileExtension.lowercase(Locale.ROOT)).ifBlank { "jpg" }
        val remotePath = "users/$cleanOwner/profile_${System.currentTimeMillis()}.$cleanExtension"

        return uploadBytesToBucket(
            bucketName = PROFILE_PHOTO_BUCKET,
            remotePath = remotePath,
            bytes = bytes,
            upsert = true
        )
    }

    fun publicUrl(remotePath: String): String {
        ensureConfigured()
        return client.storage.from(resolvedBucketName).publicUrl(sanitizeRemotePath(remotePath))
    }

    suspend fun deleteByPublicUrl(publicUrl: String): Boolean {
        ensureConfigured()
        val remotePath = remotePathFromPublicUrl(publicUrl) ?: return false
        client.storage.from(resolvedBucketName).delete(remotePath)
        return true
    }

    suspend fun downloadBytesByPublicUrl(publicUrl: String): ByteArray {
        ensureConfigured()
        val remotePath = remotePathFromPublicUrl(publicUrl)
            ?: error("Nao foi possivel extrair o path do arquivo da URL informada")

        return runCatching {
            client.storage.from(resolvedBucketName).downloadPublic(remotePath)
        }.getOrElse {
            client.storage.from(resolvedBucketName).downloadAuthenticated(remotePath)
        }
    }

    private fun ensureConfigured() {
        check(isConfigured) {
            "Supabase nao configurado. Defina SUPABASE_URL e SUPABASE_ANON_KEY no local.properties."
        }
    }

    private suspend fun uploadBytesToBucket(
        bucketName: String,
        remotePath: String,
        bytes: ByteArray,
        upsert: Boolean
    ): String {
        val cleanPath = sanitizeRemotePath(remotePath)
        client.storage.from(bucketName).upload(
            path = cleanPath,
            data = bytes,
            upsert = upsert
        )
        return client.storage.from(bucketName).publicUrl(cleanPath)
    }

    private fun sanitizeRemotePath(path: String): String {
        return path.trim().replace("\\", "/").removePrefix("/")
    }

    private fun sanitizePathPart(input: String): String {
        return input.trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-zA-Z0-9_-]".toRegex(), "")
            .ifBlank { "media" }
    }

    private fun sanitizePlate(plate: String): String {
        val raw = plate.uppercase(Locale.ROOT).replace("[^A-Z0-9]".toRegex(), "")
        return raw.ifBlank { "SEM_PLACA" }
    }

    private fun remotePathFromPublicUrl(publicUrl: String): String? {
        val cleanUrl = publicUrl.trim().substringBefore('?').substringBefore('#')
        if (cleanUrl.isBlank()) return null

        val marker = "/storage/v1/object/public/$resolvedBucketName/"
        val markerIndex = cleanUrl.indexOf(marker)
        if (markerIndex < 0) return null

        val encodedPath = cleanUrl.substring(markerIndex + marker.length)
        if (encodedPath.isBlank()) return null

        val decodedPath = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.name())
        return sanitizeRemotePath(decodedPath)
    }
}
