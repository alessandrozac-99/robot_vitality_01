package com.example.vitality.pid

/**
 * Controller HVAC dedicato alla stufetta ON/OFF basato su PMV.
 *
 * - PMV < -0.2  → freddo → possibile ON
 * - -0.2 ≤ PMV ≤ +0.2 → comfort → stufetta OFF
 * - PMV > +0.0 → troppo caldo → OFF STRICT
 *
 * Controller proporzionale con deadband.
 */
class HeaterPIDController(
    var setpoint: Double = 0.0,      // target PMV
    private val comfortBand: Double = 0.2,
    private val kp: Double = 10.0    // molto più alto = migliore risposta ON/OFF
) {

    var lastError: Double = 0.0
        private set

    var integralTerm: Double = 0.0  // sempre zero in questa architettura
        private set

    var derivativeTerm: Double = 0.0  // sempre zero
        private set

    fun update(pmv: Double, dt: Double = 0.0): Double {

        val error = setpoint - pmv
        lastError = error

        // HARD cutoff: se PMV >= 0 → OFF sempre
        if (error <= 0.0) return 0.0

        // Zona comfort: da -0.2 a 0.0 → stufetta OFF
        if (error < comfortBand) return 0.0

        // Proporzionale puro
        val p = kp * error

        // Il servizio userà (output > 0) come ON
        return p
    }
}
