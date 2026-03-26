package com.brunocodex.kotlinproject.services

import android.content.Context
import android.location.Location
import android.util.Log
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

class NearbyVehiclesRepository(context: Context) {

    companion object {
        private const val TAG = "NearbyVehiclesRepo"
    }

    data class NearbyVehicle(
        val vehicleId: String,
        val ownerId: String,
        val pickupLatitude: Double,
        val pickupLongitude: Double,
        val vehicleType: String?,
        val brand: String?,
        val model: String?,
        val manufactureYear: String?,
        val modelYear: String?,
        val color: String?,
        val bodyType: String?,
        val dailyPrice: String?,
        val condition: String?,
        val highlightTags: List<String>,
        val allowTrip: Boolean?,
        val allowedTripTypes: List<String>,
        val uploadedPhotoUrls: Map<String, String>,
        val plate: String,
        val payloadJson: String,
        val distanceMeters: Double
    )

    private val vehiclesPublicIndexRef by lazy {
        FirebaseConfiguration.getFirebaseDatabase().child("vehicles_public_index")
    }

    suspend fun getNearbyPublishedVehicles(
        centerLatitude: Double,
        centerLongitude: Double,
        radiusMeters: Double
    ): List<NearbyVehicle> = withContext(Dispatchers.IO) {
        if (radiusMeters <= 0.0) return@withContext emptyList()

        val centerGeo = GeoLocation(centerLatitude, centerLongitude)
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
                }.onFailure { throwable ->
                    Log.w(TAG, "Indexed nearby query failed for hash bound ${bound.startHash}..${bound.endHash}", throwable)
                }.getOrNull()
            }
        }.awaitAll()

        var indexedCandidates = 0
        snapshots.forEach { snapshot ->
            snapshot?.children?.forEach { child ->
                indexedCandidates++
                val entry = parseIndexedVehicle(child) ?: return@forEach
                val distance = distanceMeters(
                    fromLatitude = centerLatitude,
                    fromLongitude = centerLongitude,
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

        Log.d(
            TAG,
            "Indexed nearby scan: bounds=${bounds.size}, candidates=$indexedCandidates, inRadius=${indexedById.size}, radius=$radiusMeters"
        )

        if (indexedById.isEmpty()) {
            return@withContext emptyList()
        }

        val result = indexedById.values.sortedBy { it.distanceMeters }
        Log.d(
            TAG,
            "Indexed nearby final count=${result.size}, radius=$radiusMeters"
        )
        result
    }

    private fun parseIndexedVehicle(snapshot: DataSnapshot): NearbyVehicle? {
        val status = snapshot.child("status").getValue(String::class.java)
        if (!isPublishedStatusOrUnknown(status)) return null

        val vehicleId = snapshot.child("vehicleId").getValue(String::class.java)
            .orEmpty()
            .ifBlank { snapshot.key.orEmpty() }
            .ifBlank { return null }
        val ownerId = snapshot.child("ownerId").getValue(String::class.java)
            .orEmpty()
            .ifBlank { return null }

        val latitude = snapshot.child("pickupLatitude").toNullableDouble() ?: return null
        val longitude = snapshot.child("pickupLongitude").toNullableDouble() ?: return null
        val vehicleType = normalizeVehicleType(
            snapshot.child("vehicleType").getValue(String::class.java)
        )
        val brand = snapshot.child("brand").getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val model = snapshot.child("model").getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val manufactureYear = snapshot.child("manufactureYear").getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val modelYear = snapshot.child("modelYear").getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val color = snapshot.child("color").getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val bodyType = normalizeBodyType(
            snapshot.child("bodyType").getValue(String::class.java)
        )
        val dailyPrice = snapshot.child("dailyPrice").getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val condition = snapshot.child("condition").getValue(String::class.java)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val highlightTags = snapshot.child("highlightTags").toStringList()
        val allowTrip = snapshot.child("allowTrip").toNullableBoolean()
        val allowedTripTypes = snapshot.child("allowedTripTypes").toStringList()
        val uploadedPhotoUrls = snapshot.child("uploadedPhotoUrls").toStringMap()

        return NearbyVehicle(
            vehicleId = vehicleId,
            ownerId = ownerId,
            pickupLatitude = latitude,
            pickupLongitude = longitude,
            vehicleType = vehicleType,
            brand = brand,
            model = model,
            manufactureYear = manufactureYear,
            modelYear = modelYear,
            color = color,
            bodyType = bodyType,
            dailyPrice = dailyPrice,
            condition = condition,
            highlightTags = highlightTags,
            allowTrip = allowTrip,
            allowedTripTypes = allowedTripTypes,
            uploadedPhotoUrls = uploadedPhotoUrls,
            plate = "",
            payloadJson = "{}",
            distanceMeters = Double.MAX_VALUE
        )
    }

    private fun isPublishedStatusOrUnknown(rawStatus: String?): Boolean {
        val normalized = rawStatus?.trim()?.lowercase()
        return normalized.isNullOrBlank() || normalized == SQLiteConfiguration.STATUS_PUBLISHED
    }

    private fun DataSnapshot.toNullableDouble(): Double? {
        val raw = value ?: return null
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
    }

    private fun DataSnapshot.toNullableBoolean(): Boolean? {
        val raw = value ?: return null
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes", "sim" -> true
                "false", "0", "no", "nao" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun DataSnapshot.toStringList(): List<String> {
        if (childrenCount > 0) {
            return children.mapNotNull { child ->
                child.getValue(String::class.java)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }

        return when (val raw = value) {
            is List<*> -> raw.mapNotNull { item ->
                item?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
            is String -> raw.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun DataSnapshot.toStringMap(): Map<String, String> {
        val fromChildren = linkedMapOf<String, String>()
        children.forEach { child ->
            val key = child.key.orEmpty().trim()
            val mapValue = child.getValue(String::class.java).orEmpty().trim()
            if (key.isNotBlank() && mapValue.isNotBlank()) {
                fromChildren[key] = mapValue
            }
        }
        if (fromChildren.isNotEmpty()) return fromChildren

        val raw = value
        if (raw is Map<*, *>) {
            val fromMap = linkedMapOf<String, String>()
            raw.forEach { (mapKeyRaw, mapValueRaw) ->
                val key = mapKeyRaw?.toString()?.trim().orEmpty()
                val mapValue = mapValueRaw?.toString()?.trim().orEmpty()
                if (key.isNotBlank() && mapValue.isNotBlank()) {
                    fromMap[key] = mapValue
                }
            }
            return fromMap
        }

        return emptyMap()
    }

    private fun normalizeVehicleType(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            "car" -> "car"
            "motorcycle", "moto" -> "motorcycle"
            else -> null
        }
    }

    private fun normalizeBodyType(raw: String?): String? {
        return raw?.trim()?.takeIf { it.isNotBlank() }
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
