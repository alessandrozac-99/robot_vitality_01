package com.example.vitality.coaching

import android.util.Log
import com.example.vitality.data.roomSensorIds
import com.example.vitality.data.Spmv
import com.example.vitality.viewmodel.TemperatureViewModel
import kotlinx.coroutines.delay

class EnvironmentalSensorProvider(
    private val vm: TemperatureViewModel
) {

    /**
     * Mappa un nome normalizzato (es. "nicole") → nome stanza esatto ("Nicole")
     */
    fun mapNormalizedToRoom(normalized: String): String? {

        Log.e("ENV-MAP", "🔎 mapNormalizedToRoom() → normalizzato = '$normalized'")
        Log.e("ENV-MAP", "🔎 Chiavi roomSensorIds = ${roomSensorIds.keys}")

        val result = roomSensorIds.keys.firstOrNull {
            val normKey = Normalizer.normalize(it)
            Log.e("ENV-MAP", "   • confronto: normalize('$it') = '$normKey'")
            normKey == normalized
        }

        Log.e("ENV-MAP", "➡ mapping FINAL → '$normalized' → '$result'")
        return result
    }


    /**
     * 🔥 Acquisizione real-time dei sensori + generazione ComfortData
     */
    suspend fun getComfortForPoi(poiRaw: String): ComfortData? {

        Log.e("ENV", "\n======================= GET COMFORT =======================")
        Log.e("ENV", "🏷 Richiesta Comfort per POI raw = '$poiRaw'")

        val normalized = Normalizer.normalize(poiRaw)
        Log.e("ENV", "🔤 Normalizzato → '$normalized'")

        val roomKey = mapNormalizedToRoom(normalized)

        if (roomKey == null) {
            Log.e("ENV", "❌ Nessuna stanza trovata per '$poiRaw' (normalized='$normalized')")
            return null
        }

        Log.e("ENV", "🏠 Stanza associata → '$roomKey'")

        val ids = roomSensorIds[roomKey]!!
        Log.e("ENV", "🆔 IDs sensori → $ids")

        // --------------------------------------------------------------------
        // 1) Refresh dei dati real-time
        // --------------------------------------------------------------------
        try {
            Log.e("ENV", "🔄 Chiamo loadDataForZone('$roomKey')")
            vm.loadDataForZone(roomKey)
        } catch (e: Exception) {
            Log.e("ENV", "❌ ERRORE loadDataForZone('$roomKey'): ${e.message}")
        }

        Log.e("ENV", "⏳ delay 900ms per aggiornamento StateFlow...")
        delay(900)

        // --------------------------------------------------------------------
        // 2) Lettura di OGNI valore sensore
        // --------------------------------------------------------------------
        val t   = vm.getPropertyValue(ids.deviceId, ids.temperatureId)
        val rh  = vm.getPropertyValue(ids.deviceId, ids.humidityId)
        val co2 = vm.getPropertyValue(ids.deviceId, ids.co2Id)

        val lux  = ids.illuminationId?.let { vm.getPropertyValue(ids.deviceId, it) }
        val voc  = ids.vocId?.let { vm.getPropertyValue(ids.deviceId, it) }
        val iaq  = ids.iaqindexId?.let { vm.getPropertyValue(ids.deviceId, it) }
        val snd  = ids.soundlevelId?.let { vm.getPropertyValue(ids.deviceId, it) }

        Log.e("ENV", "📥 Valori letti:")
        Log.e("ENV", "   🌡 Temperature = $t")
        Log.e("ENV", "   💧 Humidity    = $rh")
        Log.e("ENV", "   🫁 CO₂         = $co2")
        Log.e("ENV", "   💡 Lux         = $lux")
        Log.e("ENV", "   🧪 VOC         = $voc")
        Log.e("ENV", "   📊 IAQ         = $iaq")
        Log.e("ENV", "   🔊 Sound       = $snd")

        if (t == null || rh == null) {
            Log.e("ENV", "❌ Dati termici mancanti per '$roomKey' → null (t=$t, rh=$rh)")
            return null
        }

        // Temperatura esterna
        val tOut = vm.externalTemp.value ?: 15.0
        Log.e("ENV", "🌍 Temperatura esterna (tOut) = $tOut")

        // --------------------------------------------------------------------
        // 3) Calcolo PMV / comfort
        // --------------------------------------------------------------------
        Log.e("ENV", "🧠 Calcolo SPMV → compute(t=$t, rh=$rh, tOut=$tOut)")

        val sp = Spmv.compute(t, rh, tOut)

        Log.e("ENV", "🧮 Risultato SPMV = pmv=${sp.pmv}, pmv2=${sp.pmv2}, pmv3=${sp.pmv3}, clo=${sp.cloPred}, class=${sp.comfortClass}")

        // --------------------------------------------------------------------
        // 4) Creazione ComfortData finale
        // --------------------------------------------------------------------
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

        Log.e("ENV", "📡 ComfortData finale per '$roomKey' → $result")
        Log.e("ENV", "===========================================================\n")

        return result
    }
}
