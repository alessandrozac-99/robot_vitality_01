package com.example.vitality.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private const val TAG = "HistoryRepo"
private const val BASE_URL = "https://api.wattsense.com"
private const val SIZE_MAX: Int = 2500 // sufficiente per 7d @5min (~2016 bucket)

/**
 * Repository storico che replica l'approccio di "VitalityApp":
 * - OkHttp raw GET
 * - parsing JSON manuale
 * - paginazione via header `Link: <...>; rel="next"`
 * e poi esegue merge T/H, resampling e padding per la UI.
 */
class HistoryRepository(
    private val api: WattsenseApi = RetrofitClient.create(),
    private val client: OkHttpClient = RetrofitClient.okHttpClient() // deve esistere; in caso contrario, esponilo in RetrofitClient
) {

    suspend fun getTemperatureHumidityHistory(
        roomName: String,
        windowMs: Long,
        stepMs: Long
    ): List<HistoricalItem> = withContext(Dispatchers.IO) {

        val key = roomSensorIds.keys.firstOrNull { normalizeName(it) == normalizeName(roomName) }
        if (key == null) {
            Log.w(TAG, "⚠️ Nessun sensore definito per '$roomName'")
            return@withContext emptyList()
        }
        val ids = roomSensorIds[key] ?: return@withContext emptyList()
        val deviceId = ids.deviceId

        val now = System.currentTimeMillis()
        val since = now - windowMs

        // Stima size richiesta allo storico (bucket ~ window/step)
        val approxBuckets = (windowMs / stepMs).toInt().coerceAtLeast(100)
        val reqSize = approxBuckets.coerceAtMost(SIZE_MAX)

        try {
            // --- FETCH “alla VitalityApp”: OkHttp + paginazione Link ---
            val tempList = fetchPropertySeries(deviceId, ids.temperatureId, since, now, reqSize, "T[$key]")
            val humList  = fetchPropertySeries(deviceId, ids.humidityId,    since, now, reqSize, "H[$key]")

            diagSeries("IN.T", tempList)
            diagSeries("IN.H", humList)

            var merged = mergeTH(tempList, humList)
            if (merged.isEmpty()) {
                // Fallback come prima: includeHistory dall’istantaneo
                Log.i(TAG, "⭘ Nessun match T/H → provo includeHistory")
                val miniTemp = includeHistorySeries(deviceId, ids.temperatureId, "IH.T[$key]")
                val miniHum  = includeHistorySeries(deviceId, ids.humidityId,    "IH.H[$key]")
                diagSeries("IH.T", miniTemp)
                diagSeries("IH.H", miniHum)
                merged = mergeTH(miniTemp, miniHum)
            }

            if (merged.isEmpty()) {
                Log.i(TAG, "⭘ Nessun dato storico per '$key' (device=$deviceId) in ${windowMs/3600000}h")
                return@withContext emptyList()
            }

            // Resampling + padding per asse stabile
            diagMerged("MRG", merged)
            val resampled = merged.resampleEvery(stepMs).also { diagMerged("RSM", it) }
            val padded = resampled.padUniformHold(stepMs, since, now).also {
                diagPadding("PAD", it, stepMs, since, now)
            }

            Log.i(TAG, "✅ ${padded.size} punti (${windowMs/3600000}h @${stepMs/60000}m) per '$key' (device=$deviceId)")
            padded
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore storico '$roomName': ${e.message}", e)
            emptyList()
        }
    }

    // --------------------- FETCH alla VitalityApp ---------------------

    private suspend fun fetchPropertySeries(
        deviceId: String,
        propertyId: String,
        since: Long,
        until: Long,
        size: Int,
        tag: String
    ): List<PropertyHistoricalItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<PropertyHistoricalItem>()
        var url = "$BASE_URL/v1/devices/$deviceId/properties?since=$since&until=$until&property=$propertyId&size=$size"
        val visited = HashSet<String>()

        var page = 0
        while (true) {
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()

            if (!resp.isSuccessful) {
                Log.w(TAG, "ℹ️ $tag HTTP ${resp.code} ${resp.message}")
                break
            }

            val bodyStr = resp.body?.string()
            if (bodyStr.isNullOrBlank()) {
                Log.d(TAG, "⭘ $tag body vuoto (page=$page)")
                break
            }

            try {
                val arr = JSONArray(bodyStr)
                for (i in 0 until arr.length()) {
                    val obj: JSONObject = arr.getJSONObject(i)
                    val property = obj.optString("property", propertyId)
                    val timestamp = obj.optLong("timestamp", 0L)
                    // Wattsense può fornire payload e/o scaledPayload
                    val value = parseDouble(obj.opt("payload")) ?: parseDouble(obj.opt("scaledPayload"))
                    if (timestamp > 0 && value != null) {
                        out.add(PropertyHistoricalItem(property = property, timestamp = timestamp, payload = value, scaledPayload = null))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ $tag parsing JSON: ${e.message}")
            }

            val linkHeader = resp.headers["Link"]
            val nextLink = parseNextLink(linkHeader)
            if (nextLink.isNullOrEmpty() || !visited.add(nextLink)) break
            url = if (nextLink.startsWith("http")) nextLink else "$BASE_URL$nextLink"
            page++
        }

        Log.d(TAG, "HTTP $tag total=${out.size} points")
        out.sortedBy { it.timestamp }
    }

    private fun parseNextLink(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        val regex = "<([^>]*)>; rel=\"next\"".toRegex()
        return regex.find(linkHeader)?.groups?.get(1)?.value
    }

    private fun parseDouble(any: Any?): Double? = when (any) {
        is Number -> any.toDouble()
        is String -> any.toDoubleOrNull()
        else -> null
    }

    // --------------------- Fallback includeHistory (instant) ---------------------

    private suspend fun includeHistorySeries(
        deviceId: String,
        propertyId: String,
        tag: String
    ): List<PropertyHistoricalItem> = try {
        val pr = api.getProperty(deviceId, propertyId, includeHistory = true)
        pr.history?.readValues.orEmpty().also {
            Log.d(TAG, "$tag includeHistory size=${it.size}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ $tag includeHistory error: ${e.message}")
        emptyList()
    }

    // --------------------- Merge / Resampling / Padding ---------------------

    private fun mergeTH(
        temp: List<PropertyHistoricalItem>,
        hum: List<PropertyHistoricalItem>
    ): List<HistoricalItem> {
        if (temp.isEmpty() || hum.isEmpty()) return emptyList()
        val deltas = ArrayList<Long>()

        val merged = temp.mapNotNull { t ->
            val h = hum.minByOrNull { abs(it.timestamp - t.timestamp) }
            val dt = if (h != null) abs(h.timestamp - t.timestamp) else null
            dt?.let { deltas += it }

            val tv = t.payload ?: t.scaledPayload
            val hv = h?.payload ?: h?.scaledPayload
            if (tv != null && hv != null) {
                HistoricalItem(timestamp = t.timestamp, temperature = tv, humidity = hv)
            } else null
        }.sortedBy { it.timestamp }

        if (deltas.isNotEmpty()) {
            deltas.sort()
            val p50 = deltas[deltas.size / 2]
            val p90 = deltas[(deltas.size * 9) / 10]
            Log.d(TAG, "MRG Δt(ms): p50=$p50, p90=$p90, max=${deltas.last()}")
        }
        return merged
    }

    private fun List<Double>.avgOrNull(): Double? =
        if (isEmpty()) null else this.sum() / this.size

    /** Resampling uniforme: media per bucket di ampiezza stepMs. */
    private fun List<HistoricalItem>.resampleEvery(stepMs: Long): List<HistoricalItem> {
        if (this.isEmpty()) return this
        val grouped = this.groupBy { it.timestamp / stepMs }
        return grouped.entries
            .map { (bucket, items) ->
                val ts = bucket * stepMs
                val tAvg = items.map { it.temperature }.avgOrNull()
                val hAvg = items.map { it.humidity }.avgOrNull()
                if (tAvg != null && hAvg != null) HistoricalItem(ts, tAvg, hAvg) else null
            }
            .filterNotNull()
            .sortedBy { it.timestamp }
    }

    /** Padding con hold-last-value per coprire [start,end]. */
    private fun List<HistoricalItem>.padUniformHold(
        stepMs: Long,
        start: Long,
        end: Long
    ): List<HistoricalItem> {
        if (this.isEmpty()) return this
        val byBucket = this.associateBy { it.timestamp / stepMs }.toMutableMap()
        var last: HistoricalItem? = null
        val out = ArrayList<HistoricalItem>()
        var b = (start / stepMs)
        val endBucket = (end / stepMs)
        while (b <= endBucket) {
            val ts = b * stepMs
            val item = byBucket[b] ?: last?.copy(timestamp = ts)
            if (item != null) {
                out += item
                last = item
            }
            b++
        }
        return out
    }

    // --------------------- Diagnostica ---------------------

    private fun diagSeries(tag: String, list: List<PropertyHistoricalItem>) {
        if (list.isEmpty()) {
            Log.d(TAG, "$tag size=0")
            return
        }
        val tsMin = list.minOf { it.timestamp }
        val tsMax = list.maxOf { it.timestamp }
        val vals = list.mapNotNull { it.payload ?: it.scaledPayload }
        val mean = if (vals.isNotEmpty()) vals.sum() / vals.size else 0.0
        val samePct = if (vals.size < 2) 0.0 else {
            var same = 0
            for (i in 1 until vals.size) if (vals[i] == vals[i-1]) same++
            same.toDouble() / (vals.size - 1)
        }
        Log.d(TAG, "$tag size=${list.size} ts=[${shortTs(tsMin)}..${shortTs(tsMax)}] mean=${fmt(mean)} same%=${fmt(samePct*100)}")
    }

    private fun diagMerged(tag: String, list: List<HistoricalItem>) {
        if (list.isEmpty()) {
            Log.d(TAG, "$tag size=0")
            return
        }
        val tsMin = list.first().timestamp
        val tsMax = list.last().timestamp
        val tVals = list.map { it.temperature }
        val hVals = list.map { it.humidity }
        fun samePct(v: List<Double>): Double {
            if (v.size < 2) return 0.0
            var s = 0
            for (i in 1 until v.size) if (v[i] == v[i-1]) s++
            return s.toDouble() / (v.size - 1)
        }
        Log.d(TAG, "$tag size=${list.size} ts=[${shortTs(tsMin)}..${shortTs(tsMax)}] T.same%=${fmt(samePct(tVals)*100)} H.same%=${fmt(samePct(hVals)*100)}")
    }

    private fun diagPadding(tag: String, list: List<HistoricalItem>, stepMs: Long, start: Long, end: Long) {
        val totalBuckets = ((end - start) / stepMs + 1).toInt().coerceAtLeast(0)
        val real = list.size
        Log.d(TAG, "$tag buckets=$real/$totalBuckets step=${stepMs/60000}m range=${(end-start)/3600000}h")
    }

    private fun fmt(d: Double?): String = if (d == null) "null" else String.format("%.3f", d)
    private fun shortTs(ts: Long): String = String.format("%tH:%tM:%tS", ts, ts, ts)
}
