package com.example.vitality.pid

/**
 * Controllo ON/OFF basato su PMV con isteresi definita:
 *  - Accensione: PMV <= -0.20
 *  - Spegnimento: PMV >= +0.15
 *
 * Include protezioni anti short-cycle:
 *  - minimo tempo OFF prima di riaccendere
 *  - minimo tempo ON prima di spegnere
 */
class HeaterPIDController(

    private val turnOnThreshold: Double = -0.20,   // ACCENSIONE
    private val turnOffThreshold: Double = +0.15,  // SPEGNIMENTO

    private val minOffDurationMs: Long = 180_000,  // 3 min OFF → protezione
    private val minOnDurationMs: Long = 120_000    // 2 min ON → stabilità
) {

    private var lastSwitchTime: Long = 0L
    var isHeaterOn: Boolean = false
        private set

    fun update(pmv: Double, now: Long = System.currentTimeMillis()): Boolean {

        val elapsed = now - lastSwitchTime

        // === LOGICA ACCENSIONE ===
        if (!isHeaterOn) {
            if (pmv <= turnOnThreshold && elapsed >= minOffDurationMs) {
                isHeaterOn = true
                lastSwitchTime = now
            }
        }
        // === LOGICA SPEGNIMENTO ===
        else {
            if (pmv >= turnOffThreshold && elapsed >= minOnDurationMs) {
                isHeaterOn = false
                lastSwitchTime = now
            }
        }

        return isHeaterOn
    }
}
