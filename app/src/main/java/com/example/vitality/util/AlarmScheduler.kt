// file: app/src/main/java/com/example/vitality/alarms/AlarmScheduler.kt
package com.example.vitality.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.example.vitality.receivers.StartAggregationReceiver
import com.example.vitality.receivers.StopAggregationReceiver
import java.util.Calendar
import java.util.TimeZone

object AlarmScheduler {

    private const val TZ_ID = "Europe/Rome"

    fun scheduleNextDailyStart(context: Context) {
        scheduleDaily(
            context = context,
            hour = 8, minute = 0,
            requestCode = 1008,
            intent = Intent(context, StartAggregationReceiver::class.java)
        )
    }

    fun scheduleNextDailyStop(context: Context) {
        scheduleDaily(
            context = context,
            hour = 20, minute = 0,
            requestCode = 1020,
            intent = Intent(context, StopAggregationReceiver::class.java)
        )
    }

    private fun scheduleDaily(
        context: Context,
        hour: Int,
        minute: Int,
        requestCode: Int,
        intent: Intent
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val tz = TimeZone.getTimeZone(TZ_ID)
        val cal = Calendar.getInstance(tz).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        // Se orario già passato oggi → domani
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val triggerAt = cal.timeInMillis
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canExact) {
            // Nessun errore qui: usiamo la compat di AndroidX
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                am, AlarmManager.RTC_WAKEUP, triggerAt, pi
            )
        } else {
            // Fallback meno preciso (accettabile se exact non disponibile)
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, /*windowLength*/ 60_000L, pi)
        }
    }
}
