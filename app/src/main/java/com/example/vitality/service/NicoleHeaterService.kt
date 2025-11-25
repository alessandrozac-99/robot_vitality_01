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
import com.example.vitality.pid.HeaterPIDController   // <-- ora coerente con il tuo file
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

    // === PID HVAC (P-only + deadband) ===
    private val pid = HeaterPIDController(
        setpoint = 0.0,
        comfortBand = 0.2,
        kp = 10.0
    )

    private val intervalMs = 180_000L // ciclo ogni 3 minuti

    private val TAG = "NicoleHeaterSvc"
    private val TZ = TimeZone.getTimeZone("Europe/Rome")

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================================================
    // ON CREATE
    // =====================================================================================
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
            Log.w(TAG, "🚫 Stanza Nicole NON occupata → OFF")
            heaterRepo.turnOff()
            return
        }

        // 3) Caricamento dati sensori
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
            return
        }

        // 5) PID HVAC (proporzionale + deadband)
        val pidOut = pid.update(spmv)

        Log.e(TAG, "📊 PID RESULT → PMV=$spmv | output=$pidOut")

        // 6) Log Firebase
        firebaseLogger.logPidStep(
            pmv = spmv,
            error = pid.lastError,
            pidOutput = pidOut,
            heaterState = status?.output ?: false,
            occupancy = occupied,
            tIndoor = tAmb,
            plugPower = status?.apower,
            integral = pid.integralTerm,
            derivative = pid.derivativeTerm,
            dtMs = intervalMs
        )

        // 7) Decisione ON/OFF secondo logica HVAC
        when {
            // PMV ≥ 0 → troppo caldo → OFF HARD
            spmv >= 0.0 -> {
                Log.e(TAG, "🔥 PMV ≥ 0 → OFF HARD")
                heaterRepo.turnOff()
            }

            // Zona comfort: -0.2 ≤ PMV < 0 → OFF
            pidOut <= 0.0 -> {
                Log.e(TAG, "⚖ Comfort zone → OFF")
                heaterRepo.turnOff()
            }

            // PMV < -0.2 → freddo → ON
            else -> {
                Log.e(TAG, "❄ Freddo → ON")
                heaterRepo.turnOn()
            }
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
