package com.example.vitality.coaching

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import kotlinx.coroutines.*

class ComfortCoachMovementController(
    private val robot: Robot
) : OnGoToLocationStatusChangedListener {

    private var target: String? = null
    private var onArrival: (() -> Unit)? = null
    private var onFinalAbort: (() -> Unit)? = null

    private var retries = 0
    private val maxRetries = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    fun navigate(
        poi: String,
        onArrival: () -> Unit,
        onAbort: () -> Unit
    ) {
        // Reset state
        target = poi
        this.onArrival = onArrival
        this.onFinalAbort = onAbort
        retries = 0

        Log.d("CoachNav", "➡️ NAVIGATE → $poi")

        // stop any previous movement
        robot.stopMovement()

        // allow stabilization
        scope.launch {
            delay(500)
            Log.d("CoachNav", "🚀 goTo($poi)")
            robot.goTo(poi)
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        if (location != target) return

        when (status) {

            OnGoToLocationStatusChangedListener.START -> {
                Log.d("CoachNav", "🏁 START → $location")
            }

            OnGoToLocationStatusChangedListener.GOING -> {
                Log.d("CoachNav", "➡️ GOING → $location")
            }

            OnGoToLocationStatusChangedListener.CALCULATING -> {
                Log.d("CoachNav", "🧮 CALCULATING → $location")
            }

            OnGoToLocationStatusChangedListener.REPOSING -> {
                Log.d("CoachNav", "♻️ REPOSING → $location")
            }

            OnGoToLocationStatusChangedListener.COMPLETE -> {
                Log.d("CoachNav", "✅ COMPLETE → $location")
                onArrival?.invoke()
            }

            OnGoToLocationStatusChangedListener.ABORT -> {
                Log.e("CoachNav", "❌ ABORT → $location  (retry=$retries/$maxRetries)")

                if (retries < maxRetries) {
                    retries++

                    // Backoff esponenziale
                    val wait = when (retries) {
                        1 -> 4000L
                        2 -> 6000L
                        else -> 10000L
                    }

                    Log.w("CoachNav", "⏳ Retry #$retries tra ${wait}ms")

                    scope.launch {
                        // fermo movimento pendente
                        robot.stopMovement()

                        // stabilizzazione sensori
                        delay(500)

                        // rilocalizzazione (se SDK supporta)
                        try {
                            robot.repose(null)
                            Log.d("CoachNav", "📡 Rilocalizzazione richiesta")
                        } catch (_: Exception) {
                            Log.w("CoachNav", "⚠ relocalize() non supportato")
                        }

                        // attesa backoff
                        delay(wait)

                        Log.d("CoachNav", "📤 Retry movimento → $location")
                        robot.goTo(location)
                    }

                } else {
                    // troppi abort → fallimento definitivo
                    Log.e("CoachNav", "💀 ABORT FINALE → stop e callback")
                    robot.stopMovement()
                    onFinalAbort?.invoke()
                }
            }
        }
    }
}
