package com.example.vitality.ui.dashboard

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vitality.model.Zone
import com.example.vitality.ui.dashboard.components.*
import com.example.vitality.ui.map.TemiMapCanvas
import com.example.vitality.ui.map.TemiMapViewModel
import com.example.vitality.viewmodel.ComfortDayViewModel
import com.example.vitality.viewmodel.SmartPlugViewModel
import com.example.vitality.viewmodel.TemperatureViewModel
import java.text.SimpleDateFormat
import java.util.*
import com.example.vitality.ui.dashboard.components.SpmvLegend
import androidx.compose.ui.unit.Dp
import com.example.vitality.ui.map.MapInfo
import androidx.compose.ui.graphics.ImageBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    zones: List<Zone>,
    mapBitmap: Bitmap?,
    mapViewModel: TemiMapViewModel,
    temperatureViewModel: TemperatureViewModel,
    smartPlugViewModel: SmartPlugViewModel,
    comfortDayViewModel: ComfortDayViewModel = viewModel()
) {
    var selectedZone by remember { mutableStateOf<Zone?>(null) }

    LaunchedEffect(zones) {
        if (selectedZone == null && zones.isNotEmpty()) {
            selectedZone = zones.first()
        }
    }

    var selectedDay by remember { mutableStateOf(todayKey()) }
    var showPicker by remember { mutableStateOf(false) }

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = dayKeyToEpochMs(selectedDay),
        yearRange = 2024..2030
    )

    val daySeries by comfortDayViewModel.daySeries.collectAsState()

    val spmvPoints = daySeries.mapNotNull { it.spmv?.let { v -> it.timestamp to v.toFloat() } }
    val tempPoints = daySeries.mapNotNull { it.tAmb?.let { v -> it.timestamp to v.toFloat() } }
    val humPoints = daySeries.mapNotNull { it.rh?.let { v -> it.timestamp to v.toFloat() } }

    val temp by temperatureViewModel.temperature.collectAsState()
    val hum by temperatureViewModel.humidity.collectAsState()
    val co2 by temperatureViewModel.co2.collectAsState()
    val voc by temperatureViewModel.voc.collectAsState()
    val iaq by temperatureViewModel.iaq.collectAsState()
    val ill by temperatureViewModel.illumination.collectAsState()
    val snd by temperatureViewModel.sound.collectAsState()
    val extT by temperatureViewModel.externalTemp.collectAsState()
    val airScore by temperatureViewModel.airQualityScore.collectAsState()

    val spmvLive by temperatureViewModel.spmv.collectAsState()
    val plugs by smartPlugViewModel.smartPlugs.collectAsState()

    val mapInfo by mapViewModel.mapInfo.collectAsState()
    val overlay by mapViewModel.tintOverlay.collectAsState()
    val heatmap by mapViewModel.heatmapOverlay.collectAsState()
    val poi by mapViewModel.poi.collectAsState()

    // --- CORREZIONE CRUCIALE ---
    LaunchedEffect(selectedZone?.name) {
        selectedZone?.let { zone ->
            smartPlugViewModel.loadPlugsForRoom(zone.name, 60_000)
            temperatureViewModel.loadDataForZone(zone.name)
        }
    }

    LaunchedEffect(selectedZone?.name, selectedDay) {
        selectedZone?.let { zone ->
            comfortDayViewModel.observeRoomDay(zone.name, selectedDay)
        }
    }
    // ----------------------------

    val isWide = LocalConfiguration.current.screenWidthDp > 900

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Vitality Dashboard") })
        }
    ) { padding ->

        if (isWide) {
            Row(
                modifier = Modifier
                    .padding(padding)
                    .padding(12.dp)
                    .fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Column(Modifier.weight(1f)) {

                    AnimatedMapCard(
                        mapBitmap = mapBitmap,
                        zones = zones,
                        mapInfo = mapInfo,
                        overlay = overlay,
                        heatmap = heatmap,
                        poi = poi,
                        selectedZone = selectedZone,
                        onZoneSelected = { name ->
                            selectedZone = zones.find { it.name == name }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    SpmvLegend()

                    Spacer(Modifier.height(12.dp))

                    AnimatedZoneSelector(
                        zones = zones,
                        selectedZone = selectedZone,
                        onSelect = { selectedZone = it }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {

                    selectedZone?.let {

                        AnimatedCardFadeSlide {
                            EnvironmentCard(
                                temperature = temp,
                                humidity = hum,
                                co2 = co2,
                                voc = voc,
                                iaq = iaq,
                                illumination = ill,
                                sound = snd,
                                airQualityScore = airScore,
                                externalTemperature = extT
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        AnimatedCardFadeSlide {
                            SpmvLiveCard(spmvLive)
                        }

                        Spacer(Modifier.height(16.dp))

                        AnimatedCardFadeSlide {
                            SmartPlugLiveCard(plugs)
                        }

                        Spacer(Modifier.height(20.dp))

                        AnimatedCardFadeSlide {
                            DayPickerField(
                                dayKey = selectedDay,
                                onPick = { selectedDay = it },
                                showPicker = showPicker,
                                onShowPicker = { showPicker = it },
                                pickerState = pickerState
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        AnimatedCardFadeSlide {
                            ChartsCard(
                                spmv = spmvPoints,
                                temp = tempPoints,
                                hum = humPoints
                            )
                        }
                    }
                }
            }

        } else {

            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                AnimatedMapCard(
                    mapBitmap = mapBitmap,
                    zones = zones,
                    mapInfo = mapInfo,
                    overlay = overlay,
                    heatmap = heatmap,
                    poi = poi,
                    selectedZone = selectedZone,
                    onZoneSelected = { name ->
                        selectedZone = zones.find { it.name == name }
                    },
                    height = 260.dp
                )

                Spacer(Modifier.height(16.dp))

                selectedZone?.let {

                    AnimatedCardFadeSlide {
                        EnvironmentCard(
                            temperature = temp,
                            humidity = hum,
                            co2 = co2,
                            voc = voc,
                            iaq = iaq,
                            illumination = ill,
                            sound = snd,
                            airQualityScore = airScore,
                            externalTemperature = extT
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    AnimatedCardFadeSlide { SpmvLiveCard(spmvLive) }

                    Spacer(Modifier.height(16.dp))

                    AnimatedCardFadeSlide { SmartPlugLiveCard(plugs) }

                    Spacer(Modifier.height(16.dp))

                    AnimatedCardFadeSlide {
                        DayPickerField(
                            dayKey = selectedDay,
                            onPick = { selectedDay = it },
                            showPicker = showPicker,
                            onShowPicker = { showPicker = it },
                            pickerState = pickerState
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    AnimatedCardFadeSlide {
                        ChartsCard(
                            spmv = spmvPoints,
                            temp = tempPoints,
                            hum = humPoints
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedCardFadeSlide(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(400)) +
                slideInVertically(initialOffsetY = { it / 6 }),
    ) { content() }
}

// Increased map card height for better visibility
@Composable
fun AnimatedMapCard(
    mapBitmap: Bitmap?,
    zones: List<Zone>,
    mapInfo: MapInfo?,
    overlay: ImageBitmap?,
    heatmap: ImageBitmap?,
    poi: List<TemiMapViewModel.Poi>?,
    selectedZone: Zone?,
    height: Dp = 600.dp, // increased from ~420dp
    onZoneSelected: (String) -> Unit
) {
    val scale by animateFloatAsState(
        if (selectedZone != null) 1.02f else 1f,
        tween(450, easing = FastOutSlowInEasing),
        label = ""
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        mapBitmap?.let { bmp ->
            TemiMapCanvas(
                mapBitmap = bmp.asImageBitmap(),
                zones = zones,
                mapInfo = mapInfo,
                selectedZone = selectedZone?.name,
                onZoneSelected = onZoneSelected,
                tintOverlay = overlay,
                heatmapOverlay = heatmap,
                poi = poi ?: emptyList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
            )
        }
    }
}

@Composable
fun AnimatedZoneSelector(
    zones: List<Zone>,
    selectedZone: Zone?,
    onSelect: (Zone) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        zones.forEach { z ->
            AnimatedZoneChip(
                text = z.name,
                selected = selectedZone?.name == z.name,
                onClick = { onSelect(z) }
            )
        }
    }
}

private val tzRome = TimeZone.getTimeZone("Europe/Rome")
private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tzRome }

fun todayKey(): String = fmt.format(Date())
fun epochMsToDayKey(ms: Long): String = fmt.format(Date(ms))
fun dayKeyToEpochMs(key: String): Long {
    val c = Calendar.getInstance(tzRome)
    c.set(
        key.substring(0, 4).toInt(),
        key.substring(5, 7).toInt() - 1,
        key.substring(8).toInt(),
        0, 0, 0
    )
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}
