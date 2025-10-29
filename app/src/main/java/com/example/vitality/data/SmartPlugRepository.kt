package com.example.vitality.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 📡 Stato presa Shelly (campi estesi).
 */
data class SmartPlugStatus(
    val name: String,
    val id: String,
    val online: Boolean,
    val apower: Double,
    val voltage: Double,
    val current: Double,
    val temperature: Double,
    val aenergyTotal: Double,
    val ssid: String,
    val rssi: Int,
    val room: String,
    val error: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    // Extra
    val output: Boolean? = null,
    val pf: Double? = null,
    val freq: Double? = null,
    val overpower: Boolean? = null,
    val overtemperature: Boolean? = null,
    val errors: List<String>? = null,
    val aenergyByMinute: List<Double>? = null,
    val aenergyMinuteTs: Long? = null,
    val ip: String? = null,
    val wifiConnected: Boolean? = null,
    val wifiStaIP: String? = null,
    val cloudConnected: Boolean? = null,
    val mqttConnected: Boolean? = null,
    val mac: String? = null,
    val model: String? = null,
    val fw: String? = null,
    val fwId: String? = null,
    val uptimeSec: Long? = null,
    val hasUpdate: Boolean? = null
)

private const val TAG = "SmartPlugRepo"
private const val CLOUD_URL = "https://shelly-149-eu.shelly.cloud/device/status"

// ⚠️ sostituisci con la tua chiave
private const val CLOUD_API_KEY =
    "MmJjZmQ2dWlk06784AF88ECF7960783542AC441C1293653638794B8AB7630206E2C8D7C949A23FE6358BD116670F"

/**
 * Repository con:
 * - Retry esponenziale + jitter su 429/5xx
 * - Rispetto header Retry-After
 * - Concorrenza limitata (per evitare burst → 429)
 * - Piccolo staggering tra richieste
 */
