package com.brunocodex.kotlinproject.viewmodels

import androidx.lifecycle.ViewModel
import org.json.JSONArray
import org.json.JSONObject

class VehicleRegisterViewModel : ViewModel() {

    data class DaySchedule(
        var enabled: Boolean = false,
        var startTime: String = "",
        var endTime: String = ""
    )

    companion object {
        const val TYPE_CAR = "car"
        const val TYPE_MOTORCYCLE = "motorcycle"

        const val PHOTO_FRONT = "front"
        const val PHOTO_REAR = "rear"
        const val PHOTO_LEFT_SIDE = "left_side"
        const val PHOTO_RIGHT_SIDE = "right_side"
        const val PHOTO_DASHBOARD = "dashboard"
        const val PHOTO_TRUNK = "trunk"
        const val PHOTO_ENGINE = "engine"
        const val PHOTO_TIRES_WHEELS = "tires_wheels"
        const val PHOTO_FINISHING = "finishing"
        const val PHOTO_ACCESSORIES = "accessories"
        const val PHOTO_EXHAUST = "exhaust"

        const val AVAILABILITY_IMMEDIATE = "immediate"
        const val AVAILABILITY_DRAFT = "draft"

        const val CONDITION_EXCELLENT = "excellent"
        const val CONDITION_GOOD = "good"
        const val CONDITION_OK = "ok"
        const val CONDITION_NEEDS_ATTENTION = "needs_attention"

        const val DAY_MON = "mon"
        const val DAY_TUE = "tue"
        const val DAY_WED = "wed"
        const val DAY_THU = "thu"
        const val DAY_FRI = "fri"
        const val DAY_SAT = "sat"
        const val DAY_SUN = "sun"

        const val PICKUP_ADDRESS_MODE_PROFILE = "profile"
        const val PICKUP_ADDRESS_MODE_SPECIFIC = "specific"
    }

    var currentStep: Int = 0

    // Etapa 1
    var vehicleType: String? = null
    var availabilityMode: String? = null

    // Etapa 2
    var brand: String? = null
    var model: String? = null
    var manufactureYear: String? = null
    var modelYear: String? = null
    var trimVersion: String? = null
    var plate: String? = null
    var renavamOrChassis: String? = null
    var color: String? = null
    var bodyType: String? = null
    var fuelType: String? = null
    var transmissionType: String? = null
    var doors: String? = null
    var seats: String? = null
    val highlightTags: LinkedHashSet<String> = linkedSetOf()

    // Etapa 3
    val uploadedPhotoUrls: LinkedHashMap<String, String> = linkedMapOf()
    var shortVideoUrl: String? = null

    // Etapa 4
    var mileage: String? = null
    var condition: String? = null
    var hadAccident: Boolean? = null
    var accidentDescription: String? = null
    var observations: String? = null
    val safetyItems: LinkedHashSet<String> = linkedSetOf()
    val comfortItems: LinkedHashSet<String> = linkedSetOf()
    var dailyPrice: String? = null

    // Etapa 5
    var documentsUpToDate: Boolean? = null
    var ipvaLicensingOk: Boolean? = null
    var hasInsurance: Boolean? = null
    var insuranceType: String? = null
    var allowPet: Boolean? = null
    var allowSmoking: Boolean? = null
    var allowTrip: Boolean? = null
    val allowedTripTypes: LinkedHashSet<String> = linkedSetOf()
    var minimumDriverAge: String? = null
    var minimumLicenseYears: String? = null

    // Etapa 6
    var cityState: String? = null
    var neighborhood: String? = null
    var pickupPoint: String? = null
    var pickupOnLocation: Boolean = true
    var deliveryByFee: Boolean = false
    var deliveryRadiusKm: String? = null
    var deliveryFee: String? = null
    var pickupAddressMode: String? = null
    var pickupProfileAddressConfirmed: Boolean = false
    var pickupSearchQuery: String? = null
    var pickupStreet: String? = null
    var pickupNumber: String? = null
    var pickupNeighborhood: String? = null
    var pickupCity: String? = null
    var pickupState: String? = null
    var pickupCep: String? = null
    var pickupLatitude: Double? = null
    var pickupLongitude: Double? = null
    val weeklySchedule: LinkedHashMap<String, DaySchedule> = linkedMapOf(
        DAY_MON to DaySchedule(),
        DAY_TUE to DaySchedule(),
        DAY_WED to DaySchedule(),
        DAY_THU to DaySchedule(),
        DAY_FRI to DaySchedule(),
        DAY_SAT to DaySchedule(),
        DAY_SUN to DaySchedule()
    )

