// file: app/src/main/java/com/example/vitality/receivers/StartAggregationReceiver.kt
package com.example.vitality.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.vitality.service.VitalityAggregationService

class StartAggregationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Avvia il Foreground Service alle 08:00
        val i = Intent(context, VitalityAggregationService::class.java)
        ContextCompat.startForegroundService(context, i)
    }
}
