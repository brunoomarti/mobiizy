package com.brunocodex.kotlinproject.utils

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface NominatimApi {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("limit") limit: Int = 25, // <<< era 8
        @Query("countrycodes") countryCodes: String = "br",
        @Query("accept-language") acceptLanguage: String = "pt-BR"
    ): List<NominatimResult>

}

data class NominatimResult(
    @SerializedName("display_name") val displayName: String?,
    val address: NominatimAddress?
)

data class NominatimAddress(
    val road: String?,
    @SerializedName("house_number") val houseNumber: String?,
    val suburb: String?,
    val neighbourhood: String?,
    val city: String?,
    val town: String?,
    val municipality: String?,
    val state: String?,
    @SerializedName("postcode") val postcode: String?
)
