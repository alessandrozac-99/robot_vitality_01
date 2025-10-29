// file: app/src/main/java/com/example/vitality/receivers/StopAggregationReceiver.kt
package com.example.vitality.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.vitality.util.AlarmScheduler
import com.example.vitality.service.VitalityAggregationService

class StopAggregationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Ferma il service alle 20:00
        context.stopService(Intent(context, VitalityAggregationService::class.java))
        // (ri)pianifica il prossimo ciclo (facoltativo, ma comodo se vuoi continuità)
        AlarmScheduler.scheduleNextDailyStart(context)
        AlarmScheduler.scheduleNextDailyStop(context)
    }
}
