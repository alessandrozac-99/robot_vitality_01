package com.example.vitality.coaching

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import kotlinx.coroutines.*

class ComfortCoachMovementController(
    private val robot: Robot
) : OnGoToLocationStatusChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var targetRaw: String? = null
    private var targetReal: String? = null
    private var fallbackTarget: String? = null
    private var onArrival: (() -> Unit)? = null
    private var onAbort: (() -> Unit)? = null

    private var retries = 0
    private val maxRetries = 4

    private var lastProgressTime = System.currentTimeMillis()
    private var navTimeoutJob: Job? = null

    // 🔥 Stati di sicurezza
    private var isNavigating = false
    private var finished = false

    init {
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    // ======================================================================
    private fun resolvePoiName(raw: String): String {
        val norm = Normalizer.normalize(raw)
        val match = robot.locations?.firstOrNull {
            Normalizer.normalize(it) == norm
        }

        return match ?: raw
    }

    // ======================================================================
    fun navigate(
        poi: String,
        fallbackPoi: String? = null,
        onArrival: () -> Unit,
        onAbort: () -> Unit
    ) {
        // 🔥 Reset stato in modo INFALLIBILE
        finished = false
        isNavigating = true

        Log.e("MOVE", "➡ navigate('$poi'), fb=$fallbackPoi")

        targetRaw = poi
        targetReal = resolvePoiName(poi)
        fallbackTarget = fallbackPoi
        this.onArrival = onArrival
        this.onAbort = onAbort

        retries = 0
        lastProgressTime = System.currentTimeMillis()

        robot.stopMovement()

        scope.launch {
            delay(200)
            val dest = targetReal
            if (dest == null) {
                Log.e("MOVE", "❌ targetReal null → abort")
                finish(false)
                return@launch
            }

            Log.e("MOVE", "🚗 goTo('$dest')")
            robot.goTo(dest)
        }

        startTimeout()
    }

    // ======================================================================
    fun goHome(onArrival: () -> Unit, onAbort: () -> Unit) {
        navigate("home base", "nicole", onArrival, onAbort)
    }

    // ======================================================================
    private fun startTimeout() {
        navTimeoutJob?.cancel()

        navTimeoutJob = scope.launch {
            delay(180_000)

            if (!isNavigating || finished) return@launch

            Log.e("MOVE", "⏳ TIMEOUT navigazione → abort")
            finish(false)
        }
    }

    // ======================================================================
    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        if (!isNavigating || finished) return

        val tgt = targetReal ?: return

        Log.e("MOVE_STATUS",
            "📡 EVENT → loc='$location', status='$status', desc='$description', tgt='$tgt'")

        if (Normalizer.normalize(location) != Normalizer.normalize(tgt)) {
            Log.e("MOVE_STATUS", "⚠ Ignorato (non target)")
            return
        }

        when (status) {

            OnGoToLocationStatusChangedListener.GOING -> {
                lastProgressTime = System.currentTimeMillis()
            }

            OnGoToLocationStatusChangedListener.COMPLETE -> {
                Log.e("MOVE", "🎉 COMPLETATO '$location'")
                finish(true)
            }

            OnGoToLocationStatusChangedListener.ABORT -> {
                handleAbort(description)
            }
        }
    }

    // ======================================================================
    private fun handleAbort(description: String) {

        if (!isNavigating || finished) return

        Log.e("MOVE", "❌ ABORT → $description")

        val now = System.currentTimeMillis()
        val stuck = now - lastProgressTime > 6000

        val doorClosed =
            description.contains("path", true) ||
                    description.contains("blocked", true)

        val obstacle = description.contains("obstacle", true)

        when {
            doorClosed -> {
                Log.e("MOVE", "🚪 Porta chiusa → fallback")
                attemptFallbackOrAbort()
            }

            stuck -> {
                Log.e("MOVE", "⏱ Bloccato troppo → fallback")
                attemptFallbackOrAbort()
            }

            obstacle && retries < maxRetries -> {
                retry()
            }

            retries >= maxRetries -> {
                Log.e("MOVE", "🛑 Max retry → abort")
                attemptFallbackOrAbort()
            }
        }
    }

    // ======================================================================
    private fun retry() {
        retries++
        Log.e("MOVE", "↩ Retry $retries/$maxRetries → $targetReal")

        scope.launch {
            robot.stopMovement()
            delay(1000)

            val dest = targetReal
            if (dest == null) {
                finish(false)
                return@launch
            }

            lastProgressTime = System.currentTimeMillis()
            robot.goTo(dest)
        }
    }

    // ======================================================================
    private fun attemptFallbackOrAbort() {

        if (finished) return

        val fb = fallbackTarget

        if (fb != null &&
            Normalizer.normalize(fb) != Normalizer.normalize(targetRaw)
        ) {

            val fbReal = resolvePoiName(fb)
            Log.e("MOVE", "↩ FALLBACK → '$fbReal'")

            navigate(
                poi = fbReal,
                fallbackPoi = null,
                onArrival = { finish(true) },
                onAbort = { finish(false) }
            )

            return
        }

        finish(false)
    }

    // ======================================================================
    private fun finish(success: Boolean) {

        if (finished) {
            Log.e("MOVE", "⚠ finish() ignorato (già finito)")
            return
        }

        finished = true
        isNavigating = false
        navTimeoutJob?.cancel()

        val callback = if (success) onArrival else onAbort

        Log.e("MOVE", "🏁 finish(success=$success) → callback")

        // Pulizia sicura
        targetRaw = null
        targetReal = null
        fallbackTarget = null
        onArrival = null
        onAbort = null

        callback?.invoke()
    }
}
