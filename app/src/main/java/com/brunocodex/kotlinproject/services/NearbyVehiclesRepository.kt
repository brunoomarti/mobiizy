package com.brunocodex.kotlinproject.services

import android.content.Context
import android.location.Location
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.Task
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.firebase.database.DataSnapshot
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NearbyVehiclesRepository(context: Context) {

    data class NearbyVehicle(
        val vehicleId: String,
        val ownerId: String,
        val pickupLatitude: Double,
        val pickupLongitude: Double,
        val distanceMeters: Double
    )

    private val vehiclesRef by lazy {
        FirebaseConfiguration.getFirebaseDatabase().child("vehicles")
    }
    private val vehiclesPublicIndexRef by lazy {
        FirebaseConfiguration.getFirebaseDatabase().child("vehicles_public_index")
    }

    suspend fun getNearbyPublishedVehicles(
        center: LatLng,
        radiusMeters: Double
    ): List<NearbyVehicle> = withContext(Dispatchers.IO) {
        if (radiusMeters <= 0.0) return@withContext emptyList()

        val centerGeo = GeoLocation(center.latitude, center.longitude)
        val bounds = GeoFireUtils.getGeoHashQueryBounds(centerGeo, radiusMeters)
        if (bounds.isEmpty()) return@withContext emptyList()

        val indexedById = linkedMapOf<String, NearbyVehicle>()

        val snapshots = bounds.map { bound ->
            async {
                runCatching {
                    vehiclesPublicIndexRef
                        .orderByChild("pickupGeohash")
                        .startAt(bound.startHash)
                        .endAt(bound.endHash)
                        .get()
                        .await()
                }.getOrNull()
            }
        }.awaitAll()

        snapshots.forEach { snapshot ->
            snapshot?.children?.forEach { child ->
                val entry = parseIndexedVehicle(child) ?: return@forEach
                val distance = distanceMeters(
                    fromLatitude = center.latitude,
                    fromLongitude = center.longitude,
                    toLatitude = entry.pickupLatitude,
                    toLongitude = entry.pickupLongitude
                )
                if (distance > radiusMeters) return@forEach

                val current = indexedById[entry.vehicleId]
                if (current == null || distance < current.distanceMeters) {
                    indexedById[entry.vehicleId] = entry.copy(distanceMeters = distance)
                }
            }
        }

        if (indexedById.isEmpty()) {
            val legacy = fetchNearbyFromLegacyTree(center, radiusMeters)
            return@withContext legacy
        }

        indexedById.values.sortedBy { it.distanceMeters }
    }

    private fun parseIndexedVehicle(snapshot: DataSnapshot): NearbyVehicle? {
        val status = snapshot.child("status").getValue(String::class.java)
            .orEmpty()
            .ifBlank { SQLiteConfiguration.STATUS_DRAFT }
        if (status != SQLiteConfiguration.STATUS_PUBLISHED) return null

        val vehicleId = snapshot.child("vehicleId").getValue(String::class.java)
            .orEmpty()
            .ifBlank { snapshot.key.orEmpty() }
            .ifBlank { return null }
        val ownerId = snapshot.child("ownerId").getValue(String::class.java)
            .orEmpty()
            .ifBlank { return null }

        val latitude = snapshot.child("pickupLatitude").toNullableDouble() ?: return null
        val longitude = snapshot.child("pickupLongitude").toNullableDouble() ?: return null

        return NearbyVehicle(
            vehicleId = vehicleId,
            ownerId = ownerId,
            pickupLatitude = latitude,
            pickupLongitude = longitude,
            distanceMeters = Double.MAX_VALUE
        )
    }

    private suspend fun fetchNearbyFromLegacyTree(
        center: LatLng,
        radiusMeters: Double
    ): List<NearbyVehicle> {
        val root = runCatching { vehiclesRef.get().await() }.getOrNull() ?: return emptyList()
        val found = linkedMapOf<String, NearbyVehicle>()

        root.children.forEach { ownerNode ->
            val ownerId = ownerNode.key.orEmpty().ifBlank { return@forEach }
            ownerNode.children.forEach { vehicleNode ->
                val status = vehicleNode.child("status").getValue(String::class.java)
                    .orEmpty()
                    .ifBlank { SQLiteConfiguration.STATUS_DRAFT }
                if (status != SQLiteConfiguration.STATUS_PUBLISHED) return@forEach

                val vehicleId = vehicleNode.child("vehicleId").getValue(String::class.java)
                    .orEmpty()
                    .ifBlank { vehicleNode.key.orEmpty() }
                    .ifBlank { return@forEach }
                val payloadRaw = vehicleNode.child("payloadJson").getValue(String::class.java).orEmpty()
                val payload = runCatching { JSONObject(payloadRaw) }.getOrNull() ?: return@forEach

                val latitude = payload.optNullableDouble("pickupLatitude")
                    ?: payload.optNullableDouble("pickupLat")
                val longitude = payload.optNullableDouble("pickupLongitude")
                    ?: payload.optNullableDouble("pickupLng")
                    ?: payload.optNullableDouble("pickupLon")
                if (latitude == null || longitude == null) return@forEach

                val distance = distanceMeters(
                    fromLatitude = center.latitude,
                    fromLongitude = center.longitude,
                    toLatitude = latitude,
                    toLongitude = longitude
                )
                if (distance > radiusMeters) return@forEach

                val current = found[vehicleId]
                if (current == null || distance < current.distanceMeters) {
                    found[vehicleId] = NearbyVehicle(
                        vehicleId = vehicleId,
                        ownerId = ownerId,
                        pickupLatitude = latitude,
                        pickupLongitude = longitude,
                        distanceMeters = distance
                    )
                }
            }
        }

        return found.values.sortedBy { it.distanceMeters }
    }

    private fun DataSnapshot.toNullableDouble(): Double? {
        val raw = value ?: return null
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
    }

    private fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Double {
        val result = FloatArray(1)
        Location.distanceBetween(fromLatitude, fromLongitude, toLatitude, toLongitude, result)
        return result[0].toDouble()
    }

    private suspend fun <T> Task<T>.await(): T? {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener {
                if (continuation.isActive) continuation.resume(it)
            }
            addOnFailureListener {
                if (continuation.isActive) continuation.resumeWithException(it)
            }
        }
    }
}
