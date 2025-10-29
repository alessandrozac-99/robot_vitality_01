package com.example.vitality.ui.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitality.data.RetrofitClient
import com.example.vitality.data.Spmv
import com.example.vitality.data.WeatherRepository
import com.example.vitality.data.WattsenseApi
import com.example.vitality.data.RoomSensorIds
import com.example.vitality.data.normalizeName
import com.example.vitality.data.roomSensorIds
import com.example.vitality.model.Zone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TemiMapViewModel(application: Application) : AndroidViewModel(application) {

    // ==== Data sources ======================================================
    private val wattsense: WattsenseApi = RetrofitClient.create()
    private val weatherRepo = WeatherRepository()

    // ==== Map & zones state =================================================
    private val _mapBitmap = MutableStateFlow<ImageBitmap?>(null)
    val mapBitmap: StateFlow<ImageBitmap?> = _mapBitmap

    private val _mapInfo = MutableStateFlow<MapInfo?>(null)
    val mapInfo: StateFlow<MapInfo?> = _mapInfo

    private val _zones = MutableStateFlow<List<Zone>>(emptyList())
    val zones: StateFlow<List<Zone>> = _zones

    // Occupancy grezza (valori JSON: -1, 0, 100)
    private var occData: IntArray? = null

    // Overlay tinteggiato globale (tutte le zone)
    private val _tintOverlay = MutableStateFlow<ImageBitmap?>(null)
    val tintOverlay: StateFlow<ImageBitmap?> = _tintOverlay

    // Cache input per sPMV (per ricalcolo batch)
    private data class ZoneEnv(
        val tAir: Double,   // °C
        val rh: Double,     // %
        val tOut: Double,   // °C
        val met: Double? = null,
        val clo: Double? = null
    )

    private val zoneEnvCache = mutableMapOf<String, ZoneEnv>()
    private val spmvCache = mutableMapOf<String, Double>()

    private val _lastSpmvUpdate = MutableStateFlow<Long?>(null)
    val lastSpmvUpdate: StateFlow<Long?> = _lastSpmvUpdate

    // Jobs
    private var autoRefreshJob: Job? = null            // ricalcolo batch della cache
    private var globalPollJob: Job? = null             // ✅ polling T/RH per tutte le stanze + T esterna

    private val spmvAlpha = 0x66 // opacità overlay

    init {
        viewModelScope.launch {
            try {
                loadMap()
                loadZones()
                // refresh batch della sola cache (manteniamo a 1 min)
                startAutoSpmvRefresh(periodMinutes = 1)
                // ✅ nuovo: polling globale ogni minuto dei valori necessari alla mappa (T/RH + T esterna)
                startGlobalAutoColoring(everyMs = 60_000L)
            } catch (e: Exception) {
                Log.e("TemiMapVM", "❌ Init error", e)
            }
        }
    }

    override fun onCleared() {
        autoRefreshJob?.cancel(); autoRefreshJob = null
        globalPollJob?.cancel(); globalPollJob = null
        super.onCleared()
    }

    /** Carica mappa e occupancy */
    private suspend fun loadMap() = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        val json = context.assets.open("map_image.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)

        val mapImage = root.optJSONObject("Map_Image")
        val mapInfoObj = root.optJSONObject("mapInfo")
        if (mapImage == null || mapInfoObj == null) {
            Log.e("TemiMapVM", "❌ JSON mappa invalido")
            return@withContext
        }

        val width = mapImage.optInt("cols", 0)
        val height = mapImage.optInt("rows", 0)
        val dataArray = mapImage.optJSONArray("data")
        if (width <= 0 || height <= 0 || dataArray == null) {
            Log.e("TemiMapVM", "❌ Dati mappa incompleti")
            return@withContext
        }

        val occ = IntArray(width * height) { i -> dataArray.optInt(i, 0) }
        occData = occ

        val pixels = IntArray(width * height) { i ->
            when (occ[i]) {
                -1   -> Color.TRANSPARENT
                0    -> Color.WHITE
                100  -> Color.BLACK
                else -> Color.LTGRAY
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        _mapBitmap.value = bitmap.asImageBitmap()

        _mapInfo.value = MapInfo(
            width = width,
            height = height,
            originX = mapInfoObj.optDouble("origin_x", 0.0),
            originY = mapInfoObj.optDouble("origin_y", 0.0),
            resolution = mapInfoObj.optDouble("resolution", 0.05)
        )

        Log.i("TemiMapVM", "✅ Map loaded ${width}x$height")
    }

    /** Carica zone */
    private suspend fun loadZones() = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        val text = BufferedReader(InputStreamReader(context.assets.open("zones.json"))).use { it.readText() }
        val root = JSONObject(text)
        val jsonZones = root.optJSONArray("zones") ?: return@withContext

        val parsedZones = mutableListOf<Zone>()
        for (i in 0 until jsonZones.length()) {
            val z = jsonZones.getJSONObject(i)
            val name = z.optString("name", "Zona ${i + 1}")
            val verticesJson = z.optJSONArray("vertices") ?: continue

            val vertices = mutableListOf<Zone.Vertex>()
            for (j in 0 until verticesJson.length()) {
                val v = verticesJson.optJSONArray(j) ?: continue
                if (v.length() >= 2) {
                    vertices.add(Zone.Vertex(v.optDouble(0).toFloat(), v.optDouble(1).toFloat()))
                }
            }
            parsedZones.add(Zone(name, vertices))
        }
        _zones.value = parsedZones
        Log.i("TemiMapVM", "✅ Zones loaded: ${parsedZones.size}")
    }

    /* ====================== API sPMV batch & overlay ====================== */

    fun submitZoneEnv(
        zoneName: String,
        tAir: Double,
        rh: Double,
        tOut: Double,
        met: Double? = null,
        clo: Double? = null
    ) {
        zoneEnvCache[zoneName] = ZoneEnv(tAir, rh, tOut, met, clo)
        val spmv = computeSpmv(tAir, rh, tOut, met, clo)
        spmvCache[zoneName] = spmv
        _lastSpmvUpdate.value = System.currentTimeMillis()

        Log.i(
            "TemiMapVM",
            "🧮 sPMV updated zone='$zoneName' t=${"%.1f".format(tAir)}°C rh=${"%.0f".format(rh)}% tout=${"%.1f".format(tOut)}°C → ${"%.2f".format(spmv)}"
        )

        rebuildGlobalTintOverlay()
    }

    fun refreshAllSpmv() {
        if (zoneEnvCache.isEmpty()) return
        for ((zone, env) in zoneEnvCache) {
            spmvCache[zone] = computeSpmv(env.tAir, env.rh, env.tOut, env.met, env.clo)
        }
        _lastSpmvUpdate.value = System.currentTimeMillis()
        Log.i("TemiMapVM", "♻️ sPMV batch refresh: ${spmvCache.size} zone")
        rebuildGlobalTintOverlay()
    }

    /** Rinfresca periodicamente la sola cache sPMV (senza refetch dei sensori) */
    fun startAutoSpmvRefresh(periodMinutes: Long = 1) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(periodMinutes * 60 * 1000L)
                try {
                    refreshAllSpmv()
                } catch (e: Exception) {
                    Log.e("TemiMapVM", "❌ auto sPMV refresh", e)
                }
            }
        }
    }

    fun stopAutoSpmvRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun getCachedSpmv(zoneName: String): Double? = spmvCache[zoneName]

    /** ✅ Polling globale: ogni minuto legge T/RH per stanza + T esterna (unica) e aggiorna l’overlay */
    fun startGlobalAutoColoring(everyMs: Long = 60_000L) {
        globalPollJob?.cancel()
        globalPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // 1) Temperatura esterna (unica per tutte le stanze)
                    val tOut = runCatching { weatherRepo.fetchAnconaOutdoorTempC() }.getOrNull()

                    // 2) Per ciascuna zona configurata, leggi T/RH e ricalcola sPMV
                    val zs = _zones.value
                    if (tOut != null && zs.isNotEmpty()) {
                        for (zone in zs) {
                            val key = roomSensorIds.keys.firstOrNull {
                                normalizeName(it) == normalizeName(zone.name)
                            } ?: continue
                            val ids: RoomSensorIds = roomSensorIds[key] ?: continue

                            suspend fun getLatestDouble(pid: String?): Double? {
                                if (pid.isNullOrBlank()) return null
                                return runCatching {
                                    val r = wattsense.getProperty(
                                        deviceId = ids.deviceId,
                                        propertyIdOrSlug = pid,
                                        includeHistory = false
                                    )
                                    r.scaledPayload ?: (r.payload as? Number)?.toDouble()
                                }.getOrNull()
                            }

                            val t = getLatestDouble(ids.temperatureId)
                            val rh = getLatestDouble(ids.humidityId)
                            if (t != null && rh != null) {
                                submitZoneEnv(
                                    zoneName = zone.name,
                                    tAir = t,
                                    rh = rh,
                                    tOut = tOut,
                                    met = null,
                                    clo = null
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TemiMapVM", "❌ global auto-coloring: ${e.message}", e)
                } finally {
                    delay(everyMs)
                }
            }
        }
    }

    /* =================== Overlay globale sPMV =================== */

    private fun rebuildGlobalTintOverlay() {
        viewModelScope.launch(Dispatchers.Default) {
            val info = _mapInfo.value
            val occ = occData
            val zs = _zones.value
            if (info == null || occ == null || zs.isEmpty()) {
                _tintOverlay.value = null
                return@launch
            }

            val width = info.width
            val height = info.height
            val overlayPx = IntArray(width * height) { Color.TRANSPARENT }

            for (zone in zs) {
                val spmv = spmvCache[zone.name] ?: continue
                val color = spmvToArgb(spmv, alpha = spmvAlpha)

                val minX = max(0f, zone.vertices.minOf { it.x }).toInt()
                val maxX = min(width - 1f, zone.vertices.maxOf { it.x }).toInt()
                val minY = max(0f, zone.vertices.minOf { it.y }).toInt()
                val maxY = min(height - 1f, zone.vertices.maxOf { it.y }).toInt()

                val polygon = zone.vertices

                for (y in minY..maxY) {
                    val rowOff = y * width
                    for (x in minX..maxX) {
                        if (occ[rowOff + x] == 0 && pointInPolygon(x.toFloat(), y.toFloat(), polygon)) {
                            overlayPx[rowOff + x] = color
                        }
                    }
                }
            }

            val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            overlay.setPixels(overlayPx, 0, width, 0, 0, width, height)
            _tintOverlay.value = overlay.asImageBitmap()

            Log.i("TemiMapVM", "🎨 Overlay globale ricostruito (zone colorate: ${spmvCache.size})")
        }
    }

    /* ========================= helpers ========================= */

    private fun pointInPolygon(x: Float, y: Float, vertices: List<Zone.Vertex>): Boolean {
        var inside = false
        var j = vertices.lastIndex
        for (i in vertices.indices) {
            val xi = vertices[i].x
            val yi = vertices[i].y
            val xj = vertices[j].x
            val yj = vertices[j].y

            val cond = ((yi > y) != (yj > y))
            val denom = (yj - yi)
            val safeDen = if (abs(denom) < 1e-6f) 1e-6f else denom
            val xCross = (xj - xi) * (y - yi) / safeDen + xi

            if (cond && x < xCross) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Soglie richieste:
     *  - |sPMV| > 1        → Rosso
     *  - 0.5 < |sPMV| ≤ 1  → Giallo
     *  - |sPMV| ≤ 0.5      → Verde
     */
    private fun spmvToArgb(pmv: Double, alpha: Int = 0x66): Int {
        val a = alpha.coerceIn(0x00, 0xFF)
        val absVal = kotlin.math.abs(pmv)
        val (r, g, b) = when {
            absVal > 1.0  -> Triple(0xFF, 0x00, 0x00)
            absVal > 0.5  -> Triple(0xFF, 0xFF, 0x00)
            else          -> Triple(0x00, 0xAA, 0x00)
        }
        return Color.argb(a, r, g, b)
    }

    /** ✅ sPMV “Elisa” (usa il tuo modello ufficiale) */
    private fun computeSpmv(
        tAir: Double,
        rh: Double,
        tOut: Double,
        met: Double? = null,
        clo: Double? = null
    ): Double {
        val r = Spmv.compute(tAir, rh, tOut) // pmv = Elisa
        return r.pmv
    }
}
