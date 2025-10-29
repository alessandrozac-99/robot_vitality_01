package com.example.vitality

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import com.example.vitality.service.VitalityAggregationService
import com.example.vitality.ui.dashboard.DashboardScreen
import com.example.vitality.ui.map.TemiMapViewModel
import com.example.vitality.ui.theme.VitalityAppTheme
import com.example.vitality.viewmodel.HistoryViewModel
import com.example.vitality.viewmodel.SmartPlugViewModel
import com.example.vitality.viewmodel.TemperatureViewModel
import com.example.vitality.util.AlarmScheduler
import java.util.Calendar
import java.util.TimeZone
import android.app.AlarmManager

class MainActivity : ComponentActivity() {

    private val mapVM: TemiMapViewModel by viewModels()
    private val historyVM: HistoryViewModel by viewModels()
    private val tempVM: TemperatureViewModel by viewModels()
    private val smartPlugVM: SmartPlugViewModel by viewModels()

    // Launcher permesso notifiche (Android 13+)
    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (granted) {
                // Avvio solo se siamo in orario di lavoro
                if (isWorkingHours()) startAggregationServiceSafely()
            } else {
                // opzionale: snackbar/UX per spiegare perché serve il permesso
            }
        } else {
            if (isWorkingHours()) startAggregationServiceSafely()
        }
        // In ogni caso prova a schedulare gli allarmi (non richiede POST_NOTIFICATIONS)
        ensureExactAlarmPermissionAndSchedule()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Avvia il service solo in orario 08–20 e se permesso notifiche è concesso (33+)
        maybeAskNotificationPermissionAndStartService()

        // 2) SCHEDULING AUTOMATICO START/STOP (08:00 / 20:00) con allarmi esatti
        ensureExactAlarmPermissionAndSchedule()

        // 3) UI
        setContent {
            VitalityAppTheme {
                DashboardScreen(
                    zones = mapVM.zones.collectAsState().value,
                    mapBitmap = mapVM.mapBitmap.collectAsState().value?.asAndroidBitmap(),
                    mapViewModel = mapVM,
                    temperatureViewModel = tempVM,
                    historyViewModel = historyVM,
                    smartPlugViewModel = smartPlugVM
                )
            }
        }
    }

    // ==== Working hours helper ====
    private fun isWorkingHours(nowMs: Long = System.currentTimeMillis()): Boolean {
        val tz = TimeZone.getTimeZone("Europe/Rome")
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return h in 8..19 // 08:00–19:59
    }

    // ==== Notifiche + avvio service ====
    private fun maybeAskNotificationPermissionAndStartService() {
        // Fuori orario → non avviare il foreground service (l’app resta usabile)
        if (!isWorkingHours()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotifPerm) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // Se <33 o già concesso ⇒ avvia direttamente
        startAggregationServiceSafely()
    }

    private fun startAggregationServiceSafely() {
        runCatching {
            val intent = Intent(this, VitalityAggregationService::class.java)
            startForegroundService(this, intent)
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ==== Exact alarm permission (Android 12+) + scheduling automatico ====
    private fun ensureExactAlarmPermissionAndSchedule() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                // Mostra la schermata di sistema per consentire gli allarmi esatti
                val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))
                // Non c'è callback: l'utente tornerà all'app e potrai richiamare this per schedulare
                runCatching { startActivity(i) }
                return
            }
        }
        // Se siamo qui, possiamo schedulare
        AlarmScheduler.scheduleNextDailyStart(this) // 08:00 Europe/Rome
        AlarmScheduler.scheduleNextDailyStop(this)  // 20:00 Europe/Rome
    }
}
