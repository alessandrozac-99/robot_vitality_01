package com.example.vitality.ui.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitality.data.*
import com.example.vitality.model.Zone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TemiMapViewModel(application: Application) : AndroidViewModel(application) {

    private val wattsense: WattsenseApi = RetrofitClient.create()
    private val weatherRepo = WeatherRepository()

    // MUTEX per impedire ricostruzioni simultanee delle overlay
    private val overlayMutex = Mutex()

    private var lastHeatmapBuild = 0L

    // =======================================================================
    // MAPPA BASE
    // =======================================================================
    private val _mapBitmap = MutableStateFlow<ImageBitmap?>(null)
    val mapBitmap: StateFlow<ImageBitmap?> = _mapBitmap

    private val _mapInfo = MutableStateFlow<MapInfo?>(null)
    val mapInfo: StateFlow<MapInfo?> = _mapInfo

    private var occData: IntArray? = null

    // =======================================================================
    // ZONE
    // =======================================================================
    private val _zones = MutableStateFlow<List<Zone>>(emptyList())
    val zones: StateFlow<List<Zone>> = _zones

    // =======================================================================
    // POI
    // =======================================================================
    data class Poi(val name: String, val x: Float, val y: Float)

    private val _poi = MutableStateFlow<List<Poi>>(emptyList())
    val poi: StateFlow<List<Poi>> = _poi

    // =======================================================================
    // SPMV + CACHE
    // =======================================================================
    private data class ZoneEnv(
        val tAir: Double,
        val rh: Double,
        val tOut: Double,
        val met: Double? = null,
        val clo: Double? = null
    )

    private val zoneEnvCache = mutableMapOf<String, ZoneEnv>()
    private val spmvCache = mutableMapOf<String, Double>()

    private val _lastSpmvUpdate = MutableStateFlow<Long?>(null)
    val lastSpmvUpdate: StateFlow<Long?> = _lastSpmvUpdate

    // =======================================================================
    // OVERLAY STATICO (TINT) + HEATMAP CONTINUA
    // =======================================================================
    private val spmvAlpha = 0x66

    private val _tintOverlay = MutableStateFlow<ImageBitmap?>(null)
    val tintOverlay: StateFlow<ImageBitmap?> = _tintOverlay

    private val _heatmapOverlay = MutableStateFlow<ImageBitmap?>(null)
    val heatmapOverlay: StateFlow<ImageBitmap?> = _heatmapOverlay

    // =======================================================================
    // JOB DI POLLING (UNO SOLO)
    // =======================================================================
    private var globalPollJob: Job? = null

    // =======================================================================
    // INIT: CARICAMENTO + START POLLING
    // =======================================================================
    init {
        viewModelScope.launch {
            loadMap()
            loadZones()
            loadPoi()
            startGlobalAutoColoring(60_000L) // ogni 60 secondi
        }
    }

    override fun onCleared() {
        globalPollJob?.cancel()
        super.onCleared()
    }

    // ================================================================================
    // MAPPA
    // ================================================================================
    private suspend fun loadMap() = withContext(Dispatchers.IO) {

        val context = getApplication<Application>().applicationContext
        val json = context.assets.open("map_image.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)

        val mapImage = root.optJSONObject("Map_Image")
        val mapInfoObj = root.optJSONObject("mapInfo")

        if (mapImage == null || mapInfoObj == null) return@withContext

        val width = mapImage.optInt("cols", 0)
        val height = mapImage.optInt("rows", 0)
        val dataArray = mapImage.optJSONArray("data") ?: return@withContext

        val occ = IntArray(width * height) { i -> dataArray.optInt(i, 0) }
        occData = occ

        val pixels = IntArray(width * height) { i ->
            when (occ[i]) {
                -1 -> Color.TRANSPARENT
                0 -> Color.WHITE
                100 -> Color.BLACK
                else -> Color.LTGRAY
            }
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        _mapBitmap.value = bmp.asImageBitmap()

        _mapInfo.value = MapInfo(
            width = width,
            height = height,
            originX = mapInfoObj.optDouble("origin_x", 0.0),
            originY = mapInfoObj.optDouble("origin_y", 0.0),
            resolution = mapInfoObj.optDouble("resolution", 0.05)
        )
    }

    // ================================================================================
    // ZONE
    // ================================================================================
    private suspend fun loadZones() = withContext(Dispatchers.IO) {

        val context = getApplication<Application>().applicationContext
        val json = context.assets.open("zones.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.optJSONArray("zones") ?: return@withContext

        val list = mutableListOf<Zone>()
        for (i in 0 until arr.length()) {
            val z = arr.getJSONObject(i)
            val name = z.getString("name")
            val verticesJson = z.getJSONArray("vertices")

            val vlist = List(verticesJson.length()) { j ->
                val v = verticesJson.getJSONArray(j)
                Zone.Vertex(v.getDouble(0).toFloat(), v.getDouble(1).toFloat())
            }

            list.add(Zone(name, vlist))
        }

        _zones.value = list
    }

    // ================================================================================
    // POI
    // ================================================================================
    private suspend fun loadPoi() = withContext(Dispatchers.IO) {

        val context = getApplication<Application>().applicationContext
        val json = context.assets.open("poi.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.optJSONArray("poi") ?: return@withContext

        val list = mutableListOf<Poi>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Poi(
                    name = o.getString("name"),
                    x = o.getDouble("pixelX").toFloat(),
                    y = o.getDouble("pixelY").toFloat()
                )
            )
        }

        _poi.value = list
    }

    // ================================================================================
    // SPMV: INPUT AMBIENTALE
    // ================================================================================
    fun submitZoneEnv(zone: String, tAir: Double, rh: Double, tOut: Double,
                      met: Double? = null, clo: Double? = null) {

        zoneEnvCache[zone] = ZoneEnv(tAir, rh, tOut, met, clo)
        spmvCache[zone] = computeSpmv(tAir, rh, tOut, met, clo)
        _lastSpmvUpdate.value = System.currentTimeMillis()

        viewModelScope.launch {
            overlayMutex.withLock {
                rebuildGlobalTintOverlay()
                debounceHeatmapBuild()
            }
        }
    }

    private suspend fun debounceHeatmapBuild() {
        val now = System.currentTimeMillis()
        if (now - lastHeatmapBuild < 4000) return
        lastHeatmapBuild = now
        buildHeatmapOverlay()
    }

    // ================================================================================
    // POLLING GLOBALE OGNI 60s (UNICO)
    // ================================================================================
    fun startGlobalAutoColoring(everyMs: Long) {

        globalPollJob?.cancel()
        globalPollJob = viewModelScope.launch(Dispatchers.IO) {

            while (isActive) {
                try {

                    val tOut = runCatching { weatherRepo.fetchAnconaOutdoorTempC() }.getOrNull()
                    val zs = _zones.value

                    if (tOut != null) {
                        for (zone in zs) {

                            val match = roomSensorIds.keys.firstOrNull {
                                normalizeName(it) == normalizeName(zone.name)
                            } ?: continue

                            val ids = roomSensorIds[match] ?: continue

                            suspend fun read(pid: String?): Double? {
                                if (pid.isNullOrBlank()) return null
                                val r = wattsense.getProperty(ids.deviceId, pid, false)
                                return r.scaledPayload ?: (r.payload as? Number)?.toDouble()
                            }

                            val t = read(ids.temperatureId)
                            val rh = read(ids.humidityId)

                            if (t != null && rh != null) {
                                submitZoneEnv(zone.name, t, rh, tOut)
                            }
                        }
                    }

                } catch (_: Exception) { }

                delay(everyMs)
            }
        }
    }

    // ================================================================================
    // OVERLAY DI ZONA (COLORI STATICI)
    // ================================================================================
    private suspend fun rebuildGlobalTintOverlay() = withContext(Dispatchers.Default) {

        val info = _mapInfo.value ?: return@withContext
        val occ = occData ?: return@withContext
        val zones = _zones.value

        val width = info.width
        val height = info.height

        val overlay = IntArray(width * height) { Color.TRANSPARENT }

        for (zone in zones) {

            val spmv = spmvCache[zone.name] ?: continue
            val argb = spmvToArgb(spmv, spmvAlpha)

            val minX = max(0f, zone.vertices.minOf { it.x }).toInt()
            val maxX = min(width - 1f, zone.vertices.maxOf { it.x }).toInt()
            val minY = max(0f, zone.vertices.minOf { it.y }).toInt()
            val maxY = min(height - 1f, zone.vertices.maxOf { it.y }).toInt()

            for (y in minY..maxY) {
                val row = y * width
                for (x in minX..maxX) {
                    if (occ[row + x] == 0 &&
                        pointInPolygon(x.toFloat(), y.toFloat(), zone.vertices)) {
                        overlay[row + x] = argb
                    }
                }
            }
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(overlay, 0, width, 0, 0, width, height)
        _tintOverlay.value = bmp.asImageBitmap()
    }

    // ================================================================================
    // HEATMAP CONTINUA
    // ================================================================================
    suspend fun buildHeatmapOverlay() = withContext(Dispatchers.Default) {

        val info = _mapInfo.value ?: return@withContext
        val occ = occData ?: return@withContext
        val zonesList = _zones.value

        val width = info.width
        val height = info.height

        val pixels = IntArray(width * height) { Color.TRANSPARENT }

        for (zone in zonesList) {

            val spmv = spmvCache[zone.name] ?: continue
            val color = spmvToColor(spmv)

            val minX = zone.vertices.minOf { it.x }.toInt().coerceIn(0, width - 1)
            val maxX = zone.vertices.maxOf { it.x }.toInt().coerceIn(0, width - 1)
            val minY = zone.vertices.minOf { it.y }.toInt().coerceIn(0, height - 1)
            val maxY = zone.vertices.maxOf { it.y }.toInt().coerceIn(0, height - 1)

            for (y in minY..maxY) {
                val row = y * width
                for (x in minX..maxX) {
                    if (occ[row + x] == 0 &&
                        pointInPolygon(x.toFloat(), y.toFloat(), zone.vertices)) {
                        pixels[row + x] = color
                    }
                }
            }
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        _heatmapOverlay.value = bmp.asImageBitmap()
    }

    // ================================================================================
    // SPMV HELPER
    // ================================================================================
    private fun computeSpmv(tAir: Double, rh: Double, tOut: Double,
                            met: Double?, clo: Double?): Double {
        return Spmv.compute(tAir, rh, tOut).pmv
    }

    private fun spmvToArgb(pmv: Double, alpha: Int): Int {
        val absV = abs(pmv)
        val (r, g, b) = when {
            absV > 1.0 -> Triple(255, 0, 0)
            absV > 0.5 -> Triple(255, 255, 0)
            else -> Triple(0, 170, 0)
        }
        return Color.argb(alpha, r, g, b)
    }

    private fun spmvToColor(s: Double): Int {

        val clamped = s.coerceIn(-0.5, 0.5)
        val t = ((clamped + 0.5) / 1.0).toFloat()

        val r: Int
        val g: Int
        val b: Int

        if (t < 0.5f) {
            val k = t / 0.5f
            r = 0
            g = (255 * k).toInt()
            b = (255 * (1 - k)).toInt()
        } else {
            val k = (t - 0.5f) / 0.5f
            r = (255 * k).toInt()
            g = (255 * (1 - k)).toInt()
            b = 0
        }

        return Color.argb(128, r, g, b)
    }

    // ================================================================================
    // GEOMETRIA
    // ================================================================================
    private fun pointInPolygon(x: Float, y: Float, vertices: List<Zone.Vertex>): Boolean {
        var inside = false
        var j = vertices.lastIndex

        for (i in vertices.indices) {
            val xi = vertices[i].x
            val yi = vertices[i].y
            val xj = vertices[j].x
            val yj = vertices[j].y

            val intersect = ((yi > y) != (yj > y))
            val denom = (yj - yi)
            val safe = if (abs(denom) < 1e-6f) 1e-6f else denom
            val xCross = (xj - xi) * (y - yi) / safe + xi

            if (intersect && x < xCross) inside = !inside
            j = i
        }
        return inside
    }

    private fun normalizeName(name: String): String =
        name.trim().lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
}
