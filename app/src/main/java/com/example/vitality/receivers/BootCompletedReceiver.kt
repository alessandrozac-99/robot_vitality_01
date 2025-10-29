package com.example.vitality.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.vitality.util.AlarmScheduler

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Ripianifica l’allarme quotidiano alle 08:00
        AlarmScheduler.scheduleNextDailyStart(context)
    }
}
