package com.example.vitality.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

    // === COMPONENTI ===
    private val temperatureVM = TemperatureViewModel()
    private val firebaseLogger = HeaterPidFirebaseLogger()
    private val smartRepo = SmartPlugRepository()

    private val heaterRepo = HeaterNicoleRepository(
        smartRepo = smartRepo,
        logger = firebaseLogger
    )

    // === PID HVAC (isteresi + anti short-cycle) ===
    private val pid = HeaterPIDController(
        turnOnThreshold = -0.20,
        turnOffThreshold = +0.15,
        minOffDurationMs = 180_000,
        minOnDurationMs = 120_000
    )

    private val intervalMs = 180_000L // ciclo ogni 3 minuti

    private val TAG = "NicoleHeaterSvc"
    private val TZ = TimeZone.getTimeZone("Europe/Rome")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceInternal()
        Log.e(TAG, "🔥 NicoleHeaterControlService AVVIATO")
        startLoop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // =====================================================================================
    // NOTIFICA FOREGROUND
    // =====================================================================================
    private fun startForegroundServiceInternal() {
        val channelId = "nicole_heater_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Heater PID Nicole",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PID Stufetta Nicole")
            .setContentText("Controllo attivo del comfort termico")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()

        startForeground(3001, notif)
    }

    // =====================================================================================
    // CICLO PRINCIPALE
    // =====================================================================================
    private fun startLoop() {
        scope.launch {
            delay(4000)
            while (isActive) {
                try {
                    runPidCycle()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Errore ciclo PID: ${e.message}", e)
                }
                delay(intervalMs)
            }
        }
    }

    // =====================================================================================
    // PID CYCLE
    // =====================================================================================
    private suspend fun runPidCycle() {

        // 1) Controllo orario
        if (!isTimeAllowed()) {
            Log.w(TAG, "⏱ Fuori dall'orario → OFF")
            heaterRepo.turnOff()
            return
        }

        // 2) Occupazione
        val occupied = isRoomOccupied()
        if (!occupied) {
            Log.w(TAG, "🚫 Stanza NON occupata → OFF")
            heaterRepo.turnOff()
            return
        }

        // 3) Carico dati sensore
        temperatureVM.loadDataForZone("Nicole")
        delay(1500)

        val spmv = temperatureVM.spmv.value.pmv
        val tAmb = temperatureVM.temperature.value

        if (spmv == null || tAmb == null) {
            Log.e(TAG, "⚠ PMV/T null → OFF")
            heaterRepo.turnOff()
            return
        }

        // 4) Stato presa + sicurezza
        val status = heaterRepo.getStatus()
        if (!heaterRepo.isSafe(status)) {
            Log.e(TAG, "❌ NON sicura → OFF")
            heaterRepo.turnOff()
            firebaseLogger.logSafetyEvent("unsafe_plug")
            return
        }

        // 5) ISTERESI + ANTI SHORT-CYCLE
        val newHeaterState = pid.update(spmv)

        Log.e(TAG, "📊 PID RESULT → PMV=$spmv | heaterShouldBe=$newHeaterState")

        // 6) Logging coerente col nuovo schema
        firebaseLogger.logPidStep(
            pmv = spmv,
            heaterState = newHeaterState,
            occupancy = occupied,
            tIndoor = tAmb,
            plugPower = status?.apower,
            dtMs = intervalMs
        )

        // 7) Comando fisico ON/OFF
        if (newHeaterState) {
            Log.e(TAG, "❄ Freddo → ON (isteresi)")
            heaterRepo.turnOn()
        } else {
            Log.e(TAG, "🔥 Caldo/comfort → OFF (isteresi)")
            heaterRepo.turnOff()
        }
    }

    // =====================================================================================
    // OCCUPANCY CONTROL
    // =====================================================================================
    private suspend fun isRoomOccupied(): Boolean {
        val plugs = smartRepo.fetchPlugsForRoom("Nicole")
        val sum = plugs.sumOf { it.apower }
        return sum > 5.0
    }

    // =====================================================================================
    // ORARIO CONSENTITO
    // =====================================================================================
    private fun isTimeAllowed(): Boolean {
        val cal = Calendar.getInstance(TZ)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return h in 8..20
    }
}
