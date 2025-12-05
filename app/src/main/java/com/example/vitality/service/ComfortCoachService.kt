package com.example.vitality.service

import android.app.*
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

    private val intervalMs = TimeUnit.MINUTES.toMillis(30)
    private val cooldownMs = TimeUnit.MINUTES.toMillis(40)

    private val lastInterventions = mutableMapOf<String, MutableMap<String, Long>>()
    private var loopStarted = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Log.e("SERVICE", "🔥 onCreate() → avvio foreground")
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e("SERVICE", "▶ onStartCommand(startId=$startId, flags=$flags)")
        if (!loopStarted) {
            Log.e("SERVICE", "🚀 Avvio loop principale")
            loopStarted = true
            startMainLoop()
        } else {
            Log.e("SERVICE", "ℹ Loop già avviato → skip")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.e("SERVICE", "🛑 onDestroy() → cancello scope coroutine")
        scope.cancel()
        super.onDestroy()
    }

    // ======================================================================
    private fun startForegroundService() {
        Log.e("NOTIFY", "🔔 Preparazione NotificationChannel + ForegroundService")
        val id = "comfortcoach_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.e("NOTIFY", "🔔 Creo NotificationChannel ($id)")
            val ch = NotificationChannel(id, "Comfort Coach", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(this, id)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Comfort Coach attivo")
            .setContentText("Monitoraggio ambientale…")
            .build()

        Log.e("NOTIFY", "🔔 Attivo startForeground()")
        startForeground(2024, notif)
    }

    // ======================================================================
    private fun inCooldown(room: String, type: String): Boolean {
        val last = lastInterventions[room]?.get(type)
        if (last == null) {
            Log.e("COOLDOWN", "⏱ $room/$type → nessun cooldown")
            return false
        }

        val delta = System.currentTimeMillis() - last
        val active = delta < cooldownMs

        Log.e("COOLDOWN", "⏱ check $room/$type = $active (elapsed=$delta ms)")
        return active
    }

    private fun updateCooldown(room: String, type: String) {
        Log.e("COOLDOWN", "💾 updateCooldown($room,$type)")
        lastInterventions.getOrPut(room) { mutableMapOf() }[type] = System.currentTimeMillis()
    }

    // ======================================================================
    private fun startMainLoop() {
        scope.launch {
            Log.e("LOOP", "⏳ Attesa iniziale 5s prima del primo ciclo…")
            delay(5000)

            var cycle = 0

            while (isActive) {
                cycle++
                Log.e("LOOP", "\n\n================ CYCLE #$cycle ================")

                try {
                    runCoachingCycle()
                } catch (e: Exception) {
                    Log.e("LOOP", "❌ Errore ciclo #$cycle: ${e.message}", e)
                }

                Log.e("LOOP", "🕒 Fine ciclo #$cycle → prossimo in ${intervalMs}ms")
                delay(intervalMs)
            }
        }
    }

    // ======================================================================
    private suspend fun isRoomOccupied(norm: String): Boolean {
        Log.e("OCC", "🔍 Controllo occupancy per '$norm'…")

        val room = provider.mapNormalizedToRoom(norm)
        if (room == null) {
            Log.e("OCC", "❌ Nessun mapping per '$norm'")
            return false
        }

        val plugs = runCatching { smartRepo.fetchPlugsForRoom(room) }
            .onFailure { Log.e("OCC", "❌ Errore lettura smart-plug: ${it.message}") }
            .getOrElse { return false }

        val total = plugs.sumOf { it.apower }
        val occ = total > 5

        Log.e("OCC", "🔌 potenza=$total → occupied=$occ")
        return occ
    }

    // ======================================================================
    private suspend fun runCoachingCycle() {

        Log.e("CYCLE", "🚀 Avvio runCoachingCycle()")

        val allPois = robot.locations ?: emptyList()
        Log.e("CYCLE", "📍 POI disponibili: $allPois")

        if (allPois.isEmpty()) {
            Log.e("CYCLE", "❌ Nessun POI → esco dal ciclo")
            return
        }

        data class Task(val poi: String, val norm: String, val type: String)
        val tasks = mutableListOf<Task>()

        Log.e("CYCLE", "🧠 Inizio filtro POI…")

        for (poi in allPois) {

            Log.e("FILTER", "--------------------------------")
            Log.e("FILTER", "Analisi POI='$poi'")

            if (poi.equals("home base", true)) {
                Log.e("FILTER", "🏠 Skip home base")
                continue
            }

            val norm = Normalizer.normalize(poi)
            Log.e("FILTER", "Norm → '$norm'")

            val comfort = provider.getComfortForPoi(norm)
            Log.e("FILTER", "Comfort=$comfort")
            if (comfort == null) continue

            val eval = engine.evaluateComfort(poi, comfort)
            Log.e("FILTER", "Eval=$eval")

            if (!eval.shouldCoach) {
                Log.e("FILTER", "❌ shouldCoach=false")
                continue
            }

            val occupied = isRoomOccupied(norm)
            if (!occupied) {
                Log.e("FILTER", "❌ Non occupata")
                continue
            }

            val type = comfort.comfortClass?.name ?: "GENERIC"
            if (inCooldown(norm, type)) {
                Log.e("FILTER", "⏳ In cooldown → skip")
                continue
            }

            Log.e("FILTER", "✅ Task valido → aggiunto")
            tasks += Task(poi, norm, type)
        }

        Log.e("CYCLE", "📋 Task trovati: ${tasks.size}")

        if (tasks.isEmpty()) return

        // ======================== ESECUZIONE TASK ==========================
        for ((index, task) in tasks.withIndex()) {

            Log.e("TASK", "\n🚀 Avvio TASK ${index + 1}/${tasks.size}: ${task.poi}")

            val arrived = CompletableDeferred<Boolean>()

            Log.e("MOVE", "➡ navigate verso ${task.poi}")

            movement.navigate(
                poi = task.poi,
                fallbackPoi = "home base",
                onArrival = {
                    Log.e("MOVE", "🎯 Arrivato a ${task.poi}")
                    arrived.complete(true)
                },
                onAbort = {
                    Log.e("MOVE", "❌ Abort navigazione per ${task.poi}")
                    arrived.complete(false)
                }
            )

            Log.e("MOVE", "⏳ Attesa arrivo max 180s")
            val ok = withTimeoutOrNull(180_000) { arrived.await() } ?: false
            Log.e("MOVE", "📌 Risultato navigazione = $ok")

            if (!ok) {
                Log.e("TASK", "⛔ Task impossibile → skip")

                if (index == tasks.lastIndex) {
                    Log.e("TASK", "⚠ Era l’ultimo task → ritorno alla base")
                    returnHome()
                }

                continue
            }

            // ------------------ RICALCOLO COMFORT -------------------
            Log.e("TASK", "🔁 Ricalcolo comfort…")

            val data = provider.getComfortForPoi(task.norm)
            val recheck = data?.let { engine.evaluateComfort(task.poi, it) }

            Log.e("TASK", "Recheck=$recheck")

            if (recheck == null || !recheck.shouldCoach) {
                Log.e("TASK", "👌 Problema risolto → skip messaggio")
                continue
            }

            val fullMessage =
                recheck.reason + (if (recheck.suggestions.isNotEmpty())
                    ". " + recheck.suggestions.joinToString(". ")
                else "")

            Log.e("TTS", "🗣 Messaggio → $fullMessage")
            speakBlocking(fullMessage)

            // ------------------ DOMANDE -------------------
            Log.e("ASR", "❓ Domanda relevance")
            robot.finishConversation()
            delay(300)
            val relevance = interaction.askYesNo("Ti sembra un consiglio utile?")
            Log.e("ASR", "🗯 relevance=$relevance")

            Log.e("ASR", "❓ Domanda willAct")
            robot.finishConversation()
            delay(300)
            val willAct = interaction.askYesNo("Hai intenzione di applicare questo consiglio a breve?")
            Log.e("ASR", "🗯 willAct=$willAct")

            // ------------------ LOGGING FIREBASE -------------------
            Log.e("FIREBASE", "📨 Log evento comfort su Firebase")
            logger.logComfortEvent(
                room = task.poi,
                data = data,
                message = fullMessage,
                occupancy = true,
                relevanceFeedback = relevance ?: false,
                willActFeedback = willAct ?: false
            )

            updateCooldown(task.norm, task.type)
        }

        returnHome()
    }

    // ======================================================================
    private suspend fun returnHome() {
        Log.e("MOVE", "\n🏁 Ritorno alla home base…")

        val result = CompletableDeferred<Boolean>()

        movement.goHome(
            onArrival = {
                Log.e("MOVE", "🏠 Arrivato a home base")
                result.complete(true)
            },
            onAbort = {
                Log.e("MOVE", "❌ Abort ritorno base")
                result.complete(false)
            }
        )

        val ok = withTimeoutOrNull(120_000) { result.await() }
        Log.e("MOVE", "🏁 Risultato ritorno=$ok")
    }

    // ======================================================================
    private fun speakBlocking(text: String) {
        Log.e("TTS", "🔊 speakBlocking() msg_len=${text.length}")
        robot.speak(TtsRequest.create(text, false))
        val sleep = text.length * 80L + 600
        Log.e("TTS", "⏳ Attendo ${sleep}ms per TTS")
        Thread.sleep(sleep)
        Log.e("TTS", "🎤 Fine speakBlocking()")
    }
}
