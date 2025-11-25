package com.example.vitality.coaching

import android.util.Log
import com.example.vitality.data.roomSensorIds
import com.example.vitality.data.Spmv
import com.example.vitality.viewmodel.TemperatureViewModel

class EnvironmentalSensorProvider(
    private val vm: TemperatureViewModel
) {

    /**
     * Mappa un nome normalizzato (es. "nicole") → nome stanza esatto ("Nicole")
     */
    fun mapNormalizedToRoom(normalized: String): String? {
        return roomSensorIds.keys.firstOrNull {
            Normalizer.normalize(it) == normalized
        }
    }

    /**
     * Acquisisce i dati della stanza associata al POI
     */
    suspend fun getComfortForPoi(poiRaw: String): ComfortData? {

        val normalized = Normalizer.normalize(poiRaw)

        val roomKey = mapNormalizedToRoom(normalized)
            ?: return null.also {
                Log.e("ENV", "❌ Nessun sensore associato a $poiRaw")
            }

        val ids = roomSensorIds[roomKey]!!

        val t   = vm.getPropertyValue(ids.deviceId, ids.temperatureId)
        val rh  = vm.getPropertyValue(ids.deviceId, ids.humidityId)
        val co2 = vm.getPropertyValue(ids.deviceId, ids.co2Id)

        val lux  = ids.illuminationId?.let { vm.getPropertyValue(ids.deviceId, it) }
        val voc  = ids.vocId?.let { vm.getPropertyValue(ids.deviceId, it) }
        val iaq  = ids.iaqindexId?.let { vm.getPropertyValue(ids.deviceId, it) }
        val snd  = ids.soundlevelId?.let { vm.getPropertyValue(ids.deviceId, it) }

        if (t == null || rh == null) {
            Log.e("ENV", "❌ Temperature o Humidity assenti per $roomKey")
            return null
        }

        val tOut = vm.externalTemp.value ?: 15.0

        val sp = Spmv.compute(t, rh, tOut)

        val result = ComfortData(
            pmv = sp.pmv,
            pmv2 = sp.pmv2,
            pmv3 = sp.pmv3,
            cloPred = sp.cloPred,
            comfortClass = sp.comfortClass,
            co2 = co2,
            lux = lux,
            voc = voc,
            iaq = iaq,
            sound = snd
        )

        Log.e("ENV", "📡 Dati stanza $roomKey = $result")

        return result
    }
}
