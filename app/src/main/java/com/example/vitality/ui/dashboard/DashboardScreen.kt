// file: app/src/main/java/com/example/vitality/ui/dashboard/DashboardScreen.kt
package com.example.vitality.ui.dashboard

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vitality.model.Zone
import com.example.vitality.ui.dashboard.components.RoomDashboardCard
import com.example.vitality.ui.map.TemiMapCanvas
import com.example.vitality.ui.map.TemiMapViewModel
import com.example.vitality.viewmodel.HistoryViewModel
import com.example.vitality.viewmodel.SmartPlugViewModel
import com.example.vitality.viewmodel.TemperatureViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// === NEW: chart imports ===
import com.example.vitality.ui.dashboard.components.SpmvBarChart       // uses ColumnChart
import com.example.vitality.ui.dashboard.components.SpmvChartViewModel // observes /office/{room}/snapshots/{day}
import com.example.vitality.data.firebase.FirebaseRepository
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import com.example.vitality.ui.dashboard.components.SectionTitle


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    zones: List<Zone>,
    mapBitmap: android.graphics.Bitmap?,
    mapViewModel: TemiMapViewModel,
    temperatureViewModel: TemperatureViewModel,
    historyViewModel: HistoryViewModel,
    smartPlugViewModel: SmartPlugViewModel
) {
    var selectedZone by remember { mutableStateOf<Zone?>(null) }

    LaunchedEffect(zones) {
        if (selectedZone == null && zones.isNotEmpty()) selectedZone = zones.first()
    }

    val history by historyViewModel.history.collectAsState()
    val plugs by smartPlugViewModel.smartPlugs.collectAsState()
    val loadingHistory by historyViewModel.loading.collectAsState()
    val loadingPlugs by smartPlugViewModel.loading.collectAsState()
    val loadingTemp by temperatureViewModel.loading.collectAsState()
    val tempError by temperatureViewModel.error.collectAsState()
    val plugError by smartPlugViewModel.error.collectAsState()

    val mapInfo by mapViewModel.mapInfo.collectAsState()
    val overlay by mapViewModel.tintOverlay.collectAsState()

    val temp by temperatureViewModel.temperature.collectAsState()
    val hum by temperatureViewModel.humidity.collectAsState()
    val extT by temperatureViewModel.externalTemp.collectAsState()

    val combinedLoading = loadingTemp || loadingPlugs || loadingHistory
    val isWideScreen = LocalConfiguration.current.screenWidthDp > 900

    // === NEW: SPMV chart VM & selected day ===
    val spmvVm: SpmvChartViewModel = viewModel()
    val spmvSeries by spmvVm.spmvSeries.collectAsState()

    // Day in formato YYYY-MM-DD; default = oggi (Europe/Rome)
    var selectedDay by remember { mutableStateOf(FirebaseRepository.dayBucketId()) }

    // DatePicker dialog state
    var showPicker by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = dayKeyToEpochMs(selectedDay),
        yearRange = IntRange(2024, 2030)
    )

    // Cambio stanza → reset + hook sPMV per day corrente
    LaunchedEffect(selectedZone?.name) {
        val zone = selectedZone ?: return@LaunchedEffect
        Log.i("DashboardScreen", "📍 Cambio zona → ${zone.name}")

        historyViewModel.clearHistory()
        temperatureViewModel.clearValues()
        smartPlugViewModel.clear()

        coroutineScope {
            launch { temperatureViewModel.loadDataForZone(zone.name) }
            launch { historyViewModel.loadHistoryForRoom(zone.name) }
            launch { smartPlugViewModel.loadPlugsForRoom(zone.name, pollEveryMs = 60_000L) }
        }

        mapViewModel.refreshAllSpmv()

        spmvVm.observeRoomDay(zoneKey(zone.name), selectedDay)
    }

    // Cambio giorno → ricollega la sorgente sPMV
    LaunchedEffect(selectedDay, selectedZone?.name) {
        selectedZone?.let { spmvVm.observeRoomDay(zoneKey(it.name), selectedDay) }
    }

    // Aggiorna cache sPMV in mappa quando T/RH/Tout disponibili
    LaunchedEffect(selectedZone?.name, temp, hum, extT) {
        val zone = selectedZone ?: return@LaunchedEffect
        val t = temp ?: return@LaunchedEffect
        val h = hum ?: return@LaunchedEffect
        val te = extT ?: return@LaunchedEffect
        mapViewModel.submitZoneEnv(zone.name, t, h, te, null, null)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Vitality Dashboard", style = MaterialTheme.typography.titleLarge) }
            )
        }
    ) { paddingValues ->

        if (isWideScreen) {
            Row(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    mapBitmap?.let { bmp ->
                        TemiMapCanvas(
                            mapBitmap = bmp.asImageBitmap(),
                            zones = zones,
                            mapInfo = mapInfo,
                            selectedZone = selectedZone?.name,
                            onZoneSelected = { zoneName ->
                                selectedZone = zones.find { it.name == zoneName }
                                Log.i("DashboardScreen", "🖱️ Zona selezionata → $zoneName")
                            },
                            tintOverlay = overlay,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 8.dp)
                        )
                    }

                    if (zones.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            zones.forEach { zone ->
                                FilterChip(
                                    selected = selectedZone?.name == zone.name,
                                    onClick = { selectedZone = zone },
                                    label = { Text(zone.name) },
                                    modifier = Modifier.height(36.dp)
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    selectedZone?.let { zone ->
                        RoomDashboardCard(
                            zone = zone,
                            temperatureViewModel = temperatureViewModel,
                            history = history,
                            plugs = plugs,
                            loading = combinedLoading,
                            tempError = tempError,
                            plugError = plugError,
                            // === NEW: slot sotto T/RH con calendario + grafico sPMV ===
                            extraBelowHistory = {
                                SectionTitle("🧑‍🔬 Comfort – sPMV (giorno)")
                                DayCalendarField(
                                    dayKey = selectedDay,
                                    onPick = { newDay ->
                                        selectedDay = newDay
                                    },
                                    showPicker = showPicker,
                                    onShowPicker = { showPicker = it },
                                    pickerState = pickerState
                                )
                                Spacer(Modifier.height(8.dp))
                                SpmvBarChart(
                                    spmvSeries = spmvSeries,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        )
                    } ?: NoZoneSelectedMessage()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                mapBitmap?.let { bmp ->
                    TemiMapCanvas(
                        mapBitmap = bmp.asImageBitmap(),
                        zones = zones,
                        mapInfo = mapInfo,
                        selectedZone = selectedZone?.name,
                        onZoneSelected = { zoneName ->
                            selectedZone = zones.find { it.name == zoneName }
                            Log.i("DashboardScreen", "🖱️ Zona selezionata → $zoneName")
                        },
                        tintOverlay = overlay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .padding(8.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                selectedZone?.let { zone ->
                    RoomDashboardCard(
                        zone = zone,
                        temperatureViewModel = temperatureViewModel,
                        history = history,
                        plugs = plugs,
                        loading = combinedLoading,
                        tempError = tempError,
                        plugError = plugError,
                        extraBelowHistory = {
                            SectionTitle("🧑‍🔬 Comfort – sPMV (giorno)")
                            DayCalendarField(
                                dayKey = selectedDay,
                                onPick = { newDay -> selectedDay = newDay },
                                showPicker = showPicker,
                                onShowPicker = { showPicker = it },
                                pickerState = pickerState
                            )
                            Spacer(Modifier.height(8.dp))
                            SpmvBarChart(
                                spmvSeries = spmvSeries,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    )
                } ?: NoZoneSelectedMessage()
            }
        }
    }
}

@Composable
private fun NoZoneSelectedMessage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Seleziona una zona sulla mappa per visualizzare i dati",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            modifier = Modifier.width(160.dp),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

/* ======================= Calendar day picker ======================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayCalendarField(
    dayKey: String,                                // "YYYY-MM-DD"
    onPick: (String) -> Unit,
    showPicker: Boolean,
    onShowPicker: (Boolean) -> Unit,
    pickerState: DatePickerState
) {
    // Campo readonly che apre il DatePickerDialog
    OutlinedTextField(
        value = dayKey,
        onValueChange = {},
        label = { Text("Giorno") },
        readOnly = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = { onShowPicker(true) }) { Text("Seleziona") }
        }
    )

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { onShowPicker(false) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ms = pickerState.selectedDateMillis
                        if (ms != null) onPick(epochMsToDayKey(ms))
                        onShowPicker(false)
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { onShowPicker(false) }) { Text("Annulla") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/* ======================= helpers date & keys ======================= */

private val TZ_ROME: TimeZone = TimeZone.getTimeZone("Europe/Rome")
private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
    timeZone = TZ_ROME
}
private fun epochMsToDayKey(ms: Long): String = dayFmt.format(ms)

private fun dayKeyToEpochMs(dayKey: String): Long {
    // parse "YYYY-MM-DD" → ms at 00:00 local Europe/Rome
    val cal = Calendar.getInstance(TZ_ROME).apply {
        val y = dayKey.substring(0, 4).toInt()
        val m = dayKey.substring(5, 7).toInt() - 1
        val d = dayKey.substring(8, 10).toInt()
        set(Calendar.YEAR, y)
        set(Calendar.MONTH, m)
        set(Calendar.DAY_OF_MONTH, d)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/** Mappatura eventuale display→key; per ora 1:1 */
private fun zoneKey(displayName: String): String = displayName
