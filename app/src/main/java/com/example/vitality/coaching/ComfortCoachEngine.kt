package com.example.vitality.coaching

import com.robotemi.sdk.Robot
import com.example.vitality.data.ComfortClass

class ComfortCoachEngine(
    private val robot: Robot,
    private val firebaseLogger: ComfortCoachFirebaseLogger
) {

    data class ComfortDecision(
        val shouldCoach: Boolean,
        val message: String,
        val reason: String
    )

    /**
     * 🔥 Logica di coaching basata esclusivamente sul comfort termico (SPMV principale).
     * - NO CO₂
     * - NO lux
     * - NO VOC
     * - NO IAQ
     * - NO sound
     */
    fun evaluateComfort(poi: String, data: ComfortData): ComfortDecision {

        val pmv = data.pmv
        val classif = data.comfortClass

        if (pmv == null || classif == null) {
            return ComfortDecision(false, "", "Dati insufficienti")
        }

        val alerts = when (classif) {

            ComfortClass.COLD ->
                listOf("Temperatura percepita troppo bassa")

            ComfortClass.COOL ->
                listOf("Temperatura percepita leggermente bassa")

            ComfortClass.WARM ->
                listOf("Temperatura percepita leggermente alta")

            ComfortClass.HOT ->
                listOf("Temperatura percepita troppo alta")

            ComfortClass.NEUTRAL ->
                emptyList()

        }

        if (alerts.isEmpty()) {
            return ComfortDecision(
                shouldCoach = false,
                message = "",
                reason = "Comfort termico corretto"
            )
        }

        return ComfortDecision(
            shouldCoach = true,
            message = "Attenzione: condizioni termiche non ottimali.",
            reason = alerts.joinToString(" e ")
        )
    }
}
