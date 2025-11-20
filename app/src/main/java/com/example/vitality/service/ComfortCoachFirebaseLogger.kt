package com.example.vitality.coaching

import com.google.firebase.database.FirebaseDatabase

class ComfortCoachFirebaseLogger {

    private val db = FirebaseDatabase.getInstance().reference

    fun logComfortEvent(
        room: String,
        data: ComfortData,
        message: String,
        occupancy: Boolean
    ) {
        val now = System.currentTimeMillis()

        val entry = mapOf(
            "timestamp" to now,
            "room" to room,
            "pmv" to data.pmv,
            "pmv2" to data.pmv2,
            "pmv3" to data.pmv3,
            "cloPred" to data.cloPred,
            "comfortClass" to data.comfortClass?.name,
            "co2" to data.co2,
            "lux" to data.lux,
            "voc" to data.voc,
            "iaq" to data.iaq,
            "sound" to data.sound,
            "message" to message,
            "occupancy" to occupancy
        )

        db.child("coaching_events").child(now.toString()).setValue(entry)
    }
}
