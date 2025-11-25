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
import com.example.vitality.coaching.*
import com.example.vitality.data.SmartPlugRepository
import com.example.vitality.viewmodel.TemperatureViewModel
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ComfortCoachService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val robot: Robot by lazy { Robot.getInstance() }
    private val temperatureVM = TemperatureViewModel()
    private val provider = EnvironmentalSensorProvider(temperatureVM)
    private val logger = ComfortCoachFirebaseLogger()
    private val engine = ComfortCoachEngine(robot, logger)
    private val movement = ComfortCoachMovementController(robot)
    private val smartRepo = SmartPlugRepository()

    /** intervallo tra cicli interi */
    private val intervalMs = TimeUnit.MINUTES.toMillis(15)

    /** cooldown interventi sulla stessa stanza */
    private val cooldownMs = TimeUnit.MINUTES.toMillis(30)

    /** room → tipo → ultimo intervento */
    private val lastInterventions = mutableMapOf<String, MutableMap<String, Long>>()

    private var loopStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.e("COACH", "🔥 ComfortCoachService creato")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        if (!loopStarted) {
            loopStarted = true
            startMainLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------
    // Foreground immediate
    // ------------------------------------------------------------
    private fun startForegroundSafe() {
        val channelId = "comfortcoach_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Comfort Coach",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }

        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Comfort Coaching attivo")
            .setContentText("Monitoraggio ambientale in corso…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(2024, notif)
    }

    // ------------------------------------------------------------
    // Cooldown handling
    // ------------------------------------------------------------
    private fun inCooldown(room: String, type: String): Boolean {
        val m = lastInterventions[room] ?: return false
        val last = m[type] ?: return false
        val active = (System.currentTimeMillis() - last) < cooldownMs
        if (active) Log.w("COACH", "⏳ Cooldown attivo per $room ($type)")
        return active
    }

    private fun updateCooldown(room: String, type: String) {
        lastInterventions
            .getOrPut(room) { mutableMapOf() }[type] = System.currentTimeMillis()
    }

    // ------------------------------------------------------------
    // Main loop
    // ------------------------------------------------------------
    private fun startMainLoop() {
        scope.launch {
            delay(5000)
            while (isActive) {
                try {
                    runCoachingCycle()
                } catch (e: Exception) {
                    Log.e("COACH", "❌ Errore ciclo: ${e.message}", e)
                }
                delay(intervalMs)
            }
        }
    }

    // ------------------------------------------------------------
    // Occupancy robusta
    // ------------------------------------------------------------
    private suspend fun isRoomOccupied(roomNorm: String): Boolean {
        val room = provider.mapNormalizedToRoom(roomNorm) ?: return false

        val plugs = try {
            smartRepo.fetchPlugsForRoom(room)
        } catch (e: Exception) {
            Log.e("COACH", "⚠ Occupancy fallita: ${e.message}")
            return false
        }

        if (plugs.isEmpty()) return false

        val total = plugs.sumOf { it.apower }
        Log.e("COACH", "👤 OCCUPANCY [$room] → sum=$total")

        return total > 5.0
    }

    // ------------------------------------------------------------
    // Main coaching flow
    // ------------------------------------------------------------
    private suspend fun runCoachingCycle() {

        Log.e("COACH", "🔄 Avvio ciclo coaching…")

        val allPoi = robot.locations ?: emptyList()
        if (allPoi.isEmpty()) {
            Log.e("COACH", "⚠ Nessun POI disponibile")
            return
        }

        data class Task(val poi: String, val roomNorm: String, val alertType: String)
        val tasks = mutableListOf<Task>()

        // -------------------------------
        // SCANSIONE DI TUTTE LE STANZE
        // -------------------------------
        for (poi in allPoi) {
            if (poi.equals("home base", true)) continue

            val norm = Normalizer.normalize(poi)
            val comfort = provider.getComfortForPoi(norm) ?: continue

            val decision = engine.evaluateComfort(poi, comfort)
            if (!decision.shouldCoach) continue

            if (!isRoomOccupied(norm)) continue

            val type = comfort.comfortClass?.name ?: "GENERIC"

            if (inCooldown(norm, type)) continue

            tasks += Task(poi, norm, type)
        }

        if (tasks.isEmpty()) {
            Log.e("COACH", "✔ Nessuna stanza richiede intervento")
            return
        }

        // -------------------------------
        // PER OGNI STANZA: vai → parla → logga
        // -------------------------------
        for (t in tasks) {

            val arrived = CompletableDeferred<Boolean>()

            movement.navigate(
                t.poi,
                onArrival = { arrived.complete(true) },
                onAbort   = { arrived.complete(false) }
            )

            val success = withTimeoutOrNull(60_000) {  // 60s timeout
                arrived.await()
            } ?: false

            if (!success) {
                Log.e("COACH", "⚠ Fallito → skip ${t.poi}")
                continue
            }

            // Ricontrolla comfort
            val afterData = provider.getComfortForPoi(t.roomNorm)
            val recheck = afterData?.let { engine.evaluateComfort(t.poi, it) }

            if (afterData != null && recheck != null && recheck.shouldCoach) {

                val message = buildString {
                    append(recheck.reason)
                    if (recheck.suggestions.isNotEmpty()) {
                        append(". ")
                        append(recheck.suggestions.joinToString(". "))
                    }
                }

                speak(message)

                logger.logComfortEvent(
                    room = t.poi,
                    data = afterData,
                    message = message,
                    occupancy = true
                )

                updateCooldown(t.roomNorm, t.alertType)
            }
        }

        // -------------------------------
        // RITORNO ALLA BASE
        // -------------------------------
        Log.e("COACH", "🏠 RITORNO ALLA HOME BASE")
        robot.goTo("home base")
    }

    private fun speak(text: String) {
        robot.speak(TtsRequest.create(text, false))
    }
}
