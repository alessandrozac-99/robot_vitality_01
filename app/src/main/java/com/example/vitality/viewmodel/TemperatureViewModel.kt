package com.example.vitality.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitality.data.RetrofitClient
import com.example.vitality.data.WeatherRepository
import com.example.vitality.data.WattsenseApi
import com.example.vitality.data.roomSensorIds
import com.example.vitality.data.Spmv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.round
import java.util.Calendar
import java.util.TimeZone

class TemperatureViewModel(
    private val wattsense: WattsenseApi = RetrofitClient.create(),
    private val weatherRepo: WeatherRepository = WeatherRepository()
) : ViewModel() {

    companion object {
        private const val TAG = "TemperatureVM"
        private val TZ: TimeZone = TimeZone.getTimeZone("Europe/Rome")
    }

    // -------------------- State --------------------
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _temperature = MutableStateFlow<Double?>(null)
    val temperature: StateFlow<Double?> = _temperature

    private val _humidity = MutableStateFlow<Double?>(null)
    val humidity: StateFlow<Double?> = _humidity

    private val _co2 = MutableStateFlow<Double?>(null)
    val co2: StateFlow<Double?> = _co2

    private val _voc = MutableStateFlow<Double?>(null)
    val voc: StateFlow<Double?> = _voc

    private val _iaq = MutableStateFlow<Double?>(null)
    val iaq: StateFlow<Double?> = _iaq

    private val _illumination = MutableStateFlow<Double?>(null)
    val illumination: StateFlow<Double?> = _illumination

    private val _sound = MutableStateFlow<Double?>(null)
    val sound: StateFlow<Double?> = _sound

    private val _airQualityScore = MutableStateFlow<Double?>(null)
    val airQualityScore: StateFlow<Double?> = _airQualityScore

    private val _externalTemp = MutableStateFlow<Double?>(null)
    val externalTemp: StateFlow<Double?> = _externalTemp

    data class SpmvUi(
        val pmv: Double? = null,
        val pmv2: Double? = null,
        val pmv3: Double? = null,
        val cloPred: Double? = null
    )
    private val _spmv = MutableStateFlow(SpmvUi())
    val spmv: StateFlow<SpmvUi> = _spmv

    // -------------------- Polling control --------------------
    private var currentRoomKey: String? = null
    private var pollIndoorJob: Job? = null
    private var pollOutdoorJob: Job? = null

    // anti-stagnazione valori
    private val lastValues = mutableMapOf<String, Double>()
    private val lastUpdateTs = mutableMapOf<String, Long>()

    private fun norm(s: String) = s.trim().lowercase().replace("_", "").replace(" ", "")

    private suspend fun delayUntil(tsTargetMs: Long) {
        val d = tsTargetMs - System.currentTimeMillis()
        if (d > 0) delay(d) else yield()
    }

    /** Prossimo bordo minuto (…:00.000) nella TZ Europe/Rome */
    private fun nextMinuteBoundaryMs(nowMs: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance(TZ).apply { timeInMillis = nowMs }
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MINUTE, 1)
        return c.timeInMillis
    }

    fun clearValues() {
        _temperature.value = null
        _humidity.value = null
        _co2.value = null
        _voc.value = null
        _iaq.value = null
        _illumination.value = null
        _sound.value = null
        _airQualityScore.value = null
        _externalTemp.value = null
        _spmv.value = SpmvUi()
        _error.value = null
        _loading.value = false
    }

    // -------------------------------------------------------------------------
    //  FIX: getLatestDouble ANTIVALORI, ANTISTAGNANTE
    // -------------------------------------------------------------------------
    private suspend fun getLatestDouble(
        deviceId: String,
        pid: String?,
        label: String
    ): Double? {
        if (pid.isNullOrBlank()) return null

        val resp = runCatching {
            wattsense.getProperty(
                deviceId = deviceId,
                propertyIdOrSlug = pid,
                includeHistory = false
            )
        }.onFailure {
            Log.e(TAG, "[$currentRoomKey] $label read failed → ${it.message}")
        }.getOrNull() ?: return null

        val raw = resp.scaledPayload ?: (resp.payload as? Number)?.toDouble()

        // 1) anti-null / anti-broken
        if (raw == null || raw.isNaN() || raw.isInfinite()) return null

        // 2) anti-range (valori plausibili indoor)
        val value = when (label) {
            "T" -> raw.takeIf { it in -10.0..50.0 }
            "RH" -> raw.takeIf { it in 0.0..100.0 }
            "CO2" -> raw.takeIf { it in 300.0..5000.0 }
            "VOC" -> raw.takeIf { it >= 0.0 }
            else -> raw
        } ?: return null

        val now = System.currentTimeMillis()
        val last = lastValues[label]

        // 3) anti-stagnazione: se Wattsense non aggiorna da oltre 2 min → discard
        if (last != null && last == value) {
            val age = now - (lastUpdateTs[label] ?: 0L)
            if (age > 120_000) { // 2 minuti
                Log.w(TAG, "[$currentRoomKey] $label stale for ${age}ms → discard")
                return null
            }
        } else {
            lastValues[label] = value
            lastUpdateTs[label] = now
        }

        return value
    }

    // -------------------------------------------------------------------------
    // CARICAMENTO DATI PER ZONA
    // -------------------------------------------------------------------------
    fun loadDataForZone(roomName: String) {
        val key = roomSensorIds.keys.firstOrNull { norm(it) == norm(roomName) }
        if (key == null) {
            _error.value = "Room '$roomName' non configurata"
            return
        }
        currentRoomKey = key

        _spmv.value = SpmvUi()  // reset sPMV

        pollIndoorJob?.cancel()
        pollOutdoorJob?.cancel()

        viewModelScope.launch(Dispatchers.IO) {
            _loading.emit(true)
            _error.emit(null)

            try {
                val ids = roomSensorIds[key]!!
                val deviceId = ids.deviceId

                // FIRST SNAPSHOT (snappy UI)
                val t  = getLatestDouble(deviceId, ids.temperatureId, "T")
                val rh = getLatestDouble(deviceId, ids.humidityId,   "RH")
                val c  = getLatestDouble(deviceId, ids.co2Id,        "CO2")
                val vv = getLatestDouble(deviceId, ids.vocId,        "VOC")
                val iq = getLatestDouble(deviceId, ids.iaqindexId,   "IAQ")
                val lx = getLatestDouble(deviceId, ids.illuminationId,"LUX")
                val db = getLatestDouble(deviceId, ids.soundlevelId, "DB")
                val aq = getLatestDouble(deviceId, ids.airqualityscoreId, "AQS")

                withContext(Dispatchers.Main) {
                    _temperature.value = t
                    _humidity.value = rh
                    _co2.value = c
                    _voc.value = vv
                    _iaq.value = iq
                    _illumination.value = lx
                    _sound.value = db
                    _airQualityScore.value = aq
                }

                refreshOutdoorOnce()
                recomputeSpmv()

                // ---------------------------------------------------------------------
                // POLL INDOOR OGNI MINUTO — FIX STABILITÀ
                // ---------------------------------------------------------------------
                pollIndoorJob = launch(Dispatchers.IO) {
                    var nextTick = nextMinuteBoundaryMs()

                    while (isActive) {
                        delayUntil(nextTick)

                        try {
                            val tNow  = getLatestDouble(deviceId, ids.temperatureId, "T")
                            val rhNow = getLatestDouble(deviceId, ids.humidityId,   "RH")
                            val cNow  = getLatestDouble(deviceId, ids.co2Id,        "CO2")
                            val vvNow = getLatestDouble(deviceId, ids.vocId,        "VOC")
                            val iqNow = getLatestDouble(deviceId, ids.iaqindexId,   "IAQ")
                            val lxNow = getLatestDouble(deviceId, ids.illuminationId,"LUX")
                            val dbNow = getLatestDouble(deviceId, ids.soundlevelId, "DB")
                            val aqNow = getLatestDouble(deviceId, ids.airqualityscoreId, "AQS")

                            withContext(Dispatchers.Main) {
                                if (tNow  != null && tNow  != _temperature.value) _temperature.value = tNow
                                if (rhNow != null && rhNow != _humidity.value)   _humidity.value   = rhNow
                                if (cNow  != null && cNow  != _co2.value)        _co2.value        = cNow
                                if (vvNow != null && vvNow != _voc.value)        _voc.value        = vvNow
                                if (iqNow != null && iqNow != _iaq.value)        _iaq.value        = iqNow
                                if (lxNow != null && lxNow != _illumination.value) _illumination.value = lxNow
                                if (dbNow != null && dbNow != _sound.value)      _sound.value      = dbNow
                                if (aqNow != null && aqNow != _airQualityScore.value) _airQualityScore.value = aqNow
                            }

                            // watchdog 3 minuti
                            val ageT = System.currentTimeMillis() - (lastUpdateTs["T"] ?: 0L)
                            if (ageT > 180_000) {
                                withContext(Dispatchers.Main) { _temperature.value = null }
                            }

                            recomputeSpmv()

                        } catch (e: Exception) {
                            Log.e(TAG, "[$currentRoomKey] pollIndoor error → ${e.message}")
                        } finally {
                            nextTick = nextMinuteBoundaryMs(nextTick)
                        }
                    }
                }

                // ---------------------------------------------------------------------
                // POLL OUTDOOR
                // ---------------------------------------------------------------------
                pollOutdoorJob = launch(Dispatchers.IO) {
                    var nextTick = nextMinuteBoundaryMs()
                    while (isActive) {
                        delayUntil(nextTick)
                        try {
                            refreshOutdoorOnce()
                            recomputeSpmv()
                        } finally {
                            nextTick = nextMinuteBoundaryMs(nextTick)
                        }
                    }
                }

                _loading.emit(false)

            } catch (e: Exception) {
                Log.e(TAG, "[$currentRoomKey] loadDataForZone:", e)
                _error.emit(e.localizedMessage ?: "Errore caricamento")
                _loading.emit(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // OUTDOOR TEMP
    // -------------------------------------------------------------------------
    private suspend fun refreshOutdoorOnce() {
        val out = runCatching { weatherRepo.fetchAnconaOutdoorTempC() }.getOrNull()
        withContext(Dispatchers.Main) { _externalTemp.value = out }
    }

    // -------------------------------------------------------------------------
    // COMPUTE SPMV (robusto)
    // -------------------------------------------------------------------------
    fun recomputeSpmv() {
        val t = _temperature.value
        val rh = _humidity.value
        val tOut = _externalTemp.value

        if (t == null || rh == null || tOut == null) {
            _spmv.value = SpmvUi()
            return
        }

        val r = Spmv.compute(t, rh, tOut)
        val clo = (round(r.cloPred * 10_000.0) / 10_000.0)

        _spmv.update {
            SpmvUi(
                pmv = r.pmv,
                pmv2 = r.pmv2,
                pmv3 = r.pmv3,
                cloPred = clo
            )
        }
    }

    override fun onCleared() {
        pollIndoorJob?.cancel()
        pollOutdoorJob?.cancel()
        super.onCleared()
    }
}
