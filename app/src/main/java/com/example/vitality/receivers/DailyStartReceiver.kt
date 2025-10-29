package com.example.vitality.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.vitality.service.VitalityAggregationService
import com.example.vitality.util.AlarmScheduler
import java.util.Calendar
import java.util.TimeZone

class DailyStartReceiver : BroadcastReceiver() {
    private val tz = TimeZone.getTimeZone("Europe/Rome")

    override fun onReceive(context: Context, intent: Intent) {
        // 1) ripianifica subito il prossimo 08:00 (one-shot giornaliero)
        AlarmScheduler.scheduleNextDailyStart(context)

        // 2) se siamo nella finestra 08–20 avvia il servizio in FGS (il servizio farà gating)
        val cal = Calendar.getInstance(tz)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        if (h in 8..19) {
            val svc = Intent(context, VitalityAggregationService::class.java)
            ContextCompat.startForegroundService(context, svc)
        }
    }
}
