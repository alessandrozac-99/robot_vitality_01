package com.example.vitality.coaching

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener

class ComfortCoachMovementController(
    private val robot: Robot
) : OnGoToLocationStatusChangedListener {

    private var target: String? = null
    private var onArrival: (() -> Unit)? = null
    private var onAbort: (() -> Unit)? = null

    init {
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    fun navigate(
        poi: String,
        onArrival: () -> Unit,
        onAbort: () -> Unit
    ) {
        target = poi
        this.onArrival = onArrival
        this.onAbort = onAbort

        Log.d("CoachNav", "➡️ goTo($poi)")
        robot.goTo(poi)
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        if (location != target) return

        when (status) {

            OnGoToLocationStatusChangedListener.START ->
                Log.d("CoachNav","🏁 START → $location")

            OnGoToLocationStatusChangedListener.GOING ->
                Log.d("CoachNav","➡️ GOING → $location")

            OnGoToLocationStatusChangedListener.CALCULATING ->
                Log.d("CoachNav","🧮 CALCULATING")

            OnGoToLocationStatusChangedListener.COMPLETE -> {
                Log.d("CoachNav","✅ COMPLETE → $location")
                onArrival?.invoke()
            }

            OnGoToLocationStatusChangedListener.ABORT -> {
                Log.e("CoachNav","❌ ABORT → $location")
                robot.stopMovement()
                onAbort?.invoke()
            }
        }
    }
}
