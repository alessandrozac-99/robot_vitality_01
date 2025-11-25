package com.example.vitality.data.firebase

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

/**
 * Logger ottimizzato per il PID della stufetta in Nicole.
 * Ridotto del 70% rispetto alla versione precedente.
 */
class HeaterPidFirebaseLogger {

    private val db = FirebaseDatabase.getInstance()
    private val TAG = "HeaterPidLogger"

    private val tz = TimeZone.getTimeZone("Europe/Rome")
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = tz
    }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
        timeZone = tz
    }

    private fun todayPath(): String =
        dateFmt.format(Date(System.currentTimeMillis()))

    private fun nowTimestamp(): Long =
        System.currentTimeMillis()

    private fun nowTimeKey(): String =
        timeFmt.format(Date(System.currentTimeMillis()))

    // =====================================================================================
    // LOG PID — versione ridotta e ottimizzata
    // =====================================================================================
    fun logPidSample(
        pmv: Double?,
        error: Double,
        pidOutput: Double,
        heaterState: Boolean,
        occupancy: Boolean,
        tIndoor: Double?,
        plugPower: Double?,
        pidIntegral: Double,
        pidDerivative: Double
    ) {
        val day = todayPath()
        val timeKey = nowTimeKey()
        val path = "heater_pid/nicole/logs/$day/$timeKey"

        val payload = mutableMapOf<String, Any>(
            "timestamp" to nowTimestamp(),

            // stato ambiente
            "pmv" to (pmv ?: 0.0),
            "t_indoor" to (tIndoor ?: 0.0),
            "plug_power" to (plugPower ?: 0.0),
            "occupancy" to occupancy,

            // PID
            "error" to error,
            "pid_output" to pidOutput,
            "i_term" to pidIntegral,
            "d_term" to pidDerivative,

            // output attuatore
            "heater_on" to heaterState
        )

        db.getReference(path).setValue(payload)
        Log.i(TAG, "PID LOG → $path = $payload")
    }

    // =====================================================================================
    // WRAPPER COMPATIBILE — usato dal servizio NicoleHeaterControlService
    // =====================================================================================
    fun logPidStep(
        pmv: Double?,
        error: Double,
        pidOutput: Double,
        heaterState: Boolean,
        occupancy: Boolean,
        tIndoor: Double?,
        plugPower: Double?,
        integral: Double,
        derivative: Double,
        dtMs: Long   // ignorato ma mantenuto per compatibilità
    ) {
        logPidSample(
            pmv = pmv,
            error = error,
            pidOutput = pidOutput,
            heaterState = heaterState,
            occupancy = occupancy,
            tIndoor = tIndoor,
            plugPower = plugPower,
            pidIntegral = integral,
            pidDerivative = derivative
        )
    }

    // =====================================================================================
    // LOG STATO STUFETTA
    // =====================================================================================
    fun logHeaterState(isOn: Boolean) {
        val ref = db.getReference("heater_pid/nicole/state")
        val payload = mapOf(
            "heater_on" to isOn,
            "timestamp" to nowTimestamp()
        )
        ref.setValue(payload)
        Log.i(TAG, "STATE LOG → $payload")
    }

    // =====================================================================================
    // LOG EVENTI DI SICUREZZA (separati e indepedenti)
    // =====================================================================================
    fun logSafetyEvent(event: String) {
        val day = todayPath()
        val timeKey = nowTimeKey()
        val path = "heater_pid/nicole/safety/$day/$timeKey"

        val payload = mapOf(
            "event" to event,
            "timestamp" to nowTimestamp()
        )

        db.getReference(path).setValue(payload)
        Log.e(TAG, "SAFETY → $path = $payload")
    }
}
