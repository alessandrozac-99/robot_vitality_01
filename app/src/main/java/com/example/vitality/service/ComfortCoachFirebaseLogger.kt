package com.example.vitality.service

import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.vitality.coaching.ComfortData

class ComfortCoachFirebaseLogger {

    private val db = FirebaseDatabase.getInstance().reference

    fun logComfortEvent(
        room: String,
        data: ComfortData,
        message: String,
        occupancy: Boolean,
        relevanceFeedback: Boolean?,
        willActFeedback: Boolean?
    ) {
        val now = System.currentTimeMillis()

        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val time = SimpleDateFormat("HHmmss", Locale.US).format(Date(now))

        val ref = db.child("coaching_events").child(day).child(time)

        val payload = mapOf(
            "metadata" to mapOf(
                "timestamp" to now,
                "room" to room,
                "message" to message,
                "occupancy" to occupancy
            ),

            "comfort" to mapOf(
                "class" to data.comfortClass?.name,
                "pmv" to data.pmv,
                "co2" to data.co2,
                "lux" to data.lux,
                "voc" to data.voc,
                "iaq" to data.iaq,
                "sound" to data.sound
            ),

            "feedback" to mapOf(
                "relevance" to relevanceFeedback,
                "willAct" to willActFeedback
            )
        )

        ref.setValue(payload)
    }
}
