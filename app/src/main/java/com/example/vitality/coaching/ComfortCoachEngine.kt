package com.example.vitality.coaching

import com.example.vitality.data.ComfortClass
import com.example.vitality.service.ComfortCoachFirebaseLogger
import com.robotemi.sdk.Robot

/**
 * Engine di valutazione comfort per interventi automatici.
 * Combina PMV (termico), CO₂ e illuminamento (lux).
 * Restituisce una decisione completa, con severity e suggerimenti pratici.
 */
class ComfortCoachEngine(
    private val robot: Robot,
    private val firebaseLogger: ComfortCoachFirebaseLogger
) {

    data class ComfortDecision(
        val shouldCoach: Boolean,
        val severity: Int,
        val message: String,
        val reason: String,
        val suggestions: List<String>
    )

    fun evaluateComfort(poi: String, data: ComfortData): ComfortDecision {

        val alerts = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var severity = 0   // 0 = ok, 1 = leggero, 2 = importante, 3 = critico

        // ============================================================
        // 1) TERMICO – SOLO PMV PRINCIPALE
        // ============================================================
        val pmv = data.pmv
        val classif = data.comfortClass

        if (pmv != null && classif != null) {
            when (classif) {

                ComfortClass.COLD -> {
                    alerts += "Temperatura percepita molto bassa"
                    suggestions += "Se puoi, accendi la stufetta per migliorare il comfort."
                    suggestions += "Assicurati che la finestra sia completamente chiusa."
                    severity = maxOf(severity, 3)
                }

                ComfortClass.COOL -> {
                    alerts += "Temperatura percepita bassa"
                    suggestions += "Accendi la stufetta se necessario."
                    suggestions += "Controlla se la finestra è in ribalta e chiudila."
                    severity = maxOf(severity, 2)
                }

                ComfortClass.NEUTRAL -> {
                    // Nessun alert termico
                }

                ComfortClass.WARM -> {
                    alerts += "Temperatura percepita leggermente alta"
                    suggestions += "Apri la finestra in modalità ribalta per migliorare il comfort."
                    severity = maxOf(severity, 1)
                }

                ComfortClass.HOT -> {
                    alerts += "Temperatura percepita molto alta"
                    suggestions += "Apri completamente la finestra per far entrare aria fresca."
                    severity = maxOf(severity, 3)
                }
            }
        }

        // ============================================================
        // 2) CO₂ – qualità dell'aria e ventilazione
        // ============================================================
        data.co2?.let { co2 ->

            when {
                co2 >= 1400 -> {
                    alerts += "CO₂ molto elevata"
                    suggestions += "Apri completamente la finestra per migliorare la ventilazione."
                    severity = maxOf(severity, 3)
                }

                co2 in 1000.0..1399.9 -> {
                    alerts += "CO₂ alta"
                    suggestions += "Metti la finestra in ribalta per migliorare il ricambio d’aria."
                    severity = maxOf(severity, 2)
                }

                // Sotto 1000 → ok
            }
        }

        // ============================================================
        // 3) ILLUMINAMENTO – comfort visivo
        // ============================================================
        data.lux?.let { lx ->

            when {
                lx < 60 -> {
                    alerts += "Illuminazione molto bassa"
                    suggestions += "Accendi la luce oppure apri gli oscuranti."
                    severity = maxOf(severity, 1)
                }

                lx > 800 -> {
                    alerts += "Illuminazione troppo elevata"
                    suggestions += "Riduci i riflessi abbassando gli oscuranti."
                    severity = maxOf(severity, 1)
                }

                // 150–800 → ok
            }
        }

        // ============================================================
        // OUTPUT COMPLESSIVO
        // ============================================================
        if (alerts.isEmpty()) {
            return ComfortDecision(
                shouldCoach = false,
                severity = 0,
                message = "",
                reason = "Comfort generale adeguato",
                suggestions = emptyList()
            )
        }

        return ComfortDecision(
            shouldCoach = true,
            severity = severity,
            message = "Condizioni ambientali non ottimali rilevate.",
            reason = alerts.joinToString(" • "),
            suggestions = suggestions
        )
    }
}