    fun hasAnyProgress(): Boolean {
        return !brand.isNullOrBlank() ||
            !model.isNullOrBlank() ||
            !cityState.isNullOrBlank() ||
            !pickupStreet.isNullOrBlank() ||
            !dailyPrice.isNullOrBlank() ||
            uploadedPhotoUrls.isNotEmpty() ||
            highlightTags.isNotEmpty() ||
            currentStep > 0
    }

    fun isPhotosChecklistOk(): Boolean = requiredPhotosChecked() && photoCount() >= 6

    fun isPriceChecklistOk(): Boolean = !dailyPrice.isNullOrBlank()

    fun isRulesChecklistOk(): Boolean {
        val tripOk = allowTrip != null
        val tripTypesOk = allowTrip != true || allowedTripTypes.isNotEmpty()
        if (vehicleType == TYPE_MOTORCYCLE) {
            return tripOk && tripTypesOk
        }

        return allowPet != null &&
            allowSmoking != null &&
            tripOk &&
            tripTypesOk &&
            !minimumDriverAge.isNullOrBlank() &&
            !minimumLicenseYears.isNullOrBlank()
    }

    fun isLocationChecklistOk(): Boolean {
        refreshLegacyStep6Fields()

        val deliveryOptionOk = pickupOnLocation || deliveryByFee
        val pickupConfigOk = when {
            !pickupOnLocation -> true
            pickupAddressMode == PICKUP_ADDRESS_MODE_PROFILE ->
                pickupProfileAddressConfirmed && hasCompletePickupAddress()
            pickupAddressMode == PICKUP_ADDRESS_MODE_SPECIFIC -> hasCompletePickupAddress()
            else -> false
        }
        val deliveryByFeeOk = !deliveryByFee ||
            (!deliveryRadiusKm.isNullOrBlank() && !deliveryFee.isNullOrBlank())

        return deliveryOptionOk && pickupConfigOk && deliveryByFeeOk
    }

    fun hasCompletePickupAddress(): Boolean {
        return !pickupStreet.isNullOrBlank() &&
            !pickupNumber.isNullOrBlank() &&
            !pickupNeighborhood.isNullOrBlank() &&
            !pickupCity.isNullOrBlank() &&
            !pickupState.isNullOrBlank() &&
            !pickupCep.isNullOrBlank()
    }

    fun refreshLegacyStep6Fields() {
        if (!pickupOnLocation) {
            cityState = null
            neighborhood = null
            pickupPoint = null
            return
        }

        val street = pickupStreet?.trim().orEmpty()
        val number = pickupNumber?.trim().orEmpty()
        val hood = pickupNeighborhood?.trim().orEmpty()
        val city = pickupCity?.trim().orEmpty()
        val state = pickupState?.trim().orEmpty()

        val hasAnyPickupPiece = listOf(street, number, hood, city, state).any { it.isNotBlank() }
        if (!hasAnyPickupPiece) {
            cityState = null
            neighborhood = null
            pickupPoint = null
            return
        }

        pickupPoint = listOf(street, number)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { null }
        neighborhood = hood.ifBlank { null }
        cityState = listOf(city, state)
            .filter { it.isNotBlank() }
            .joinToString("/")
            .ifBlank { null }
    }

    fun requiredPhotoKeys(): List<String> {
        return if (vehicleType == TYPE_MOTORCYCLE) {
            listOf(
                PHOTO_FRONT,
                PHOTO_REAR,
                PHOTO_LEFT_SIDE,
                PHOTO_RIGHT_SIDE,
                PHOTO_DASHBOARD,
                PHOTO_ENGINE
            )
        } else {
            listOf(
                PHOTO_FRONT,
                PHOTO_REAR,
                PHOTO_LEFT_SIDE,
                PHOTO_RIGHT_SIDE,
                PHOTO_DASHBOARD,
                PHOTO_TRUNK
            )
        }
    }

    fun requiredPhotosChecked(): Boolean {
        return requiredPhotoKeys().all { key ->
            uploadedPhotoUrls[key].isNullOrBlank().not()
        }
    }

    fun photoCount(): Int = uploadedPhotoUrls.values.count { it.isNotBlank() }

