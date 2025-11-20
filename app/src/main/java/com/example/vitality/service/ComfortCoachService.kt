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
import com.example.vitality.coaching.ComfortCoachEngine
import com.example.vitality.coaching.ComfortCoachFirebaseLogger
import com.example.vitality.coaching.ComfortCoachMovementController
import com.example.vitality.coaching.ComfortData
import com.example.vitality.coaching.EnvironmentalSensorProvider
import com.example.vitality.coaching.Normalizer
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

    // intervallo: ogni 1 minuto
    private val intervalMs = TimeUnit.MINUTES.toMillis(5)

    // cooldown per tipologia di intervento (COLD/COOL/WARM/HOT) per stanza: 20 minuti
    private val cooldownMs = TimeUnit.MINUTES.toMillis(20)

    /**
     * mappa: stanzaNormalizzata -> (tipoIntervento -> timestampUltimoIntervento)
     * tipoIntervento = comfortClass.name (COLD, COOL, WARM, HOT)
     */
    private val lastAlertIntervention =
        mutableMapOf<String, MutableMap<String, Long>>()

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================================================
    // ONCREATE — foreground immediato (obbligatorio)
    // =====================================================================================
    override fun onCreate() {
        super.onCreate()
        Log.e("COACH", "🔥🔥🔥 ComfortCoachService ONCREATE È PARTITO 🔥🔥🔥")

        startForegroundImmediately()
        startLoop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // =====================================================================================
    // START FOREGROUND
    // =====================================================================================
    private fun startForegroundImmediately() {
        val channelId = "comfortcoach_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Comfort Coach",
                NotificationManager.IMPORTANCE_LOW
            )

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Comfort Coaching attivo")
            .setContentText("Monitoraggio ambientale e interventi automatici")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(2024, notif)
    }

    // =====================================================================================
    // COOLDOWN PER TIPO DI INTERVENTO
    // =====================================================================================
    private fun isAlertInCooldown(roomNorm: String, alertType: String): Boolean {
        val roomMap = lastAlertIntervention[roomNorm] ?: return false
        val lastTs = roomMap[alertType] ?: return false
        val now = System.currentTimeMillis()
        val inCd = (now - lastTs) < cooldownMs
        if (inCd) {
            Log.e(
                "COACH",
                "⏳ Cooldown attivo per stanza=$roomNorm, alert=$alertType → skip (Δ=${now - lastTs} ms)"
            )
        }
        return inCd
    }

    private fun updateCooldown(roomNorm: String, alertType: String) {
        val roomMap = lastAlertIntervention.getOrPut(roomNorm) { mutableMapOf() }
        roomMap[alertType] = System.currentTimeMillis()
        Log.e("COACH", "🧊 Aggiornato cooldown per stanza=$roomNorm tipo=$alertType")
    }

    // =====================================================================================
    // LOOP PRINCIPALE
    // =====================================================================================
    private fun startLoop() {
        scope.launch {
            delay(8000) // avvio dolce
            while (isActive) {
                try {
                    runCoachingCycleSequential()
                } catch (e: Exception) {
                    Log.e("COACH", "❌ Errore ciclo: ${e.message}", e)
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * Logica richiesta:
     * - misura tutte le stanze
     * - decide dove intervenire
     * - va in ogni stanza che richiede intervento, in sequenza
     * - alla fine torna alla base
     * - per ogni TIPO di intervento (COLD/COOL/WARM/HOT) su una stanza,
     *   aspetta 20 minuti prima di rifare lo stesso tipo in quella stanza
     */
    private suspend fun runCoachingCycleSequential() {

        Log.e("COACH", "🔄 Avvio ciclo coaching sequenziale...")

        val poiList = robot.locations ?: return

        Log.e("COACH", "📍 POI trovati: $poiList")

        // costruiamo lista di (roomPoi, roomNorm, alertType) su cui intervenire
        data class Task(val poi: String, val roomNorm: String, val alertType: String)

        val tasks = mutableListOf<Task>()

        for (poi in poiList) {
            // ignoriamo home base per l'intervento (non ha sensori dedicati)
            if (Normalizer.normalize(poi) == Normalizer.normalize("home base")) {
                Log.e("COACH", "ℹ️ Skip home base")
                continue
            }

            val roomNorm = Normalizer.normalize(poi)

            val data: ComfortData = provider.getComfortForPoi(roomNorm) ?: run {
                Log.e("COACH", "⚠ Nessun dato ambiente valido per $poi")
                continue
            }

            Log.e("COACH", "📊 Dati per $poi = $data")

            val decision = engine.evaluateComfort(poi, data)
            if (!decision.shouldCoach) {
                Log.e("COACH", "🟢 Nessun intervento necessario su $poi")
                continue
            }

            // Tipologia = comfortClass (COLD, COOL, WARM, HOT)
            val alertType = data.comfortClass?.name ?: "THERMAL"

            // se stesso tipo di intervento in cooldown per quella stanza → skip
            if (isAlertInCooldown(roomNorm, alertType)) {
                continue
            }

            Log.e("COACH", "🚨 Intervento candidato su $poi, tipo=$alertType, reason=${decision.reason}")
            tasks += Task(poi = poi, roomNorm = roomNorm, alertType = alertType)
        }

        if (tasks.isEmpty()) {
            Log.e("COACH", "✅ Nessun intervento da eseguire in questo ciclo")
            return
        }

        Log.e("COACH", "🧭 Interventi da eseguire in sequenza: ${tasks.map { it.poi to it.alertType }}")

        // Eseguiamo gli interventi uno dopo l'altro
        for (task in tasks) {
            val poi = task.poi
            val roomNorm = task.roomNorm
            val alertType = task.alertType

            Log.d("CoachNav", "➡️ goTo($poi)")

            val completed = CompletableDeferred<Boolean>()

            movement.navigate(
                poi = poi,
                onArrival = {
                    Log.d("CoachNav", "🏁 Arrivato → $poi")
                    completed.complete(true)
                },
                onAbort = {
                    Log.e("CoachNav", "❌ ABORT → $poi")
                    completed.complete(false)
                }
            )

            val ok = completed.await()

            if (!ok) {
                Log.e("COACH", "⚠ Intervento fallito/abortito su $poi, passo al prossimo")
                continue
            }

            // Arrivato a destinazione → ricalcoliamo i dati (opzionale ma più realistico)
            val dataAtArrival = provider.getComfortForPoi(roomNorm)
            val decisionAtArrival = dataAtArrival?.let { engine.evaluateComfort(poi, it) }

            if (dataAtArrival != null && decisionAtArrival != null && decisionAtArrival.shouldCoach) {
                Log.e("COACH", "🗣 Intervento vocale su $poi → ${decisionAtArrival.reason}")
                speak(decisionAtArrival.reason)

                logger.logComfortEvent(
                    room = poi,
                    data = dataAtArrival,
                    message = decisionAtArrival.reason,
                    occupancy = true
                )

                // aggiorniamo cooldown SOLO se è stato effettivamente fatto l'annuncio
                updateCooldown(roomNorm, alertType)
            } else {
                Log.e("COACH", "ℹ️ Arrivato a $poi ma non serve più intervento (comfort ok)")
            }
        }

        // alla fine del giro → torna alla base
        Log.d("CoachNav", "🏠 Ritorno alla base")
        robot.goTo("home base")
    }

    // =====================================================================================
    // TTS
    // =====================================================================================
    private fun speak(text: String) {
        val req = TtsRequest.create(text, false)
        robot.speak(req)
    }
}
