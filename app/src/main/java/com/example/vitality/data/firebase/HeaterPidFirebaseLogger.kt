package com.example.vitality.data.firebase

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class HeaterPidFirebaseLogger {

    private val db = FirebaseDatabase.getInstance()
    private val TAG = "HeaterPidLogger"

    private val tz = TimeZone.getTimeZone("Europe/Rome")

    private val fmtDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
    private val fmtHour = SimpleDateFormat("HH", Locale.US).apply { timeZone = tz }
    private val fmtSecKey = SimpleDateFormat("mmss", Locale.US).apply { timeZone = tz }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun dayBucket(): String = fmtDay.format(Date(nowMs()))
    private fun hourBucket(): String = fmtHour.format(Date(nowMs()))
    private fun secKey(): String = fmtSecKey.format(Date(nowMs()))

    /**
     * Log coerente con il nuovo PID ON/OFF (isteresi + anti short-cycle).
     * Solo campi reali: PMV, stato, occupancy, T, potenza.
     */
    fun logPidStep(
        pmv: Double,
        heaterState: Boolean,
        occupancy: Boolean,
        tIndoor: Double?,
        plugPower: Double?,
        dtMs: Long
    ) {
        val ts = nowMs()
        val day = dayBucket()
        val hour = hourBucket()
        val key = secKey()

        val path = "heater_pid/nicole/logs/$day/$hour/$key"

        val payload = mapOf(
            "type" to "pid_step",
            "timestamp" to ts,
            "pmv" to pmv,
            "t_indoor" to (tIndoor ?: 0.0),
            "plug_power" to (plugPower ?: 0.0),
            "occupancy" to occupancy,
            "heater_on" to heaterState,
            "dt_ms" to dtMs
        )

        db.getReference(path).setValue(payload)
        Log.i(TAG, "PID LOG → $path = $payload")
    }

    fun logHeaterState(isOn: Boolean) {
        val ts = nowMs()

        val path = "heater_pid/nicole/state"
        val payload = mapOf(
            "type" to "heater_state",
            "heater_on" to isOn,
            "timestamp" to ts
        )

        db.getReference(path).setValue(payload)
        Log.i(TAG, "STATE LOG → $payload")
    }

    fun logSafetyEvent(event: String) {
        val ts = nowMs()
        val day = dayBucket()
        val hour = hourBucket()
        val key = secKey()

        val path = "heater_pid/nicole/safety/$day/$hour/$key"

        val payload = mapOf(
            "type" to "safety_event",
            "event" to event,
            "timestamp" to ts
        )

        db.getReference(path).setValue(payload)
        Log.e(TAG, "SAFETY → $path = $payload")
    }
}
