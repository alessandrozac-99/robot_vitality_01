package com.example.vitality.data

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round

data class SpmvResult(
    val pmv: Double,     // Modello “Elisa”
    val pmv2: Double,    // Modello “Pasq”
    val pmv3: Double,    // Modello “Buratti”
    val cloPred: Double,
    val comfortClass: ComfortClass
)

enum class ComfortClass { COLD, COOL, NEUTRAL, WARM, HOT }

/** Coefficienti per i tre modelli (a,b,c) + (sa,sb,sc) + (ba,bb,bc). */
private data class Coeffs(
    val a: Double,  val b: Double,  val c: Double,
    val sa: Double, val sb: Double, val sc: Double,
    val ba: Double, val bb: Double, val bc: Double
)

object Spmv {

    /**
     * Calcolo sPMV a partire da:
     * @param indoorT  Temperatura aria interna [°C]
     * @param indoorRH Umidità relativa interna [%] (0–100)
     * @param tOut     Temperatura esterna [°C]
     *
     * NOTE UNITS:
     * - pv è calcolata in kPa (Magnus-Tetens); i coefficienti b* si aspettano kPa.
     */
    @JvmStatic
    fun compute(indoorT: Double, indoorRH: Double, tOut: Double): SpmvResult {
        // Clamp per robustezza su input rumorosi / outliers
        val T = indoorT.coerceIn(-20.0, 50.0)
        val RH = indoorRH.coerceIn(0.0, 100.0)
        val Tout = tOut.coerceIn(-30.0, 50.0)

        // Pressione di vapore saturo interna [kPa] (Magnus per acqua in aria, T in °C)
        val es = 0.61094 * exp((17.625 * T) / (T + 243.04)) // kPa
        val pv = es * (RH / 100.0) // kPa

        // Clo predetto: polinomio (T_out, T_indoor, RH). Rimane in “clo”
        val cloPred =
            0.0109 * Tout -
                    0.0019 * Tout.pow(2) +
                    0.00004 * Tout.pow(3) -
                    0.2413 * T +
                    0.0078 * T.pow(2) -
                    0.0001 * T.pow(3) -
                    0.0011 * RH +
                    3.5530

        val coeffs = when {
            cloPred >= 1.0 -> Coeffs(
                a = 0.0761, b = 0.2769, c = -1.7138,
                sa = 0.1077, sb = 0.0329, sc = -2.4282,
                ba = 0.1478, bb = -0.1371, bc = 2.5239
            )
            cloPred >= 0.8 -> Coeffs( // 0.8 ≤ cloPred < 1.0
                a = 0.1253, b = 0.1952, c = -2.8667,
                sa = 0.1119, sb = 0.0406, sc = -2.5231,
                ba = 0.1383, bb = 0.0269, bc = 3.0190
            )
            cloPred > 0.5 -> Coeffs( // 0.5 < cloPred < 0.8
                a = 0.1391, b = 0.1207, c = -3.3579,
                sa = 0.1121, sb = 0.0413, sc = -2.5264,
                ba = 0.1383, bb = 0.0269, bc = 3.0190
            )
            else -> Coeffs( // cloPred ≤ 0.5
                a = 0.2851, b = 0.5619, c = -6.2674,
                sa = 0.1121, sb = 0.0421, sc = -2.5284,
                ba = 0.2803, bb = 0.1717, bc = 7.1383
            )
        }

        val pmv  = round2(coeffs.a  * T + coeffs.b  * pv + coeffs.c)
        val pmv2 = round2(coeffs.sa * T + coeffs.sb * pv + coeffs.sc)
        val pmv3 = round2(coeffs.ba * T + coeffs.bb * pv - coeffs.bc)

        val clazz = classify((pmv + pmv2 + pmv3) / 3.0)

        return SpmvResult(pmv, pmv2, pmv3, round2(cloPred), clazz)
    }

    private fun classify(pmv: Double): ComfortClass = when {
        pmv < -1.0  -> ComfortClass.COLD
        pmv < -0.3  -> ComfortClass.COOL
        pmv <= 0.3  -> ComfortClass.NEUTRAL
        pmv <= 1.0  -> ComfortClass.WARM
        else        -> ComfortClass.HOT
    }

    private fun round2(x: Double) = round(x * 100.0) / 100.0
}
