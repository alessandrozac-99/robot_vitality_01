package com.example.vitality.pid

import android.util.Log
import com.example.vitality.data.SmartPlugRepository
import com.example.vitality.data.SmartPlugStatus
import com.example.vitality.data.firebase.HeaterPidFirebaseLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Repository dedicato per la gestione della STUFETTA nella stanza “Nicole”.
 *
 * - IP locale → dal tuo SmartPlugRepository
 * - Comandi ON/OFF via RPC HTTP locale
 * - Recupero stato
 * - Log automatico verso Firebase
 */
class HeaterNicoleRepository(
    private val smartRepo: SmartPlugRepository,
    private val logger: HeaterPidFirebaseLogger
) {

    companion object {
        private const val TAG = "HeaterNicoleRepo"
        private const val PLUG_NAME = "PRESA_STUFETTA_NICOLE"
    }

    /**
     * Recupera stato attuale della stufetta.
     * Usa lo SmartPlugRepository locale (HTTP /rpc/Switch.GetStatus)
     */
    suspend fun getStatus(): SmartPlugStatus? {
        val plugs = smartRepo.fetchPlugsForRoom("Nicole")
        val plug = plugs.firstOrNull { it.name == PLUG_NAME }
        if (plug == null) {
            Log.e(TAG, "❌ Plug non trovata: $PLUG_NAME")
            return null
        }

        if (!plug.online) {
            Log.e(TAG, "⚠ $PLUG_NAME offline")
        }

        return plug
    }

    /**
     * Accende la stufetta con comando Shelly RPC.
     */
    suspend fun turnOn(): Boolean = withContext(Dispatchers.IO) {
        val plug = getStatus()
        if (plug == null || plug.ip == null) return@withContext false

        val url = "http://${plug.ip}/rpc/Switch.Set?id=0&on=true"
        val client = okhttp3.OkHttpClient()

        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (ok) {
                    Log.e(TAG, "🔥 ACCESA stufetta su $PLUG_NAME")
                    logger.logHeaterState(true)
                } else {
                    Log.e(TAG, "❌ Errore ON → HTTP ${resp.code}")
                }
                return@withContext ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception ON: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Spegne la stufetta.
     */
    suspend fun turnOff(): Boolean = withContext(Dispatchers.IO) {
        val plug = getStatus()
        if (plug == null || plug.ip == null) return@withContext false

        val url = "http://${plug.ip}/rpc/Switch.Set?id=0&on=false"
        val client = okhttp3.OkHttpClient()

        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (ok) {
                    Log.e(TAG, "🧊 SPENTA stufetta su $PLUG_NAME")
                    logger.logHeaterState(false)
                } else {
                    Log.e(TAG, "❌ Errore OFF → HTTP ${resp.code}")
                }
                return@withContext ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception OFF: ${e.message}")
            return@withContext false
        }
    }


    // ============================================================================
    // CONTROLLO SICUREZZA (richiamato dal PID)
    // ============================================================================
    suspend fun isSafe(plug: SmartPlugStatus?): Boolean {
        if (plug == null || !plug.online) return false

        if (plug.temperature >= 75.0) { // limite Shelly
            logger.logSafetyEvent("Overheating: ${plug.temperature}")
            return false
        }

        if (plug.apower >= 1900.0) {
            logger.logSafetyEvent("OverPower: ${plug.apower}W")
            return false
        }

        // Se Shelly segnala errori interni
        if (plug.errors != null && plug.errors!!.isNotEmpty()) {
            logger.logSafetyEvent("Error flags: ${plug.errors}")
            return false
        }

        return true
    }
}