    fun toDraftJson(): JSONObject {
        refreshLegacyStep6Fields()

        val json = JSONObject()

        json.put("currentStep", currentStep)
        json.put("vehicleType", vehicleType)
        json.put("availabilityMode", availabilityMode)

        json.put("brand", brand)
        json.put("model", model)
        json.put("manufactureYear", manufactureYear)
        json.put("modelYear", modelYear)
        json.put("trimVersion", trimVersion)
        json.put("plate", plate)
        json.put("renavamOrChassis", renavamOrChassis)
        json.put("color", color)
        json.put("bodyType", bodyType)
        json.put("fuelType", fuelType)
        json.put("transmissionType", transmissionType)
        json.put("doors", doors)
        json.put("seats", seats)
        json.put("highlightTags", JSONArray(highlightTags.toList()))

        val photosJson = JSONObject()
        uploadedPhotoUrls.forEach { (slotKey, slotUrl) ->
            photosJson.put(slotKey, slotUrl)
        }
        json.put("uploadedPhotoUrls", photosJson)
        json.put("shortVideoUrl", shortVideoUrl)

        json.put("mileage", mileage)
        json.put("condition", condition)
        json.put("hadAccident", hadAccident)
        json.put("accidentDescription", accidentDescription)
        json.put("observations", observations)
        json.put("safetyItems", JSONArray(safetyItems.toList()))
        json.put("comfortItems", JSONArray(comfortItems.toList()))
        json.put("dailyPrice", dailyPrice)

        json.put("documentsUpToDate", documentsUpToDate)
        json.put("ipvaLicensingOk", ipvaLicensingOk)
        json.put("hasInsurance", hasInsurance)
        json.put("insuranceType", insuranceType)
        json.put("allowPet", allowPet)
        json.put("allowSmoking", allowSmoking)
        json.put("allowTrip", allowTrip)
        json.put("allowedTripTypes", JSONArray(allowedTripTypes.toList()))
        json.put("minimumDriverAge", minimumDriverAge)
        json.put("minimumLicenseYears", minimumLicenseYears)

        json.put("cityState", cityState)
        json.put("neighborhood", neighborhood)
        json.put("pickupPoint", pickupPoint)
        json.put("pickupOnLocation", pickupOnLocation)
        json.put("deliveryByFee", deliveryByFee)
        json.put("deliveryRadiusKm", deliveryRadiusKm)
        json.put("deliveryFee", deliveryFee)
        json.put("pickupAddressMode", pickupAddressMode)
        json.put("pickupProfileAddressConfirmed", pickupProfileAddressConfirmed)
        json.put("pickupSearchQuery", pickupSearchQuery)
        json.put("pickupStreet", pickupStreet)
        json.put("pickupNumber", pickupNumber)
        json.put("pickupNeighborhood", pickupNeighborhood)
        json.put("pickupCity", pickupCity)
        json.put("pickupState", pickupState)
        json.put("pickupCep", pickupCep)
        json.put("pickupLatitude", pickupLatitude)
        json.put("pickupLongitude", pickupLongitude)

        val scheduleJson = JSONObject()
        weeklySchedule.forEach { (day, schedule) ->
            scheduleJson.put(day, JSONObject().apply {
                put("enabled", schedule.enabled)
                put("startTime", schedule.startTime)
                put("endTime", schedule.endTime)
            })
        }
        json.put("weeklySchedule", scheduleJson)

        return json
    }

