package com.example.vitality.coaching

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import kotlinx.coroutines.*

class ComfortCoachMovementController(
    private val robot: Robot
) : OnGoToLocationStatusChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var target: String? = null
    private var retries = 0
    private val maxRetries = 3

    private var onArrival: (() -> Unit)? = null
    private var onAbort: (() -> Unit)? = null

    init {
        Log.e("COACH_MOVE", "📡 MovementController inizializzato")
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    fun navigate(
        poi: String,
        onArrival: () -> Unit,
        onAbort: () -> Unit
    ) {
        Log.e("COACH_MOVE", "➡ navigate() richiesto → $poi")

        target = poi
        retries = 0
        this.onArrival = onArrival
        this.onAbort = onAbort

        Log.e("COACH_MOVE", "⛔ Movimento precedente fermato")
        robot.stopMovement()

        scope.launch {
            delay(300)
            Log.e("COACH_MOVE", "🚗 goTo($poi)")
            robot.goTo(poi)
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {

        Log.e(
            "COACH_MOVE_STATUS",
            "📍 STATUS → location=$location | status=$status | desc=$description"
        )

        // Se non è il target attuale → ignoralo
        if (target == null || location != target) {
            Log.w(
                "COACH_MOVE_STATUS",
                "⚠ Evento ignorato → target attuale=$target"
            )
            return
        }

        when (status) {

            OnGoToLocationStatusChangedListener.COMPLETE -> {
                Log.e("COACH_MOVE", "🏁 ARRIVATO a $location (retries=$retries)")
                target = null   // 🔥 RESET FONDAMENTALE
                onArrival?.invoke()
            }

            OnGoToLocationStatusChangedListener.ABORT -> {

                Log.e("COACH_MOVE", "❌ ABORT a $location (retry=$retries/$maxRetries)")

                if (retries < maxRetries) {
                    retries++
                    retryMove(location)
                } else {
                    Log.e("COACH_MOVE", "🛑 ABORT definitivo")
                    target = null   // 🔥 RESET anche qui
                    robot.stopMovement()
                    onAbort?.invoke()
                }
            }

            else -> {
                Log.d("COACH_MOVE_STATUS", "ℹ Stato non gestito: $status")
            }
        }
    }

    private fun retryMove(location: String) {
        val wait = when (retries) {
            1 -> 3000L
            2 -> 5000L
            else -> 8000L
        }

        Log.e("COACH_MOVE", "↩ Retry #$retries dopo $wait ms → $location")

        scope.launch {
            robot.stopMovement()
            delay(wait)
            Log.e("COACH_MOVE", "🚗 Retry goTo($location)")
            robot.goTo(location)
        }
    }
}
