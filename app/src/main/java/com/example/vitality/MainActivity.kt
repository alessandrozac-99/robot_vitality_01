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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.example.vitality.service.VitalityAggregationService
import com.example.vitality.service.ComfortCoachService
import com.example.vitality.service.NicoleHeaterControlService
import com.example.vitality.ui.dashboard.DashboardScreen
import com.example.vitality.ui.map.TemiMapViewModel
import com.example.vitality.ui.theme.VitalityAppTheme
import com.example.vitality.viewmodel.ComfortDayViewModel
import com.example.vitality.viewmodel.SmartPlugViewModel
import com.example.vitality.viewmodel.TemperatureViewModel
import com.example.vitality.util.AlarmScheduler
import java.util.*

class MainActivity : ComponentActivity() {

    private val DEBUG_ALWAYS_ON = true

    private val mapVM: TemiMapViewModel by viewModels()
    private val tempVM: TemperatureViewModel by viewModels()
    private val smartPlugVM: SmartPlugViewModel by viewModels()
    private val comfortDayVM: ComfortDayViewModel by viewModels()

    // ---------------------------------------------------------------
    // PERMESSO NOTIFICHE (obbligatorio per servizi foreground)
    // ---------------------------------------------------------------
    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.e("MAIN", "📌 Permesso notifiche OK → avvio servizi")
                startAllServicesSafely()
            } else {
                Log.e("MAIN", "❌ Permesso notifiche NEGATO → servizi non partiranno")
            }
        }

    private val ignoreBatteryOptimLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d("MAIN", "⚡ Richiesta battery optimization completata")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.e("MAIN", "🔥 MainActivity.onCreate()")

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

        ensureExactAlarmPermissionAndSchedule()

        maybeAskNotificationPermissionAndStartServices()

        maybeRequestIgnoreBatteryOptim()
    }

    // ---------------------------------------------------------------
    // Finestra di attivazione (ma con override DEBUG)
    // ---------------------------------------------------------------
    private fun isWorkingHours(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (DEBUG_ALWAYS_ON) return true

        val tz = TimeZone.getTimeZone("Europe/Rome")
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        return cal.get(Calendar.HOUR_OF_DAY) in 8..19
    }

    // ---------------------------------------------------------------
    // Notifiche + avvio servizi
    // ---------------------------------------------------------------
    private fun maybeAskNotificationPermissionAndStartServices() {

        Log.e("MAIN", "⏱ isWorkingHours = ${isWorkingHours()}")

        if (!isWorkingHours()) {
            Log.w("MAIN", "⛔ Fuori orario → servizi NON avviati")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                Log.w("MAIN", "🔔 Richiedo permesso notifiche…")
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startAllServicesSafely()
    }

    // ---------------------------------------------------------------
    // Avvio tutti i servizi
    // ---------------------------------------------------------------
    private fun startAllServicesSafely() {
        startAggregationServiceSafely()
        startComfortCoachServiceSafely()
        startNicoleHeaterServiceSafely()
    }

    private fun startAggregationServiceSafely() {
        runCatching {
            Log.e("MAIN", "📡 Start VitalityAggregationService")
            startForegroundService(
                this,
                Intent(this, VitalityAggregationService::class.java)
            )
        }.onFailure { it.printStackTrace() }
    }

    private fun startComfortCoachServiceSafely() {
        runCatching {
            Log.e("MAIN", "🤖 Start ComfortCoachService")
            startForegroundService(
                this,
                Intent(this, ComfortCoachService::class.java)
            )
        }.onFailure { it.printStackTrace() }
    }

    private fun startNicoleHeaterServiceSafely() {
        runCatching {
            Log.e("MAIN", "🔥 Start NicoleHeaterControlService")
            startForegroundService(
                this,
                Intent(this, NicoleHeaterControlService::class.java)
            )
        }.onFailure { it.printStackTrace() }
    }

    // ---------------------------------------------------------------
    // Exact alarms
    // ---------------------------------------------------------------
    private fun ensureExactAlarmPermissionAndSchedule() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {

                Log.w("MAIN", "⏰ Exact alarms non permessi → chiedo permesso")
                val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))

                runCatching { startActivity(i) }
                return
            }
        }

        Log.e("MAIN", "⏰ Programmo start/stop giornaliero")
        AlarmScheduler.scheduleNextDailyStart(this)
        AlarmScheduler.scheduleNextDailyStop(this)
    }

    // ---------------------------------------------------------------
    // Battery optimization
    // ---------------------------------------------------------------
    private fun maybeRequestIgnoreBatteryOptim() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val pm = getSystemService(PowerManager::class.java)
        val pkg = packageName

        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            Log.w("MAIN", "⚡ Battery optimization attiva → chiedo esclusione")

            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$pkg"))

            ignoreBatteryOptimLauncher.launch(intent)
        }
    }
}
