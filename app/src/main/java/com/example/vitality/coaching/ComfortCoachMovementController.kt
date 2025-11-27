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
    private var onArrival: (() -> Unit)? = null
    private var onAbort: (() -> Unit)? = null

    private var retries = 0
    private val maxRetries = 2

    private var lastProgressTime = System.currentTimeMillis()

    init {
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    fun navigate(
        poi: String,
        onArrival: () -> Unit,
        onAbort: () -> Unit
    ) {
        Log.e("MOVE", "➡ Navigazione verso $poi")

        target = poi
        retries = 0
        lastProgressTime = System.currentTimeMillis()

        this.onArrival = onArrival
        this.onAbort = onAbort

        robot.stopMovement()

        scope.launch {
            delay(300)
            Log.e("MOVE", "🚗 goTo($poi)")
            robot.goTo(poi)
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        Log.e("MOVE_STATUS",
            "📍 loc=$location | status=$status | desc=$description"
        )

        val tgt = target ?: return
        if (location != tgt) return

        when (status) {

            OnGoToLocationStatusChangedListener.GOING -> {
                lastProgressTime = System.currentTimeMillis()
            }

            OnGoToLocationStatusChangedListener.COMPLETE -> {
                Log.e("MOVE", "✅ Arrivato a $location")
                finish(success = true)
            }

            OnGoToLocationStatusChangedListener.ABORT -> {
                handleAbort(location, description)
            }
        }
    }

    // -------------------------------------------------------------------
    // INTELLIGENT ABORT HANDLER
    // -------------------------------------------------------------------
    private fun handleAbort(location: String, description: String) {

        Log.e("MOVE", "❌ ABORT → $description")

        val now = System.currentTimeMillis()
        val stuckTooLong = now - lastProgressTime > 6000 // 6 sec senza progresso

        val doorLikelyClosed =
            description.contains("path", true) ||
                    description.contains("blocked", true)

        val physicalObstacle =
            description.contains("obstacle", true)

        // --- FALLIMENTO IMMEDIATO (porta chiusa, path impossibile)
        if (doorLikelyClosed) {
            Log.e("MOVE", "🚪 Porta chiusa o percorso impossibile → ABORT HARD")
            finish(false)
            return
        }

        // --- FALLIMENTO SE BLOCCATO TROPPO A LUNGO
        if (stuckTooLong) {
            Log.e("MOVE", "⏱ Robot bloccato da troppo → ABORT HARD")
            finish(false)
            return
        }

        // --- OSTACOLO TEMPORANEO → RETRY FINO A 2 VOLTE
        if (physicalObstacle && retries < maxRetries) {
            retry(location)
            return
        }

        // --- TROPPI TENTATIVI → HARD ABORT
        if (retries >= maxRetries) {
            Log.e("MOVE", "🛑 Troppi tentativi → ABORT HARD")
            finish(false)
            return
        }
    }

    // -------------------------------------------------------------------
    // RETRY
    // -------------------------------------------------------------------
    private fun retry(location: String) {
        retries++
        Log.e("MOVE", "↩ Retry $retries/$maxRetries verso $location")

        scope.launch {
            robot.stopMovement()
            delay(1200)
            lastProgressTime = System.currentTimeMillis()
            robot.goTo(location)
        }
    }

    // -------------------------------------------------------------------
    // FINE NAVIGAZIONE
    // -------------------------------------------------------------------
    private fun finish(success: Boolean) {
        val cb = if (success) onArrival else onAbort

        target = null
        onArrival = null
        onAbort = null

        cb?.invoke()
    }
}
