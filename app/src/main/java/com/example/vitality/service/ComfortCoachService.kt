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
    private val interaction = ComfortCoachInteractionManager(robot)
    private val engine = ComfortCoachEngine(robot, logger)
    private val movement = ComfortCoachMovementController(robot)
    private val smartRepo = SmartPlugRepository()

    // intervalli
    private val intervalMs = TimeUnit.MINUTES.toMillis(15)
    private val cooldownMs = TimeUnit.MINUTES.toMillis(30)

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

    // --------------------------------------------------------------------
    // NOTIFICA DI FOREGROUND
    // --------------------------------------------------------------------
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
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Comfort Coach attivo")
            .setContentText("Monitoraggio ambientale…")
            .build()

        startForeground(2024, notif)
    }

    // --------------------------------------------------------------------
    // COOLDOWN CONTROL
    // --------------------------------------------------------------------
    private fun inCooldown(room: String, type: String): Boolean {
        val last = lastInterventions[room]?.get(type) ?: return false
        val active = (System.currentTimeMillis() - last) < cooldownMs
        if (active) Log.w("COACH", "⏳ Cooldown attivo per $room ($type)")
        return active
    }

    private fun updateCooldown(room: String, type: String) {
        lastInterventions.getOrPut(room) { mutableMapOf() }[type] =
            System.currentTimeMillis()
    }

    // --------------------------------------------------------------------
    // AVVIO DEL MAIN LOOP
    // --------------------------------------------------------------------
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

    // --------------------------------------------------------------------
    // OCCUPANCY
    // --------------------------------------------------------------------
    private suspend fun isRoomOccupied(norm: String): Boolean {
        val room = provider.mapNormalizedToRoom(norm) ?: return false

        val plugs = try {
            smartRepo.fetchPlugsForRoom(room)
        } catch (e: Exception) {
            Log.e("COACH", "⚠ Occupancy FAILED: ${e.message}")
            return false
        }

        if (plugs.isEmpty()) return false

        val total = plugs.sumOf { it.apower }
        Log.e("COACH", "👤 OCCUPANCY [$room] → $total W")
        return total > 5
    }

    // --------------------------------------------------------------------
    // CICLO PRINCIPALE DI COACHING
    // --------------------------------------------------------------------
    private suspend fun runCoachingCycle() {

        Log.e("COACH", "🔄 Avvio ciclo coaching…")

        val allPoi = robot.locations ?: emptyList()
        if (allPoi.isEmpty()) {
            Log.e("COACH", "⚠ Nessun POI registrato")
            return
        }

        data class Task(val poi: String, val norm: String, val type: String)
        val tasks = mutableListOf<Task>()

        // ---------------- STANZA PER STANZA ----------------
        for (poi in allPoi) {

            if (poi.equals("home base", true)) continue

            val norm = Normalizer.normalize(poi)
            val comfort = provider.getComfortForPoi(norm) ?: continue

            val evaluation = engine.evaluateComfort(poi, comfort)
            if (!evaluation.shouldCoach) continue

            if (!isRoomOccupied(norm)) continue

            val type = comfort.comfortClass?.name ?: "GENERIC"
            if (inCooldown(norm, type)) continue

            Log.e("COACH", "✔ Task → $poi")
            tasks += Task(poi, norm, type)
        }

        if (tasks.isEmpty()) {
            Log.e("COACH", "✔ NESSUN INTERVENTO")
            return
        }

        // ---------------- ESECUZIONE INTERVENTI ----------------
        for (task in tasks) {

            Log.e("COACH_NAV", "🧭 Navigazione verso ${task.poi}")

            val arrived = CompletableDeferred<Boolean>()

            movement.navigate(
                poi = task.poi,
                onArrival = { arrived.complete(true) },
                onAbort = { arrived.complete(false) }
            )

            val ok = withTimeoutOrNull(180_000) { arrived.await() } ?: false

            if (!ok) {
                Log.e("COACH_NAV", "⚠ Navigation FAILED → skip ${task.poi}")
                continue
            }

            // ---------------- RECHECK DOPO ARRIVO ----------------
            val data = provider.getComfortForPoi(task.norm)
            val recheck = data?.let { engine.evaluateComfort(task.poi, it) }

            if (recheck == null || !recheck.shouldCoach) {
                Log.e("COACH", "ℹ Comfort migliorato → nessun messaggio")
                continue
            }

            // ------------------------------------------------------
            // 1️⃣ PARLA CONSIGLIO COMPLETO SENZA TAGLI
            // ------------------------------------------------------
            val fullMessage = buildString {
                append(recheck.reason)
                if (recheck.suggestions.isNotEmpty()) {
                    append(". ")
                    append(recheck.suggestions.joinToString(". "))
                }
            }

            speakBlocking(fullMessage)

            // ------------------------------------------------------
            // 2️⃣ DOMANDA 1
            // ------------------------------------------------------
            val relevance = interaction.askYesNo("Ti sembra un consiglio utile?")

            // ------------------------------------------------------
            // 3️⃣ DOMANDA 2
            // ------------------------------------------------------
            val willAct = interaction.askYesNo(
                "Hai intenzione di applicare questo consiglio a breve?"
            )

            // ------------------------------------------------------
            // 4️⃣ LOG FIREBASE
            // ------------------------------------------------------
            logger.logComfortEvent(
                room = task.poi,
                data = data,
                message = fullMessage,
                occupancy = true,
                relevanceFeedback = relevance,
                willActFeedback = willAct
            )

            updateCooldown(task.norm, task.type)
        }

        // ---------------- RITORNO ALLA BASE ----------------
        Log.e("COACH", "🏠 RITORNO ALLA BASE")
        robot.goTo("home base")
    }

    // --------------------------------------------------------------------
    // TTS PRECISO SENZA TAGLIO – USA SOLO estimateTtsDuration()
    // --------------------------------------------------------------------
    private fun speakBlocking(text: String) {
        Log.e("COACH_TTS", "🔊 Parlo: $text")

        val req = TtsRequest.create(text, false)
        robot.speak(req)

        val wait = estimateTtsDuration(text)
        Log.e("COACH_TTS", "⏳ Attesa stimata: ${wait}ms")

        Thread.sleep(wait)
    }

    private fun estimateTtsDuration(text: String): Long {
        val base = text.length * 85L   // Temi ≈ 12 char/sec
        return base + 700              // margine sicurezza
    }
}
