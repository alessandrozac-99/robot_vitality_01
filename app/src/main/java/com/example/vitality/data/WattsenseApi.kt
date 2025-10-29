package com.example.vitality.data

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface WattsenseApi {
    @GET("v1/devices/{deviceId}/properties/{property}")
    suspend fun getProperty(
        @Path("deviceId") deviceId: String,
        @Path("property") propertyIdOrSlug: String,
        @Query("includeHistory") includeHistory: Boolean = false
    ): PropertyResponse

    // ⬇️ Ritorna Response<> per gestire 204 / body nullo
    @GET("v1/devices/{deviceId}/properties")
    suspend fun getHistoricalData(
        @Path("deviceId") deviceId: String,
        @Query("since") since: Long,
        @Query("until") until: Long,
        @Query("property") propertyId: String?,
        @Query("size") size: Int = 500
    ): Response<List<PropertyHistoricalItem>>
}
