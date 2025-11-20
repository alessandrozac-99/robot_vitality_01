package com.example.vitality

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.example.vitality.service.VitalityAggregationService
import com.example.vitality.ui.dashboard.DashboardScreen
import com.example.vitality.ui.map.TemiMapViewModel
import com.example.vitality.ui.theme.VitalityAppTheme
import com.example.vitality.viewmodel.ComfortDayViewModel
import com.example.vitality.viewmodel.SmartPlugViewModel
import com.example.vitality.viewmodel.TemperatureViewModel
import com.example.vitality.util.AlarmScheduler
import java.util.*

class MainActivity : ComponentActivity() {

    private val mapVM: TemiMapViewModel by viewModels()
    private val tempVM: TemperatureViewModel by viewModels()
    private val smartPlugVM: SmartPlugViewModel by viewModels()
    private val comfortDayVM: ComfortDayViewModel by viewModels()

    // ==================================================================================
    // RICHIESTA PERMESSO NOTIFICHE (Android 13+)
    // ==================================================================================
    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startAggregationServiceSafely()
            }
        }

    // ==================================================================================
    // RICHIESTA DISABILITAZIONE BATTERY-OPTIMIZATION
    // ==================================================================================
    private val ignoreBatteryOptimLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Non serve gestire risposta
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ==================================================================================
        // UI
        // ==================================================================================
        setContent {
            VitalityAppTheme {
                val zones = mapVM.zones.collectAsState().value
                val bitmap = mapVM.mapBitmap.collectAsState().value?.asAndroidBitmap()

                DashboardScreen(
                    zones = zones,
                    mapBitmap = bitmap,
                    mapViewModel = mapVM,
                    temperatureViewModel = tempVM,
                    smartPlugViewModel = smartPlugVM,
                    comfortDayViewModel = comfortDayVM
                )
            }
        }

        // ==================================================================================
        // PROGRAMMAZIONE SERVIZIO (START/STOP giornaliero)
        // ==================================================================================
        ensureExactAlarmPermissionAndSchedule()

        // Avvia servizio solo in orario lavorativo
        maybeAskNotificationPermissionAndStartService()

        // Battery optimization OFF per garantire polling stabile
        maybeRequestIgnoreBatteryOptim()
    }

    // ==================================================================================
    // ORARIO LAVORATIVO
    // ==================================================================================
    private fun isWorkingHours(nowMs: Long = System.currentTimeMillis()): Boolean {
        val tz = TimeZone.getTimeZone("Europe/Rome")
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        return cal.get(Calendar.HOUR_OF_DAY) in 8..19
    }

    // ==================================================================================
    // PERMESSO NOTIFICHE + START SERVIZIO
    // ==================================================================================
    private fun maybeAskNotificationPermissionAndStartService() {
        if (!isWorkingHours()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startAggregationServiceSafely()
    }

    // ==================================================================================
    // AVVIO SERVIZIO IN FOREGROUND (SICURO)
    // ==================================================================================
    private fun startAggregationServiceSafely() {
        runCatching {
            val intent = Intent(this, VitalityAggregationService::class.java)
            startForegroundService(this, intent)
        }.onFailure { e ->
            e.printStackTrace()
        }
    }

    // ==================================================================================
    // EXACT ALARMS (Android 12+)
    // ==================================================================================
    private fun ensureExactAlarmPermissionAndSchedule() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {

                val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))

                runCatching { startActivity(i) }
                return
            }
        }

        // PROGRAMMAZIONE “START/STOP” QUOTIDIANA
        AlarmScheduler.scheduleNextDailyStart(this)
        AlarmScheduler.scheduleNextDailyStop(this)
    }

    // ==================================================================================
    // BATTERY OPTIMIZATION
    // ==================================================================================
    private fun maybeRequestIgnoreBatteryOptim() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val pm = getSystemService(PowerManager::class.java)
        val pkg = packageName

        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$pkg"))

            runCatching { ignoreBatteryOptimLauncher.launch(intent) }
        }
    }
}
