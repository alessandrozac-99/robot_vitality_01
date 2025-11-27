package com.example.vitality.pid

/**
 * Controllo ON/OFF basato su PMV con isteresi definita:
 *  - Accensione: PMV <= -0.20
 *  - Spegnimento: PMV >= +0.15
 *
 * Anti short-cycle:
 *  - minimo tempo OFF prima di riaccendere
 *  - minimo tempo ON prima di spegnere
 *
 * Restituisce Pair(newState, changed)
 */
class HeaterPIDController(

    private val turnOnThreshold: Double = -0.10,
    private val turnOffThreshold: Double = +0.15,
    private val minOffDurationMs: Long = 180_000, // 3 min OFF
    private val minOnDurationMs: Long = 120_000    // 2 min ON
) {

    private var lastSwitchTs: Long = 0L
    var isHeaterOn: Boolean = false
        private set

    /**
     * @return Pair(heaterState, changed)
     */
    fun update(pmv: Double, now: Long = System.currentTimeMillis()): Pair<Boolean, Boolean> {

        val elapsed = now - lastSwitchTs
        var changed = false

        // === ACCENSIONE ===
        if (!isHeaterOn) {
            if (pmv <= turnOnThreshold && elapsed >= minOffDurationMs) {
                isHeaterOn = true
                lastSwitchTs = now
                changed = true
            }
        }

        // === SPEGNIMENTO ===
        else {
            if (pmv >= turnOffThreshold && elapsed >= minOnDurationMs) {
                isHeaterOn = false
                lastSwitchTs = now
                changed = true
            }
        }

        return Pair(isHeaterOn, changed)
    }
}
