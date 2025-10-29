package com.example.vitality.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Recupera la temperatura esterna di Ancona via Open-Meteo (no API key).
 */
class WeatherRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    // Ancona approx: 43.6167 N, 13.5167 E
    private val url =
        "https://api.open-meteo.com/v1/forecast?latitude=43.6167&longitude=13.5167&current=temperature_2m&timezone=auto"

    suspend fun fetchAnconaOutdoorTempC(): Double? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("WeatherRepo", "HTTP ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string().orEmpty()
                if (body.isEmpty()) return@use null
                val json = JSONObject(body)
                val current = json.optJSONObject("current") ?: return@use null
                current.optDouble("temperature_2m", Double.NaN).takeIf { it.isFinite() }
            }
        } catch (e: Exception) {
            Log.e("WeatherRepo", "❌ ${e.localizedMessage}")
            null
        }
    }
}
