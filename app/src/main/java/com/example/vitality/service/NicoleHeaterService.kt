package com.example.vitality.service

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.vitality.data.firebase.HeaterPidFirebaseLogger
import com.example.vitality.pid.HeaterNicoleRepository
import com.example.vitality.pid.HeaterPIDController
import com.example.vitality.data.SmartPlugRepository
import com.example.vitality.viewmodel.TemperatureViewModel
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.TimeZone

class NicoleHeaterControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // COMPONENTS
    private val temperatureVM = TemperatureViewModel()
    private val firebaseLogger = HeaterPidFirebaseLogger()
    private val smartRepo = SmartPlugRepository()
    private val heaterRepo = HeaterNicoleRepository(smartRepo, firebaseLogger)

    private val pid = HeaterPIDController(
        turnOnThreshold = -0.20,
        turnOffThreshold = +0.15,
        minOffDurationMs = 180_000,
        minOnDurationMs = 120_000
    )

    private val intervalMs = 180_000L // 3 minuti
    private val TAG = "NicoleHeaterSvc"
    private val TZ = TimeZone.getTimeZone("Europe/Rome")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundInternal()
        startLoop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // FOREGROUND
    private fun startForegroundInternal() {
        val chId = "nicole_heater_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(chId, "Heater PID Nicole", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, chId)
            .setContentTitle("PID Stufetta Nicole")
            .setContentText("Controllo comfort termico")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()

        startForeground(3001, notif)
    }

    // MAIN LOOP
    private fun startLoop() {
        scope.launch {
            delay(4000)
            while (isActive) {
                try { runPidCycle() }
                catch (e: Exception) { Log.e(TAG, "❌ Errore ciclo PID: ${e.message}") }
                delay(intervalMs)
            }
        }
    }

    // PID CYCLE
    private suspend fun runPidCycle() {

        // ORARIO
        if (!isTimeAllowed()) {
            heaterRepo.turnOff()
            return
        }

        // OCCUPANCY
        val occupied = isRoomOccupied()
        if (!occupied) {
            heaterRepo.turnOff()
            return
        }

        // SENSOR DATA
        temperatureVM.loadDataForZone("Nicole")
        delay(1500)

        val pmv = temperatureVM.spmv.value.pmv
        val tAmb = temperatureVM.temperature.value
        if (pmv == null || tAmb == null) {
            heaterRepo.turnOff()
            return
        }

        // STATO PRESA + SICUREZZA
        val plugStatus = heaterRepo.getStatus()
        if (!heaterRepo.isSafe(plugStatus)) {
            heaterRepo.turnOff()
            return
        }

        // PID LOGICA + CAMBIO STATO
        val (newState, changed) = pid.update(pmv)

        // Log SOLO se cambia
        if (changed) {
            firebaseLogger.logPidStep(
                pmv = pmv,
                heaterState = newState,
                occupancy = occupied,
                tIndoor = tAmb,
                plugPower = plugStatus?.apower,
                dtMs = intervalMs
            )
        }

        // Comando reale
        if (newState) heaterRepo.turnOn()
        else heaterRepo.turnOff()
    }

    // OCCUPANCY
    private suspend fun isRoomOccupied(): Boolean {
        val plugs = smartRepo.fetchPlugsForRoom("Nicole")
        return plugs.sumOf { it.apower } > 5.0
    }

    // ORARIO CONSENTITO
    private fun isTimeAllowed(): Boolean {
        val h = Calendar.getInstance(TZ).get(Calendar.HOUR_OF_DAY)
        return h in 8..20
    }
}