class SmartPlugRepository(
    client: OkHttpClient? = null,
    private val maxParallel: Int = 2,          // ← limite di concorrenza consigliato
    private val baseBackoffMs: Long = 350L,    // ← backoff di base
    private val maxRetries: Int = 4,           // ← retry totali
    private val perRequestJitterMs: Long = 120 // ← jitter tra richieste parallele
) {

    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val semaphore = Semaphore(maxParallel)

    private fun deriveRoom(plugName: String): String {
        val n = normalizeName(plugName)
        return roomPlugIds.entries
            .firstOrNull { (_, bundle) -> bundle.plugs.any { normalizeName(it) == n } }
            ?.key ?: plugName.removePrefix("PRESA_").replaceFirstChar { it.uppercase() }
    }

    private fun offlineStatus(name: String, id: String, reason: String) = SmartPlugStatus(
        name = name, id = id, online = false,
        apower = 0.0, voltage = 0.0, current = 0.0, temperature = 0.0,
        aenergyTotal = 0.0, ssid = "", rssi = 0, room = deriveRoom(name), error = reason
    )

    private suspend fun jitter(ms: Long) = delay(ms + Random.nextLong(0L, ms / 2 + 1))

    /**
     * Fetch Cloud UNA presa con gestione 429 e 5xx.
     */
    private suspend fun fetchShellyStatus(name: String, plugId: String): SmartPlugStatus =
        withContext(Dispatchers.IO) {
            val url = "$CLOUD_URL?id=$plugId&auth_key=$CLOUD_API_KEY"
            var lastErr: String? = null

            repeat(maxRetries) { attempt ->
                try {
                    // Stagger leggero tra richieste concorrenti
                    if (attempt == 0) jitter(perRequestJitterMs)

                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        val code = resp.code
                        if (!resp.isSuccessful) {
                            // Gestione specifica 429 (Rate Limit)
                            if (code == 429) {
                                val retryAfterHeader = resp.headers["Retry-After"]
                                val retryAfterMs = retryAfterHeader?.toLongOrNull()?.times(1000)
                                lastErr = "HTTP 429 (rate limit)"
                                val backoff = retryAfterMs ?: (baseBackoffMs * (1L shl attempt))
                                Log.w(TAG, "429 $name → backoff ${backoff}ms (attempt=$attempt)")
                                jitter(backoff)
                                return@use
                            }
                            // 5xx → retry con backoff
                            if (code in 500..599 && attempt < maxRetries - 1) {
                                lastErr = "HTTP $code"
                                val backoff = baseBackoffMs * (1L shl attempt)
                                Log.w(TAG, "$code $name → retry in ${backoff}ms")
                                jitter(backoff)
                                return@use
                            }
                            lastErr = "HTTP $code"
                            return@withContext offlineStatus(name, plugId, lastErr!!)
                        }

                        val body = resp.body?.string().orEmpty()
                        if (body.isEmpty()) return@withContext offlineStatus(name, plugId, "Empty body")

                        val root = JSONObject(body)
                        val data = root.optJSONObject("data")
                            ?: return@withContext offlineStatus(name, plugId, "Missing data")
                        val status = data.optJSONObject("device_status")
                            ?: return@withContext offlineStatus(name, plugId, "Missing device_status")

                        val sw     = status.optJSONObject("switch:0") ?: JSONObject()
                        val wifi   = status.optJSONObject("wifi") ?: JSONObject()
                        val cloud  = status.optJSONObject("cloud") ?: JSONObject()
                        val mqtt   = status.optJSONObject("mqtt") ?: JSONObject()
                        val sys    = status.optJSONObject("sys") ?: status.optJSONObject("system") ?: JSONObject()
                        val hwinfo = sys.optJSONObject("hwinfo") ?: JSONObject()

                        val apower = sw.optDouble("apower", 0.0)
                        val voltage = sw.optDouble("voltage", 0.0)
                        val current = sw.optDouble("current", 0.0)
                        val pf = sw.optDouble("pf").takeUnless { it.isNaN() }
                        val freq = sw.optDouble("freq").takeUnless { it.isNaN() }
                        val output = sw.optBoolean("output")
                        val temperature = sw.optJSONObject("temperature")?.optDouble("tC", 0.0) ?: 0.0

                        val aenergyObj = sw.optJSONObject("aenergy")
                        val aenergyTotal = aenergyObj?.optDouble("total", 0.0) ?: 0.0
                        val aenergyByMinute = aenergyObj?.optJSONArray("by_minute")?.let { arr ->
                            List(arr.length()) { idx -> arr.optDouble(idx) }
                        }
                        val aenergyTs = aenergyObj?.optLong("minute_ts")?.takeIf { it > 0 }

                        val errors = sw.optJSONArray("errors")?.let { arr ->
                            List(arr.length()) { idx -> arr.optString(idx) }
                        }
                        val overpower = sw.optBoolean("overpower", false)
                        val overtemp  = sw.optBoolean("overtemperature", false)

                        val ssid = wifi.optString("ssid", "")
                        val rssi = wifi.optInt("rssi", 0)
                        val ip   = wifi.optString("ip", null)
                        val wifiStaIP = wifi.optString("sta_ip", null)
                        val wifiConnected = wifi.optBoolean("connected", !ssid.isNullOrEmpty())

                        val cloudConnected = cloud.optBoolean("connected", false)
                        val mqttConnected  = mqtt.optBoolean("connected", false)

                        val mac       = sys.optString("mac", null) ?: hwinfo.optString("mac", null)
                        val model     = sys.optString("model", null) ?: hwinfo.optString("model", null)
                        val fw        = sys.optString("fw", null)
                        val fwId      = sys.optString("fw_id", null)
                        val uptimeSec = sys.optLong("uptime").takeIf { it > 0L }
                            ?: status.optLong("uptime").takeIf { it > 0L }
                        val hasUpdate = sys.optBoolean("has_update", false)

                        Log.d(TAG, "✅ $name ($plugId) ${apower.roundToInt()}W")

                        return@withContext SmartPlugStatus(
                            name = name, id = plugId, online = true,
                            apower = apower.coerceAtLeast(0.0),
                            voltage = if (voltage.isNaN()) 0.0 else voltage,
                            current = if (current.isNaN()) 0.0 else current,
                            temperature = if (temperature.isNaN()) 0.0 else temperature,
                            aenergyTotal = if (aenergyTotal.isNaN()) 0.0 else aenergyTotal,
                            ssid = ssid, rssi = rssi, room = deriveRoom(name),
                            output = output, pf = pf, freq = freq,
                            overpower = overpower, overtemperature = overtemp, errors = errors,
                            aenergyByMinute = aenergyByMinute, aenergyMinuteTs = aenergyTs,
                            ip = ip ?: wifiStaIP, wifiConnected = wifiConnected, wifiStaIP = wifiStaIP,
                            cloudConnected = cloudConnected, mqttConnected = mqttConnected,
                            mac = mac, model = model, fw = fw, fwId = fwId, uptimeSec = uptimeSec,
                            hasUpdate = hasUpdate
                        )
                    }
                } catch (e: Exception) {
                    lastErr = e.localizedMessage ?: "Exception"
                    if (attempt < maxRetries - 1) {
                        val backoff = baseBackoffMs * (1L shl attempt)
                        Log.w(TAG, "❌ $name ($plugId): $lastErr → retry in ${backoff}ms")
                        jitter(backoff)
                    } else {
                        Log.e(TAG, "❌ $name ($plugId) giving up: $lastErr")
                        return@withContext offlineStatus(name, plugId, lastErr!!)
                    }
                }
            }
            offlineStatus(name, plugId, lastErr ?: "Unknown")
        }

    /**
     * 🔁 Recupera prese della stanza con concorrenza limitata e staggering.
     */
    suspend fun fetchPlugsForRoom(room: String): List<SmartPlugStatus> = coroutineScope {
        val key = roomPlugIds.keys.firstOrNull { normalizeName(it) == normalizeName(room) }
        val names = key?.let { roomPlugIds[it]?.plugs }.orEmpty()
        if (names.isEmpty()) {
            Log.w(TAG, "⚠️ Nessuna presa mappata per '$room'")
            return@coroutineScope emptyList()
        }

        val nameToId = mapPlugNameToId()
        val tasks = names.mapNotNull { plugName ->
            val id = nameToId[plugName]
            if (id == null) {
                Log.w(TAG, "⚠️ ID mancante per '$plugName'")
                null
            } else async {
                semaphore.withPermit {
                    fetchShellyStatus(plugName, id)
                }
            }
        }

        val list = tasks.awaitAll().sortedBy { it.name }

        // diagnostica
        if (list.isNotEmpty()) {
            val powers = list.map { it.apower }
            val mean = powers.average()
            val p90 = powers.sorted().let { if (it.isNotEmpty()) it[(it.size * 9) / 10] else 0.0 }
            val samePct = sameConsecutivePct(powers.sorted())
            Log.i(TAG, "ROOM $room → plugs=${list.size} meanW=${"%.1f".format(mean)} p90=${"%.1f".format(p90)} same%=${"%.1f".format(samePct * 100)}")
        }
        list
    }

    suspend fun fetchAllSmartPlugs(): List<SmartPlugStatus> = coroutineScope {
        val allNames = roomPlugIds.values.flatMap { it.plugs }.distinct()
        val nameToId = mapPlugNameToId()
        val tasks = allNames.mapNotNull { plugName ->
            val id = nameToId[plugName]
            if (id == null) {
                Log.w(TAG, "⚠️ ID mancante per '$plugName'")
                null
            } else async {
                semaphore.withPermit { fetchShellyStatus(plugName, id) }
            }
        }
        tasks.awaitAll().sortedBy { it.name }
    }

    private fun sameConsecutivePct(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        var same = 0
        for (i in 1 until v.size) if (abs(v[i] - v[i - 1]) < 1e-6) same++
        return same.toDouble() / (v.size - 1)
    }

    // ===== Nome→ID =====
    private fun mapPlugNameToId(): Map<String, String> = mapOf(
        "PRESA_VITTORIA" to "8cbfeaa16060",
        "PRESA_NICOLE"   to "8cbfeaa953c8",
        "PRESA_SERENA"   to "8cbfeaa0fb4c",
        "PRESA_RICHARD"  to "e4b323150570",
        "PRESA_NIBRAS"   to "8cbfeaa16964",
        "PRESA_GLORIA"   to "8cbfeaa058f4",
        "PRESA_CECILIA"  to "8cbfeaa44018"
    )
}
