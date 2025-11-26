package com.example.vitality.coaching

import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComfortCoachFirebaseLogger {

    private val db = FirebaseDatabase.getInstance().reference

    fun logComfortEvent(
        room: String,
        data: ComfortData,
        message: String,
        occupancy: Boolean,
        relevanceFeedback: Boolean? = null,
        willActFeedback: Boolean? = null,
        badAnswersCount: Int? = null,
        noAnswerOnRelevance: Boolean? = null,
        noAnswerOnWillAct: Boolean? = null
    ) {
        val now = System.currentTimeMillis()

        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val timeKey = SimpleDateFormat("HHmmss", Locale.US).format(Date(now))

        val ref = db.child("coaching_events").child(day).child(timeKey)

        val entry = mapOf(
            "timestamp" to now,
            "room" to room,
            "comfortClass" to data.comfortClass?.name,
            "pmv" to data.pmv,
            "co2" to data.co2,
            "lux" to data.lux,
            "voc" to data.voc,
            "iaq" to data.iaq,
            "sound" to data.sound,
            "message" to message,
            "occupancy" to occupancy,
            "relevanceFeedback" to relevanceFeedback,
            "willActFeedback" to willActFeedback,
            "badAnswersCount" to badAnswersCount,
            "noAnswerOnRelevance" to noAnswerOnRelevance,
            "noAnswerOnWillAct" to noAnswerOnWillAct
        )

        ref.setValue(entry)
    }
}