    fun restoreFromJson(json: JSONObject) {
        currentStep = json.optInt("currentStep", 0)
        vehicleType = json.optNullableString("vehicleType")
        availabilityMode = json.optNullableString("availabilityMode")

        brand = json.optNullableString("brand")
        model = json.optNullableString("model")
        manufactureYear = json.optNullableString("manufactureYear")
        modelYear = json.optNullableString("modelYear")
        trimVersion = json.optNullableString("trimVersion")
        plate = json.optNullableString("plate")
        renavamOrChassis = json.optNullableString("renavamOrChassis")
        color = json.optNullableString("color")
        bodyType = json.optNullableString("bodyType")
        fuelType = json.optNullableString("fuelType")
        transmissionType = json.optNullableString("transmissionType")
        doors = json.optNullableString("doors")
        seats = json.optNullableString("seats")
        json.optJSONArray("highlightTags").toStringSet(highlightTags)

        uploadedPhotoUrls.clear()
        val photosJson = json.optJSONObject("uploadedPhotoUrls")
        if (photosJson != null) {
            val keys = photosJson.keys()
            while (keys.hasNext()) {
                val slotKey = keys.next()
                val slotUrl = photosJson.optString(slotKey).trim()
                if (slotUrl.isNotBlank()) {
                    uploadedPhotoUrls[slotKey] = slotUrl
                }
            }
        }
        shortVideoUrl = json.optNullableString("shortVideoUrl")

        mileage = json.optNullableString("mileage")
        condition = json.optNullableString("condition")
        hadAccident = json.optNullableBoolean("hadAccident")
        accidentDescription = json.optNullableString("accidentDescription")
        observations = json.optNullableString("observations")
        json.optJSONArray("safetyItems").toStringSet(safetyItems)
        json.optJSONArray("comfortItems").toStringSet(comfortItems)
        dailyPrice = json.optNullableString("dailyPrice")

        documentsUpToDate = json.optNullableBoolean("documentsUpToDate")
        ipvaLicensingOk = json.optNullableBoolean("ipvaLicensingOk")
        hasInsurance = json.optNullableBoolean("hasInsurance")
        insuranceType = json.optNullableString("insuranceType")
        allowPet = json.optNullableBoolean("allowPet")
        allowSmoking = json.optNullableBoolean("allowSmoking")
        allowTrip = json.optNullableBoolean("allowTrip")
        json.optJSONArray("allowedTripTypes").toStringSet(allowedTripTypes)
        minimumDriverAge = json.optNullableString("minimumDriverAge")
        minimumLicenseYears = json.optNullableString("minimumLicenseYears")

        cityState = json.optNullableString("cityState")
        neighborhood = json.optNullableString("neighborhood")
        pickupPoint = json.optNullableString("pickupPoint")
        pickupOnLocation = json.optBoolean("pickupOnLocation", true)
        deliveryByFee = json.optBoolean("deliveryByFee", false)
        deliveryRadiusKm = json.optNullableString("deliveryRadiusKm")
        deliveryFee = json.optNullableString("deliveryFee")
        pickupAddressMode = json.optNullableString("pickupAddressMode")
        pickupProfileAddressConfirmed = json.optBoolean("pickupProfileAddressConfirmed", false)
        pickupSearchQuery = json.optNullableString("pickupSearchQuery")
        pickupStreet = json.optNullableString("pickupStreet")
        pickupNumber = json.optNullableString("pickupNumber")
        pickupNeighborhood = json.optNullableString("pickupNeighborhood")
        pickupCity = json.optNullableString("pickupCity")
        pickupState = json.optNullableString("pickupState")
        pickupCep = json.optNullableString("pickupCep")
        pickupLatitude = json.optNullableDouble("pickupLatitude")
        pickupLongitude = json.optNullableDouble("pickupLongitude")

        migrateLegacyPickupAddressIfNeeded()

        val scheduleJson = json.optJSONObject("weeklySchedule") ?: JSONObject()
        weeklySchedule.forEach { (day, schedule) ->
            val item = scheduleJson.optJSONObject(day) ?: return@forEach
            schedule.enabled = item.optBoolean("enabled", false)
            schedule.startTime = item.optString("startTime", "")
            schedule.endTime = item.optString("endTime", "")
        }
    }

    private fun migrateLegacyPickupAddressIfNeeded() {
        if (pickupAddressMode.isNullOrBlank() && pickupOnLocation) {
            val hasNewAddressData = !pickupStreet.isNullOrBlank() ||
                !pickupNumber.isNullOrBlank() ||
                !pickupNeighborhood.isNullOrBlank() ||
                !pickupCity.isNullOrBlank() ||
                !pickupState.isNullOrBlank() ||
                !pickupCep.isNullOrBlank()

            if (hasNewAddressData) {
                pickupAddressMode = PICKUP_ADDRESS_MODE_SPECIFIC
            } else {
                val hasLegacyAddress = !pickupPoint.isNullOrBlank() ||
                    !neighborhood.isNullOrBlank() ||
                    !cityState.isNullOrBlank()
                if (hasLegacyAddress) {
                    pickupAddressMode = PICKUP_ADDRESS_MODE_SPECIFIC
                    pickupStreet = pickupStreet ?: pickupPoint
                    pickupNeighborhood = pickupNeighborhood ?: neighborhood
                    val parsed = parseLegacyCityState(cityState)
                    pickupCity = pickupCity ?: parsed.first
                    pickupState = pickupState ?: parsed.second
                }
            }
        }

        refreshLegacyStep6Fields()
    }

    private fun parseLegacyCityState(raw: String?): Pair<String?, String?> {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null to null

        val slash = value.split("/").map { it.trim() }.filter { it.isNotBlank() }
        if (slash.size >= 2) {
            return slash.dropLast(1).joinToString(" ").ifBlank { null } to slash.last().ifBlank { null }
        }

        val dash = value.split("-").map { it.trim() }.filter { it.isNotBlank() }
        if (dash.size >= 2) {
            return dash.dropLast(1).joinToString(" ").ifBlank { null } to dash.last().ifBlank { null }
        }

        return value to null
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? {
        if (isNull(key)) return null
        val value = opt(key)
        return value as? Boolean
    }

    private fun JSONArray?.toStringSet(target: MutableSet<String>) {
        target.clear()
        if (this == null) return
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotEmpty()) target.add(value)
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
