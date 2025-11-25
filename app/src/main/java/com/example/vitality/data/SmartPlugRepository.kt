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

/**
 * Repository ottimizzato per acquisizione **locale HTTP** dalle prese Shelly.
 * - Nessun cloud
 * - Nessun rate‑limit
 * - Endpoint: /rpc/Switch.GetStatus
 * - Concorrenza controllata (Semaphore)
 */
class SmartPlugRepository(
    client: OkHttpClient? = null,
    private val maxParallel: Int = 3,          // più alto rispetto al cloud
    private val perRequestDelayMs: Long = 80L   // leggero staggering
) {

    private val TAG = "SmartPlugRepoLocal"

    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val semaphore = Semaphore(maxParallel)

    /**
     * Mappa nome → IP (locale). Più efficiente tenerla qui.
     */
    private val plugIpMap: Map<String, String> = mapOf(
        "PRESA_VITTORIA" to "192.168.10.161",
        "PRESA_NICOLE"   to "192.168.10.177",
        "PRESA_SERENA"   to "192.168.10.180",
        "PRESA_RICHARD"  to "192.168.10.153",
        "PRESA_NIBRAS"   to "192.168.10.176",
        "PRESA_GLORIA"   to "192.168.10.146",
        "PRESA_CECILIA"  to "192.168.10.179",
        "PRESA_STUFETTA_NICOLE" to "192.168.10.84"

    )

    private fun offlineStatus(name: String, reason: String) = SmartPlugStatus(
        name = name,
        id = plugIpMap[name] ?: "",
        online = false,
        apower = 0.0,
        voltage = 0.0,
        current = 0.0,
        temperature = 0.0,
        aenergyTotal = 0.0,
        ssid = "",
        rssi = 0,
        room = deriveRoom(name),
        error = reason
    )

    private fun deriveRoom(plugName: String): String =
        plugName.removePrefix("PRESA_")
            .replaceFirstChar { it.uppercase() }

    /**
     * 🔎 Query locale /rpc/Switch.GetStatus
     */
    private suspend fun fetchShellyLocal(name: String, ip: String): SmartPlugStatus =
        withContext(Dispatchers.IO) {
            try {
                delay(perRequestDelayMs) // leggero staggering

                val url = "http://$ip/rpc/Switch.GetStatus?id=0"
                val req = Request.Builder().url(url).get().build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext offlineStatus(name, "HTTP ${resp.code}")
                    }

                    val body = resp.body?.string().orEmpty()
                    if (body.isEmpty()) return@withContext offlineStatus(name, "Empty body")

                    val json = JSONObject(body)

                    val apower = json.optDouble("apower", 0.0)
                    val output = json.optBoolean("output", false)
                    val voltage = json.optDouble("voltage", 0.0)
                    val current = json.optDouble("current", 0.0)
                    val temperature = json.optJSONObject("temperature")?.optDouble("tC", 0.0) ?: 0.0

                    val aenergy = json.optJSONObject("aenergy")
                    val totalEnergy = aenergy?.optDouble("total", 0.0) ?: 0.0
                    val byMinute = aenergy?.optJSONArray("by_minute")?.let { arr ->
                        List(arr.length()) { idx -> arr.optDouble(idx) }
                    }
                    val aenergyTs = aenergy?.optLong("minute_ts")
                    val errors = json.optJSONArray("errors")?.let { arr ->
                        List(arr.length()) { idx -> arr.optString(idx) }
                    }

                    Log.d(TAG, "$name → ${apower.roundToInt()}W")

                    return@withContext SmartPlugStatus(
                        name = name,
                        id = ip,
                        online = true,
                        apower = apower,
                        voltage = voltage,
                        current = current,
                        temperature = temperature,
                        aenergyTotal = totalEnergy,
                        ssid = "",
                        rssi = 0,
                        room = deriveRoom(name),
                        output = output,
                        pf = null,
                        freq = null,
                        overpower = null,
                        overtemperature = null,
                        errors = errors,
                        aenergyByMinute = byMinute,
                        aenergyMinuteTs = aenergyTs,
                        ip = ip,
                        wifiConnected = null,
                        wifiStaIP = null,
                        cloudConnected = null,
                        mqttConnected = null,
                        mac = null,
                        model = null,
                        fw = null,
                        fwId = null,
                        uptimeSec = null,
                        hasUpdate = null
                    )
                }
            } catch (e: Exception) {
                return@withContext offlineStatus(name, e.localizedMessage ?: "Exception")
            }
        }

    /**
     * 🔁 Recupera tutte le prese della stanza (in locale)
     */
    suspend fun fetchPlugsForRoom(room: String): List<SmartPlugStatus> = coroutineScope {
        val roomKey = roomPlugIds.keys.firstOrNull { normalizeName(it) == normalizeName(room) }
        val names = roomKey?.let { roomPlugIds[it]?.plugs }.orEmpty()

        val tasks = names.map { plugName ->
            val ip = plugIpMap[plugName]
            if (ip == null) {
                Log.w(TAG, "IP mancante per $plugName")
                async { offlineStatus(plugName, "Missing IP") }
            } else async {
                semaphore.withPermit { fetchShellyLocal(plugName, ip) }
            }
        }

        val list = tasks.awaitAll().sortedBy { it.name }

        // diagnostica
        if (list.isNotEmpty()) {
            val powers = list.map { it.apower }
            val mean = powers.average()
            val samePct = sameConsecutivePct(powers.sorted())
            Log.i(TAG, "ROOM $room → plugs=${list.size} meanW=${"%.1f".format(mean)} same%=${"%.1f".format(samePct * 100)}")
        }

        list
    }

    fun sameConsecutivePct(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        var same = 0
        for (i in 1 until v.size) if (abs(v[i] - v[i - 1]) < 1e-6) same++
        return same.toDouble() / (v.size - 1)
    }

    suspend fun switchOn(name: String) = withContext(Dispatchers.IO) {
        plugIpMap[name]?.let { ip ->
            val url = "http://$ip/rpc/Switch.Set?id=0&on=true"
            client.newCall(Request.Builder().url(url).get().build()).execute().close()
        }
    }

    suspend fun switchOff(name: String) = withContext(Dispatchers.IO) {
        plugIpMap[name]?.let { ip ->
            val url = "http://$ip/rpc/Switch.Set?id=0&on=false"
            client.newCall(Request.Builder().url(url).get().build()).execute().close()
        }
    }

}
