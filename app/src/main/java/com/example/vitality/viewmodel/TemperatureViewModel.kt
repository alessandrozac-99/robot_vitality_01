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

    fun loadDataForZone(roomName: String) {
        val key = roomSensorIds.keys.firstOrNull { norm(it) == norm(roomName) }
        if (key == null) {
            _error.value = "Room '$roomName' non configurata"
            return
        }
        currentRoomKey = key

        // reset sPMV/CLO per evitare valori residui
        _spmv.value = SpmvUi()

        // stop polling precedenti
        pollIndoorJob?.cancel()
        pollOutdoorJob?.cancel()

        viewModelScope.launch(Dispatchers.IO) {
            _loading.emit(true)
            _error.emit(null)
            try {
                val ids = roomSensorIds[key]!!
                val deviceId = ids.deviceId

                suspend fun getLatestDouble(pid: String?, label: String): Double? {
                    if (pid.isNullOrBlank()) {
                        Log.w(TAG, "[$currentRoomKey] $label: propertyId NULL/blank")
                        return null
                    }
                    val resp = runCatching {
                        wattsense.getProperty(
                            deviceId = deviceId,
                            propertyIdOrSlug = pid,
                            includeHistory = false
                        )
                    }.onFailure {
                        Log.e(TAG, "[$currentRoomKey] getProperty($label) dev=$deviceId pid=$pid ❌ ${it.message}")
                    }.getOrNull()

                    val value = resp?.scaledPayload ?: (resp?.payload as? Number)?.toDouble()
                    Log.i(TAG, "[$currentRoomKey] READ $label dev=$deviceId pid=$pid → $value")
                    return value
                }

                // --- Primo snapshot immediato (UI snappy) ---
                val t  = getLatestDouble(ids.temperatureId, "T")
                val rh = getLatestDouble(ids.humidityId,   "RH")
                val c  = getLatestDouble(ids.co2Id,        "CO2")
                val vv = getLatestDouble(ids.vocId,        "VOC")
                val iq = getLatestDouble(ids.iaqindexId,   "IAQ")
                val lx = getLatestDouble(ids.illuminationId,"LUX")
                val db = getLatestDouble(ids.soundlevelId, "DB")
                val aq = getLatestDouble(ids.airqualityscoreId, "AQS")

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

                // T esterna immediata + prima recompute sPMV
                refreshOutdoorOnce()
                recomputeSpmv()

                // --- Poll indoor ALLINEATO al minuto ---
                pollIndoorJob = launch(Dispatchers.IO) {
                    var nextTick = nextMinuteBoundaryMs()
                    while (isActive) {
                        delayUntil(nextTick)
                        val wakeLag = System.currentTimeMillis() - nextTick
                        if (kotlin.math.abs(wakeLag) > 1_000) {
                            Log.w(TAG, "⏱️ drift indoor wakeLag=${wakeLag}ms → riallineo")
                        }
                        try {
                            val tNow  = getLatestDouble(ids.temperatureId, "T")
                            val rhNow = getLatestDouble(ids.humidityId,   "RH")
                            val cNow  = getLatestDouble(ids.co2Id,        "CO2")
                            val vvNow = getLatestDouble(ids.vocId,        "VOC")
                            val iqNow = getLatestDouble(ids.iaqindexId,   "IAQ")
                            val lxNow = getLatestDouble(ids.illuminationId,"LUX")
                            val dbNow = getLatestDouble(ids.soundlevelId, "DB")
                            val aqNow = getLatestDouble(ids.airqualityscoreId, "AQS")

                            withContext(Dispatchers.Main) {
                                if (tNow  != null) _temperature.value = tNow
                                if (rhNow != null) _humidity.value   = rhNow
                                if (cNow  != null) _co2.value        = cNow
                                if (vvNow != null) _voc.value        = vvNow
                                if (iqNow != null) _iaq.value        = iqNow
                                if (lxNow != null) _illumination.value = lxNow
                                if (dbNow != null) _sound.value      = dbNow
                                if (aqNow != null) _airQualityScore.value = aqNow
                            }
                            recomputeSpmv()
                        } catch (e: Exception) {
                            Log.e(TAG, "[$currentRoomKey] pollIndoor: ${e.message}")
                        } finally {
                            nextTick = nextMinuteBoundaryMs(nextTick)
                        }
                    }
                }

                // --- Poll esterno ALLINEATO al minuto ---
                pollOutdoorJob = launch(Dispatchers.IO) {
                    var nextTick = nextMinuteBoundaryMs()
                    while (isActive) {
                        delayUntil(nextTick)
                        val wakeLag = System.currentTimeMillis() - nextTick
                        if (kotlin.math.abs(wakeLag) > 1_000) {
                            Log.w(TAG, "⏱️ drift outdoor wakeLag=${wakeLag}ms → riallineo")
                        }
                        try {
                            refreshOutdoorOnce()
                            recomputeSpmv()
                        } catch (_: Exception) {
                        } finally {
                            nextTick = nextMinuteBoundaryMs(nextTick)
                        }
                    }
                }

                _loading.emit(false)
            } catch (e: Exception) {
                Log.e(TAG, "[$currentRoomKey] loadDataForZone: ${e.message}", e)
                _error.emit(e.localizedMessage ?: "Errore caricamento")
                _loading.emit(false)
            }
        }
    }

    private suspend fun refreshOutdoorOnce() {
        val out = weatherRepo.fetchAnconaOutdoorTempC()
        withContext(Dispatchers.Main) { _externalTemp.value = out }
    }

    fun recomputeSpmv() {
        val t = _temperature.value
        val rh = _humidity.value
        val tOut = _externalTemp.value
        if (t == null || rh == null || tOut == null) return

        val r = Spmv.compute(t, rh, tOut)
        val clo = (round(r.cloPred * 10_000.0) / 10_000.0)

        Log.i(
            TAG,
            "[$currentRoomKey] CLO=${"%.4f".format(clo)}  " +
                    "T=${"%.2f".format(t)}°C RH=${"%.1f".format(rh)}% T_out=${"%.2f".format(tOut)}°C"
        )

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
        super.onCleared()
        pollIndoorJob?.cancel()
        pollOutdoorJob?.cancel()
    }
}
