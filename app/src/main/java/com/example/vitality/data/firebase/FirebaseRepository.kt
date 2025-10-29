package com.example.vitality.data.firebase

import android.util.Log
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import java.util.Calendar
import java.util.TimeZone

/**
 * Repository Firebase RTDB – versione ottimizzata (campi ridotti)
 *
 * Struttura:
 *  - Snapshot ambientali (10'): /office/{ROOM}/snapshots/{YYYY-MM-DD}/{HH:mm}
 *      ↳ payload "sparse": scriviamo sempre "timestamp" + SOLO i campi richiesti
 *         (t_amb, rh, spmv, spmv2, spmv3, clo_pred)
 *  - Riepilogo prese orarie: /plugs/{PLUG}/hourly_summary/{YYYY-MM-DDThh}
 *
 * Retention:
 *  - cleanup per stanza (client-side) che mantiene solo gli ultimi N giorni
 */
class FirebaseRepository(
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance()
) {

    companion object {
        private const val TAG = "FirebaseRepository"
        private val TZ: TimeZone = TimeZone.getTimeZone("Europe/Rome")

        /** Bucket giorno, es. 2025-10-28 */
        @JvmStatic
        fun dayBucketId(nowMs: Long = System.currentTimeMillis()): String {
            val cal = Calendar.getInstance(TZ).apply { timeInMillis = nowMs }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return String.format(
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        }

        /** Chiave 10' allineata, es. 14:30 */
        @JvmStatic
        fun tenMinKey(nowMs: Long = System.currentTimeMillis()): String {
            val cal = Calendar.getInstance(TZ).apply { timeInMillis = nowMs }
            val m = cal.get(Calendar.MINUTE)
            val aligned = m - (m % 10)
            cal.set(Calendar.MINUTE, aligned)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }

        /** Bucket orario, es. 2025-10-28T14 */
        @JvmStatic
        fun hourlyBucketId(nowMs: Long = System.currentTimeMillis()): String {
            val cal = Calendar.getInstance(TZ).apply { timeInMillis = nowMs }
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return String.format(
                "%04d-%02d-%02dT%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY)
            )
        }
    }

    // ==== MODELLI ====

    /**
     * Modello “ridotto” per snapshot d’ufficio:
     *   - timestamp (sempre)
     *   - t_amb, rh, spmv, spmv2, spmv3, clo_pred
     */
    data class OfficeSnapshot(
        val timestamp: Long = 0L,
        val t_amb: Double? = null,
        val rh: Double? = null,
        val spmv: Double? = null,
        val spmv2: Double? = null,
        val spmv3: Double? = null,
        val clo_pred: Double? = null
    ) {
        /** Converte in payload “sparse”: include sempre timestamp, omette i null. */
        fun toMapFiltered(): Map<String, Any> {
            val m = mutableMapOf<String, Any>("timestamp" to timestamp)
            fun put(k: String, v: Double?) { if (v != null) m[k] = v }
            put("t_amb", t_amb)
            put("rh", rh)
            put("spmv", spmv)
            put("spmv2", spmv2)
            put("spmv3", spmv3)
            put("clo_pred", clo_pred)
            return m
        }
    }

    data class PlugHourlySummary(
        val hour_bucket: String = "",
        val timestamp_end_ms: Long = 0L,
        val count_above_5: Int = 0
    )

    // ==== PATH HELPERS ====

    private fun officeDayRef(roomKey: String, dayBucket: String): DatabaseReference =
        db.getReference("office").child(roomKey).child("snapshots").child(dayBucket)

    private fun officeSnapshotRef(roomKey: String, dayBucket: String, tenMinKey: String): DatabaseReference =
        officeDayRef(roomKey, dayBucket).child(tenMinKey)

    private fun plugHourlySummaryRef(plugName: String, hourBucket: String): DatabaseReference =
        db.getReference("plugs").child(plugName).child("hourly_summary").child(hourBucket)

    // ==== WRITE APIs ====

    /** Idempotente su chiave (giorno + HH:mm a 10'). Scrive payload “sparse” (no campi null). */
    fun pushOfficeSnapshot(roomKey: String, dayBucket: String, tenMinKey: String, snapshot: OfficeSnapshot) {
        val payload = snapshot.toMapFiltered()
        Log.i(TAG, "SET /office/$roomKey/snapshots/$dayBucket/$tenMinKey → $payload")
        officeSnapshotRef(roomKey, dayBucket, tenMinKey).setValue(payload)
    }

    /** Scrive un riepilogo orario per la presa indicata. */
    fun setPlugHourlySummary(
        plugName: String,
        hourBucket: String,
        countAbove5: Int,
        commitTsMs: Long = System.currentTimeMillis()
    ) {
        val payload = PlugHourlySummary(
            hour_bucket = hourBucket,
            timestamp_end_ms = commitTsMs,
            count_above_5 = countAbove5
        )
        Log.i(TAG, "SET /plugs/$plugName/hourly_summary/$hourBucket = $payload")
        plugHourlySummaryRef(plugName, hourBucket).setValue(payload)
    }

    // ==== RETENTION (client-side) ====

    /**
     * Rimuove i rami /office/{room}/snapshots/{day} più vecchi di keepDays.
     * Esempio: keepDays=90 → conserva ~3 mesi.
     * NB: confronta la key YYYY-MM-DD in ordine lessicografico.
     */
    fun cleanupOldOfficeDays(roomKey: String, keepDays: Int) {
        if (keepDays <= 0) return

        val cutoffCal = Calendar.getInstance(TZ).apply { add(Calendar.DAY_OF_YEAR, -keepDays) }
        val cutoffDay = String.format(
            "%04d-%02d-%02d",
            cutoffCal.get(Calendar.YEAR),
            cutoffCal.get(Calendar.MONTH) + 1,
            cutoffCal.get(Calendar.DAY_OF_MONTH)
        )

        val ref = db.getReference("office").child(roomKey).child("snapshots")
        ref.get().addOnSuccessListener { snap ->
            var removed = 0
            for (child in snap.children) {
                val dayKey = child.key ?: continue
                if (dayKey < cutoffDay) {
                    child.ref.removeValue()
                    removed++
                }
            }
            if (removed > 0) {
                Log.i(TAG, "cleanup [$roomKey]: rimossi $removed giorni (< $cutoffDay)")
            } else {
                Log.d(TAG, "cleanup [$roomKey]: nulla da rimuovere (cutoff=$cutoffDay)")
            }
        }.addOnFailureListener {
            Log.e(TAG, "cleanup [$roomKey] FAILED: ${it.message}", it)
        }
    }
}
