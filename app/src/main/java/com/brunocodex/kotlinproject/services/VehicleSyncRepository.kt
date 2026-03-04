package com.brunocodex.kotlinproject.services

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VehicleSyncRepository(context: Context) {

    companion object {
        private const val REMOTE_SYNC_TIMEOUT_MS = 8000L
    }

    data class SyncResult(
        val vehicleId: String,
        val remoteSynced: Boolean
    )

    private val localDb = SQLiteConfiguration.getInstance(context.applicationContext)
    private val vehiclesRef by lazy { FirebaseConfiguration.getFirebaseDatabase().child("vehicles") }

    suspend fun saveOrUpdateVehicle(
        ownerId: String,
        plate: String,
        status: String,
        payloadJson: String
    ): SyncResult = withContext(Dispatchers.IO) {
        val normalizedOwner = normalizePathPart(ownerId.ifBlank { "anonymous" })
        val normalizedPlate = normalizePlate(plate)
        val vehicleId = buildVehicleId(normalizedOwner, normalizedPlate)
        val now = System.currentTimeMillis()

        val row = SQLiteConfiguration.VehicleRow(
            vehicleId = vehicleId,
            ownerId = normalizedOwner,
            plate = normalizedPlate,
            status = status,
            payloadJson = payloadJson,
            updatedAt = now,
            deleted = false,
            syncState = SQLiteConfiguration.SYNC_STATE_PENDING
        )

        localDb.upsertVehicle(row)

        val synced = trySyncRow(row)
        if (synced) {
            localDb.markVehicleSynced(vehicleId)
        } else {
            localDb.markVehiclePending(vehicleId)
        }
        SyncResult(vehicleId = vehicleId, remoteSynced = synced)
    }

    suspend fun deleteVehicle(ownerId: String, plate: String): SyncResult = withContext(Dispatchers.IO) {
        val normalizedOwner = normalizePathPart(ownerId.ifBlank { "anonymous" })
        val normalizedPlate = normalizePlate(plate)
        val vehicleId = buildVehicleId(normalizedOwner, normalizedPlate)
        val now = System.currentTimeMillis()

        val existing = localDb.getVehicle(vehicleId)
        if (existing == null) {
            localDb.upsertVehicle(
                SQLiteConfiguration.VehicleRow(
                    vehicleId = vehicleId,
                    ownerId = normalizedOwner,
                    plate = normalizedPlate,
                    status = SQLiteConfiguration.STATUS_DRAFT,
                    payloadJson = "{}",
                    updatedAt = now,
                    deleted = true,
                    syncState = SQLiteConfiguration.SYNC_STATE_PENDING
                )
            )
        } else {
            localDb.markVehicleDeleted(vehicleId = vehicleId, updatedAt = now)
        }

        val row = localDb.getVehicle(vehicleId)
        val synced = if (row == null) {
            false
        } else {
            trySyncRow(row)
        }

        if (synced) {
            localDb.deleteVehiclePermanently(vehicleId)
        } else {
            localDb.markVehiclePending(vehicleId)
        }

        SyncResult(vehicleId = vehicleId, remoteSynced = synced)
    }

    suspend fun syncPendingVehicles(ownerId: String): Int = withContext(Dispatchers.IO) {
        val normalizedOwner = normalizePathPart(ownerId.ifBlank { "anonymous" })
        val pendingRows = localDb.getPendingVehicles(normalizedOwner)
        var syncedCount = 0

        pendingRows.forEach { row ->
            val synced = trySyncRow(row)
            if (!synced) return@forEach

            if (row.deleted) {
                localDb.deleteVehiclePermanently(row.vehicleId)
            } else {
                localDb.markVehicleSynced(row.vehicleId)
            }
            syncedCount++
        }

        syncedCount
    }

    suspend fun getVehicles(ownerId: String): List<SQLiteConfiguration.VehicleRow> =
        withContext(Dispatchers.IO) {
            val normalizedOwner = normalizePathPart(ownerId.ifBlank { "anonymous" })
            localDb.getVehicles(normalizedOwner)
        }

    private suspend fun trySyncRow(row: SQLiteConfiguration.VehicleRow): Boolean {
        return withTimeoutOrNull(REMOTE_SYNC_TIMEOUT_MS) {
            runCatching { syncRow(row) }.getOrDefault(false)
        } ?: false
    }

    private suspend fun syncRow(row: SQLiteConfiguration.VehicleRow): Boolean {
        return if (row.deleted) {
            vehiclesRef.child(row.ownerId).child(row.vehicleId)
                .removeValue()
                .await()
            true
        } else {
            val payload = linkedMapOf<String, Any>(
                "vehicleId" to row.vehicleId,
                "ownerId" to row.ownerId,
                "plate" to row.plate,
                "status" to row.status,
                "deleted" to false,
                "payloadJson" to row.payloadJson,
                "updatedAtClient" to row.updatedAt,
                "updatedAtServer" to ServerValue.TIMESTAMP
            )

            vehiclesRef.child(row.ownerId).child(row.vehicleId)
                .setValue(payload)
                .await()
            true
        }
    }

    private suspend fun Task<Void>.await() {
        suspendCancellableCoroutine<Unit> { continuation ->
            addOnSuccessListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
            addOnFailureListener {
                if (continuation.isActive) continuation.resumeWithException(it)
            }
        }
    }

    private fun buildVehicleId(ownerId: String, plate: String): String {
        return "${normalizePathPart(ownerId)}_${normalizePlate(plate)}"
    }

    private fun normalizePlate(plate: String): String {
        val raw = plate.uppercase().replace("[^A-Z0-9]".toRegex(), "")
        return raw.ifBlank { "SEM_PLACA" }
    }

    private fun normalizePathPart(value: String): String {
        return value.trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-zA-Z0-9_-]".toRegex(), "")
            .ifBlank { "value" }
    }
}
