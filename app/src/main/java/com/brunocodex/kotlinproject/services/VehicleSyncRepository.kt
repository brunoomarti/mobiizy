package com.brunocodex.kotlinproject.services

import android.content.Context
import com.brunocodex.kotlinproject.utils.ApiClient
import com.brunocodex.kotlinproject.utils.NominatimResult
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.android.gms.tasks.Task
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.ServerValue
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VehicleSyncRepository(context: Context) {

    companion object {
        private const val REMOTE_SYNC_TIMEOUT_MS = 8000L
        private const val REMOTE_LIST_TIMEOUT_MS = 5000L
        private const val GEO_LOOKUP_TIMEOUT_MS = 9000L
        private const val MAX_GEO_QUERY_ATTEMPTS = 12
    }

    data class SyncResult(
        val vehicleId: String,
        val remoteSynced: Boolean
    )

    private val localDb = SQLiteConfiguration.getInstance(context.applicationContext)
    private val vehiclesRef by lazy { FirebaseConfiguration.getFirebaseDatabase().child("vehicles") }
    private val vehiclesPublicIndexRef by lazy {
        FirebaseConfiguration.getFirebaseDatabase().child("vehicles_public_index")
    }

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

    suspend fun getVehiclesOnlineFirst(ownerId: String): List<SQLiteConfiguration.VehicleRow> =
        withContext(Dispatchers.IO) {
            val normalizedOwner = normalizePathPart(ownerId.ifBlank { "anonymous" })
            val localRows = localDb.getVehicles(normalizedOwner)

            val remoteRows = fetchRemoteVehicles(normalizedOwner)
            if (remoteRows == null) {
                return@withContext localRows
            }

            mergeRemoteRowsIntoLocal(localRows, remoteRows)
            refreshPublicIndexFromRows(remoteRows)
            localDb.getVehicles(normalizedOwner)
        }

    private suspend fun fetchRemoteVehicles(ownerId: String): List<SQLiteConfiguration.VehicleRow>? {
        val snapshot = withTimeoutOrNull(REMOTE_LIST_TIMEOUT_MS) {
            runCatching { vehiclesRef.child(ownerId).get().await() }.getOrNull()
        } ?: return null

        val now = System.currentTimeMillis()
        val rows = mutableListOf<SQLiteConfiguration.VehicleRow>()
        snapshot.children.forEach { item ->
            val parsed = parseRemoteSnapshot(ownerId = ownerId, snapshot = item, now = now) ?: return@forEach
            if (!parsed.deleted) {
                rows += parsed
            }
        }

        return rows
    }

    private fun parseRemoteSnapshot(
        ownerId: String,
        snapshot: DataSnapshot,
        now: Long
    ): SQLiteConfiguration.VehicleRow? {
        val vehicleId = snapshot.child("vehicleId").getValue(String::class.java)
            .orEmpty()
            .ifBlank { snapshot.key.orEmpty() }
            .ifBlank { return null }

        val payloadJson = snapshot.child("payloadJson").getValue(String::class.java).orEmpty()
        val plate = snapshot.child("plate").getValue(String::class.java).orEmpty()
        val status = snapshot.child("status").getValue(String::class.java)
            .orEmpty()
            .ifBlank { SQLiteConfiguration.STATUS_DRAFT }
        val deleted = snapshot.child("deleted").getValue(Boolean::class.java) ?: false
        val updatedAt = snapshot.child("updatedAtClient").getValue(Long::class.java) ?: now

        return SQLiteConfiguration.VehicleRow(
            vehicleId = vehicleId,
            ownerId = ownerId,
            plate = normalizePlate(plate),
            status = status,
            payloadJson = payloadJson.ifBlank { "{}" },
            updatedAt = updatedAt,
            deleted = deleted,
            syncState = SQLiteConfiguration.SYNC_STATE_SYNCED
        )
    }

    private fun mergeRemoteRowsIntoLocal(
        localRows: List<SQLiteConfiguration.VehicleRow>,
        remoteRows: List<SQLiteConfiguration.VehicleRow>
    ) {
        val localById = localRows.associateBy { it.vehicleId }

        remoteRows.forEach { remote ->
            val local = localById[remote.vehicleId]
            val shouldKeepLocalPending = local != null &&
                local.syncState == SQLiteConfiguration.SYNC_STATE_PENDING &&
                local.updatedAt >= remote.updatedAt

            if (!shouldKeepLocalPending) {
                localDb.upsertVehicle(remote)
            }
        }

        val remoteIds = remoteRows.mapTo(hashSetOf()) { it.vehicleId }
        localRows.forEach { local ->
            val shouldDeleteStaleSynced = local.syncState == SQLiteConfiguration.SYNC_STATE_SYNCED &&
                local.vehicleId !in remoteIds
            if (shouldDeleteStaleSynced) {
                localDb.deleteVehiclePermanently(local.vehicleId)
            }
        }
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
            vehiclesPublicIndexRef.child(row.vehicleId)
                .removeValue()
                .await()
            true
        } else {
            val payloadObject = runCatching { JSONObject(row.payloadJson) }.getOrElse { JSONObject() }
            val vehicleType = normalizeVehicleType(payloadObject.optString("vehicleType"))
            payloadObject.put("vehicleType", vehicleType)
            val pickupCoordinates = resolvePickupCoordinates(payloadObject)
            if (pickupCoordinates != null) {
                payloadObject.put("pickupLatitude", pickupCoordinates.first)
                payloadObject.put("pickupLongitude", pickupCoordinates.second)
            }
            val effectivePayloadJson = payloadObject.toString()

            val payload = linkedMapOf<String, Any>(
                "vehicleId" to row.vehicleId,
                "ownerId" to row.ownerId,
                "plate" to row.plate,
                "status" to row.status,
                "vehicleType" to vehicleType,
                "deleted" to false,
                "payloadJson" to effectivePayloadJson,
                "updatedAtClient" to row.updatedAt,
                "updatedAtServer" to ServerValue.TIMESTAMP
            )

            vehiclesRef.child(row.ownerId).child(row.vehicleId)
                .setValue(payload)
                .await()

            if (pickupCoordinates != null && row.status == SQLiteConfiguration.STATUS_PUBLISHED) {
                val indexPayload = buildPublicIndexPayload(
                    row = row,
                    payloadObject = payloadObject,
                    vehicleType = vehicleType,
                    pickupCoordinates = pickupCoordinates
                )
                vehiclesPublicIndexRef.child(row.vehicleId)
                    .setValue(indexPayload)
                    .await()
            } else {
                vehiclesPublicIndexRef.child(row.vehicleId)
                    .removeValue()
                    .await()
            }

            if (effectivePayloadJson != row.payloadJson) {
                localDb.upsertVehicle(
                    row.copy(
                        payloadJson = effectivePayloadJson,
                        syncState = SQLiteConfiguration.SYNC_STATE_PENDING
                    )
                )
            }
            true
        }
    }

    private suspend fun refreshPublicIndexFromRows(rows: List<SQLiteConfiguration.VehicleRow>) {
        rows.filter { row ->
            !row.deleted && row.status == SQLiteConfiguration.STATUS_PUBLISHED
        }.forEach { row ->
            val payloadObject = runCatching { JSONObject(row.payloadJson) }.getOrElse { JSONObject() }
            val vehicleType = normalizeVehicleType(payloadObject.optString("vehicleType"))
            payloadObject.put("vehicleType", vehicleType)
            val pickupCoordinates = resolvePickupCoordinates(payloadObject) ?: return@forEach
            val indexPayload = buildPublicIndexPayload(
                row = row,
                payloadObject = payloadObject,
                vehicleType = vehicleType,
                pickupCoordinates = pickupCoordinates
            )
            runCatching {
                vehiclesPublicIndexRef.child(row.vehicleId)
                    .setValue(indexPayload)
                    .await()
            }
        }
    }

    private fun buildPublicIndexPayload(
        row: SQLiteConfiguration.VehicleRow,
        payloadObject: JSONObject,
        vehicleType: String,
        pickupCoordinates: Pair<Double, Double>
    ): Map<String, Any> {
        val geoHash = GeoFireUtils.getGeoHashForLocation(
            GeoLocation(pickupCoordinates.first, pickupCoordinates.second)
        )
        val publicBrand = payloadObject.optString("brand").trim()
        val publicModel = payloadObject.optString("model").trim()
        val publicManufactureYear = payloadObject.optString("manufactureYear").trim()
        val publicModelYear = payloadObject.optString("modelYear").trim()
        val publicColor = payloadObject.optString("color").trim()
        val publicBodyType = payloadObject.optString("bodyType").trim()
        val publicDailyPrice = payloadObject.optString("dailyPrice").trim()
        val publicCondition = payloadObject.optString("condition").trim()
        val publicMileage = payloadObject.optString("mileage").trim()
        val publicTransmissionType = payloadObject.optString("transmissionType").trim()
        val publicFuelType = payloadObject.optString("fuelType").trim()
        val publicSeats = payloadObject.optString("seats").trim()
        val publicCityState = payloadObject.optString("cityState").trim()
        val publicNeighborhood = payloadObject.optString("neighborhood").trim()
        val publicHighlightTags = payloadObject.optStringList("highlightTags")
        val publicSafetyItems = payloadObject.optStringList("safetyItems")
        val publicComfortItems = payloadObject.optStringList("comfortItems")
        val publicAllowTrip = payloadObject.optNullableBoolean("allowTrip") ?: false
        val publicAllowedTripTypes = payloadObject.optStringList("allowedTripTypes")
        val publicUploadedPhotoUrls = payloadObject.optStringMap("uploadedPhotoUrls")
        val publicPickupOnLocation = payloadObject.optNullableBoolean("pickupOnLocation") ?: false
        val publicDeliveryByFee = payloadObject.optNullableBoolean("deliveryByFee") ?: false
        val publicDeliveryRadiusKm = payloadObject.optString("deliveryRadiusKm").trim()
        val publicDeliveryFee = payloadObject.optString("deliveryFee").trim()
        val publicDocumentsUpToDate = payloadObject.optNullableBoolean("documentsUpToDate") ?: false
        val publicIpvaLicensingOk = payloadObject.optNullableBoolean("ipvaLicensingOk") ?: false
        val publicHasInsurance = payloadObject.optNullableBoolean("hasInsurance") ?: false

        return linkedMapOf(
            "vehicleId" to row.vehicleId,
            "ownerId" to row.ownerId,
            "status" to row.status,
            "vehicleType" to vehicleType,
            "brand" to publicBrand,
            "model" to publicModel,
            "manufactureYear" to publicManufactureYear,
            "modelYear" to publicModelYear,
            "color" to publicColor,
            "bodyType" to publicBodyType,
            "dailyPrice" to publicDailyPrice,
            "condition" to publicCondition,
            "mileage" to publicMileage,
            "transmissionType" to publicTransmissionType,
            "fuelType" to publicFuelType,
            "seats" to publicSeats,
            "cityState" to publicCityState,
            "neighborhood" to publicNeighborhood,
            "highlightTags" to publicHighlightTags,
            "safetyItems" to publicSafetyItems,
            "comfortItems" to publicComfortItems,
            "allowTrip" to publicAllowTrip,
            "allowedTripTypes" to publicAllowedTripTypes,
            "uploadedPhotoUrls" to publicUploadedPhotoUrls,
            "pickupOnLocation" to publicPickupOnLocation,
            "deliveryByFee" to publicDeliveryByFee,
            "deliveryRadiusKm" to publicDeliveryRadiusKm,
            "deliveryFee" to publicDeliveryFee,
            "documentsUpToDate" to publicDocumentsUpToDate,
            "ipvaLicensingOk" to publicIpvaLicensingOk,
            "hasInsurance" to publicHasInsurance,
            "pickupLatitude" to pickupCoordinates.first,
            "pickupLongitude" to pickupCoordinates.second,
            "pickupGeohash" to geoHash,
            "updatedAtClient" to row.updatedAt,
            "updatedAtServer" to ServerValue.TIMESTAMP
        )
    }

    private data class PickupAddressParts(
        val street: String?,
        val number: String?,
        val neighborhood: String?,
        val city: String?,
        val state: String?,
        val cep: String?,
        val pickupSearchQuery: String?
    )

    private suspend fun resolvePickupCoordinates(payload: JSONObject): Pair<Double, Double>? {
        val directLatitude = payload.optNullableDouble("pickupLatitude")
            ?: payload.optNullableDouble("pickupLat")
        val directLongitude = payload.optNullableDouble("pickupLongitude")
            ?: payload.optNullableDouble("pickupLng")
            ?: payload.optNullableDouble("pickupLon")
        if (directLatitude != null && directLongitude != null) {
            return directLatitude to directLongitude
        }

        val parts = parsePickupAddressParts(payload)
        val addressQueries = buildPickupAddressQueries(parts)
        if (addressQueries.isEmpty()) return null

        return withTimeoutOrNull(GEO_LOOKUP_TIMEOUT_MS) {
            for (query in addressQueries) {
                val searchResults = runCatching {
                    ApiClient.nominatim.search(query = query)
                }.getOrElse { emptyList() }

                val bestMatch = pickBestCoordinate(searchResults, parts)
                if (bestMatch != null) return@withTimeoutOrNull bestMatch
            }
            null
        }
    }

    private fun parsePickupAddressParts(payload: JSONObject): PickupAddressParts {
        val street = payload.optNullableString("pickupStreet")
            ?: payload.optNullableString("pickupPoint")
        val number = payload.optNullableString("pickupNumber")
        val neighborhood = payload.optNullableString("pickupNeighborhood")
            ?: payload.optNullableString("neighborhood")
        val city = payload.optNullableString("pickupCity")
        val state = payload.optNullableString("pickupState")
        val cep = payload.optNullableString("pickupCep")
        val pickupSearchQuery = payload.optNullableString("pickupSearchQuery")

        val legacyCityState = payload.optNullableString("cityState")
        val cityStateParts = legacyCityState
            ?.split("/", "-")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val cityFromLegacy = if (city.isNullOrBlank()) cityStateParts.firstOrNull() else null
        val stateFromLegacy = if (state.isNullOrBlank() && cityStateParts.size > 1) {
            cityStateParts.lastOrNull()
        } else {
            null
        }

        return PickupAddressParts(
            street = street,
            number = number,
            neighborhood = neighborhood,
            city = city ?: cityFromLegacy,
            state = state ?: stateFromLegacy,
            cep = cep,
            pickupSearchQuery = pickupSearchQuery
        )
    }

    private fun buildPickupAddressQueries(parts: PickupAddressParts): List<String> {
        val queries = linkedSetOf<String>()

        fun addQuery(vararg values: String?) {
            val query = values
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .joinToString(", ")
                .trim()
            if (query.isBlank()) return

            queries += query
            val normalized = normalizeForSearch(query)
            if (normalized.isNotBlank() && normalized != query) {
                queries += normalized
            }
        }

        addQuery(parts.pickupSearchQuery)
        addQuery(parts.neighborhood, parts.city, parts.state, parts.cep, "Brasil")
        addQuery(parts.neighborhood, parts.city, parts.state, "Brasil")
        addQuery(parts.cep, parts.city, parts.state, "Brasil")
        addQuery(parts.cep, "Brasil")
        addQuery(parts.city, parts.state, "Brasil")
        addQuery(parts.street, parts.number, parts.neighborhood, parts.city, parts.state, parts.cep, "Brasil")
        addQuery(parts.street, parts.number, parts.city, parts.state, "Brasil")
        addQuery(parts.street, parts.neighborhood, parts.city, parts.state, "Brasil")
        addQuery(parts.street, parts.city, parts.state, "Brasil")

        return queries.take(MAX_GEO_QUERY_ATTEMPTS)
    }

    private fun pickBestCoordinate(
        results: List<NominatimResult>,
        parts: PickupAddressParts
    ): Pair<Double, Double>? {
        if (results.isEmpty()) return null

        data class ScoredCoordinate(
            val latitude: Double,
            val longitude: Double,
            val score: Int
        )

        val expectedStreet = normalizeForSearch(parts.street.orEmpty())
        val expectedNeighborhood = normalizeForSearch(parts.neighborhood.orEmpty())
        val expectedCity = normalizeForSearch(parts.city.orEmpty())
        val expectedState = normalizeState(parts.state.orEmpty())
        val expectedCepDigits = normalizeDigits(parts.cep).takeIf { it.isNotBlank() }

        val scored = results.mapNotNull { result ->
            val latitude = result.lat?.toDoubleOrNull() ?: return@mapNotNull null
            val longitude = result.lon?.toDoubleOrNull() ?: return@mapNotNull null

            val resultAddress = result.address
            val resultCity = normalizeForSearch(
                resultAddress?.city
                    ?: resultAddress?.town
                    ?: resultAddress?.municipality
                    ?: ""
            )
            val resultState = normalizeState(resultAddress?.state.orEmpty())
            val resultCepDigits = normalizeDigits(resultAddress?.postcode)
            val displayName = normalizeForSearch(result.displayName.orEmpty())

            var score = 0
            if (expectedCepDigits != null && resultCepDigits.startsWith(expectedCepDigits.take(5))) {
                score += 120
            }
            if (expectedCity.isNotBlank() && (
                    (resultCity.isNotBlank() && resultCity == expectedCity) ||
                        resultCity.contains(expectedCity) ||
                        (resultCity.isNotBlank() && expectedCity.contains(resultCity))
                    )
            ) {
                score += 80
            }
            if (expectedState.isNotBlank() && resultState == expectedState) {
                score += 60
            }
            if (expectedStreet.isNotBlank() && displayName.contains(expectedStreet)) {
                score += 25
            }
            if (expectedNeighborhood.isNotBlank() && displayName.contains(expectedNeighborhood)) {
                score += 20
            }

            ScoredCoordinate(latitude = latitude, longitude = longitude, score = score)
        }.sortedByDescending { it.score }

        val best = scored.firstOrNull() ?: return null
        val hasExpectedContext = expectedCepDigits != null || expectedCity.isNotBlank() || expectedState.isNotBlank()
        if (hasExpectedContext && best.score <= 0) return null

        return best.latitude to best.longitude
    }

    private fun normalizeForSearch(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized
            .replace("\\p{Mn}+".toRegex(), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    private fun normalizeDigits(raw: String?): String {
        return raw.orEmpty().filter { it.isDigit() }
    }

    private fun normalizeState(raw: String): String {
        val value = normalizeForSearch(raw)
        if (value.length == 2 && value.all { it.isLetter() }) return value

        val map = mapOf(
            "acre" to "ac",
            "alagoas" to "al",
            "amapa" to "ap",
            "amazonas" to "am",
            "bahia" to "ba",
            "ceara" to "ce",
            "distrito federal" to "df",
            "espirito santo" to "es",
            "goias" to "go",
            "maranhao" to "ma",
            "mato grosso" to "mt",
            "mato grosso do sul" to "ms",
            "minas gerais" to "mg",
            "para" to "pa",
            "paraiba" to "pb",
            "parana" to "pr",
            "pernambuco" to "pe",
            "piaui" to "pi",
            "rio de janeiro" to "rj",
            "rio grande do norte" to "rn",
            "rio grande do sul" to "rs",
            "rondonia" to "ro",
            "roraima" to "rr",
            "santa catarina" to "sc",
            "sao paulo" to "sp",
            "sergipe" to "se",
            "tocantins" to "to"
        )
        return map[value].orEmpty()
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? {
        if (isNull(key)) return null
        return when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "sim" -> true
                "false", "0", "no", "nao" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        if (isNull(key)) return emptyList()
        return when (val value = opt(key)) {
            is org.json.JSONArray -> {
                buildList {
                    for (index in 0 until value.length()) {
                        val item = value.optString(index).trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
            }
            is Collection<*> -> value.mapNotNull { item ->
                item?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
            is String -> value.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun JSONObject.optStringMap(key: String): Map<String, String> {
        if (isNull(key)) return emptyMap()
        val raw = opt(key)
        val result = linkedMapOf<String, String>()

        when (raw) {
            is JSONObject -> {
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val mapKey = keys.next().orEmpty().trim()
                    val mapValue = raw.optString(mapKey).trim()
                    if (mapKey.isNotBlank() && mapValue.isNotBlank()) {
                        result[mapKey] = mapValue
                    }
                }
            }
            is Map<*, *> -> {
                raw.forEach { (mapKeyRaw, mapValueRaw) ->
                    val mapKey = mapKeyRaw?.toString()?.trim().orEmpty()
                    val mapValue = mapValueRaw?.toString()?.trim().orEmpty()
                    if (mapKey.isNotBlank() && mapValue.isNotBlank()) {
                        result[mapKey] = mapValue
                    }
                }
            }
        }

        return result
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

    private fun normalizeVehicleType(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "motorcycle", "moto" -> "motorcycle"
            else -> "car"
        }
    }
}
