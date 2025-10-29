// file: app/src/main/java/com/example/vitality/ui/dashboard/components/DashboardComponents.kt
package com.example.vitality.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitality.data.HistoricalItem
import com.example.vitality.data.SmartPlugStatus
import com.example.vitality.model.Zone
import com.example.vitality.viewmodel.TemperatureViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun GridMetrics(metrics: List<Triple<String, String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        metrics.forEach { (icon, label, value) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$icon  $label", fontSize = 14.sp)
                Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Card completa della zona con parametri ambientali + storico T/RH + slot extra sotto (per sPMV)
 */
@Composable
fun RoomDashboardCard(
    zone: Zone,
    temperatureViewModel: TemperatureViewModel,
    history: List<HistoricalItem>,
    plugs: List<SmartPlugStatus>,
    loading: Boolean,
    tempError: String?,
    plugError: String?,
    extraBelowHistory: (@Composable ColumnScope.() -> Unit) = {}   // slot per grafico sPMV giornaliero
) {
    val scrollState = rememberScrollState()

    val temperature by temperatureViewModel.temperature.collectAsState()
    val humidity by temperatureViewModel.humidity.collectAsState()
    val co2 by temperatureViewModel.co2.collectAsState()
    val voc by temperatureViewModel.voc.collectAsState()
    val iaq by temperatureViewModel.iaq.collectAsState()
    val illumination by temperatureViewModel.illumination.collectAsState()
    val sound by temperatureViewModel.sound.collectAsState()
    val airQuality by temperatureViewModel.airQualityScore.collectAsState()

    val spmv by temperatureViewModel.spmv.collectAsState()          // ← realtime sPMV & CLO
    val tOut by temperatureViewModel.externalTemp.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = zone.name,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        when {
            loading -> {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Aggiornamento dati in corso...", color = Color.Gray, fontSize = 13.sp)
            }
            tempError != null || plugError != null -> {
                tempError?.let { Text("⚠️ Errore sensori: $it", color = Color.Red) }
                plugError?.let { Text("⚠️ Errore prese: $it", color = Color.Red) }
            }
            else -> {
                SectionTitle("🌍 Parametri Ambientali")
                GridMetrics(
                    listOf(
                        Triple("🌡️", "Temperatura", temperature?.let { "%.1f °C".format(it) } ?: "N/D"),
                        Triple("💧", "Umidità", humidity?.let { "%.0f %%".format(it) } ?: "N/D"),
                        Triple("🔥", "CO₂", co2?.let { "%.0f ppm".format(it) } ?: "N/D"),
                        Triple("🧪", "VOC", voc?.let { "%.0f ppb".format(it) } ?: "N/D"),
                        Triple("💡", "Luce", illumination?.let { "%.0f lx".format(it) } ?: "N/D"),
                        Triple("🔊", "Rumore", sound?.let { "%.1f dB".format(it) } ?: "N/D"),
                        Triple("📊", "IAQ Index", iaq?.let { "%.0f".format(it) } ?: "N/D"),
                        Triple("🌿", "Air Quality", airQuality?.let { "%.0f".format(it) } ?: "N/D"),
                        Triple("🌬️", "T esterna (Ancona)", tOut?.let { "%.1f °C".format(it) } ?: "—")
                    )
                )

                SectionTitle("📈 Storico ultime 6 ore")
                if (history.isNotEmpty()) {
                    TemperatureChart(
                        data = history,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    HumidityChart(
                        data = history,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )

                    // ====== NUOVO BLOCCO: KPI realtime sPMV & CLO (subito sotto T/RH) ======
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("🎯 Comfort istantaneo")
                    SpmvRealtimeRow(
                        pmv  = spmv.pmv,
                        pmv2 = spmv.pmv2,
                        pmv3 = spmv.pmv3,
                        clo  = spmv.cloPred
                    )

                    // ============= Slot extra (grafico a barre SPMV giornaliero) ============
                    Spacer(Modifier.height(8.dp))
                    extraBelowHistory()
                } else {
                    Text(
                        "Nessun dato storico disponibile",
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SectionTitle("⚡ Prese Shelly")
                if (plugs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SmartPlugAccordionList(plugs = plugs)
                } else {
                    Text("Nessuna presa attiva", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
    }
}

/* -------------------- sPMV realtime row (tiles) -------------------- */

@Composable
private fun SpmvRealtimeRow(
    pmv: Double?,
    pmv2: Double?,
    pmv3: Double?,
    clo: Double?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatTile(
            title = "sPMV",
            value = fmtSignedNullable(pmv),
            bandColor = bandColor(pmv),
            modifier = Modifier.weight(1f)  // <-- weight applicato QUI (RowScope)
        )
        StatTile(
            title = "sPMV₂",
            value = fmtSignedNullable(pmv2),
            bandColor = bandColor(pmv2),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            title = "sPMV₃",
            value = fmtSignedNullable(pmv3),
            bandColor = bandColor(pmv3),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            title = "CLO pred",
            value = formatNullable(clo, "%.2f"),
            bandColor = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    bandColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,               // <-- niente weight qui dentro
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(22.dp)
                        .background(bandColor, shape = MaterialTheme.shapes.extraSmall)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@Composable
private fun bandColor(v: Double?): Color = when {
    v == null || !v.isFinite() -> MaterialTheme.colorScheme.outlineVariant
    kotlin.math.abs(v) <= 0.5   -> Color(0xFF2E7D32)   // comfort
    kotlin.math.abs(v) <= 1.0   -> Color(0xFFF9A825)   // marginale
    else                        -> Color(0xFFC62828)   // discomfort
}


/* ---------- PRESE: Accordion / Dettaglio ---------- */

@Composable
private fun SmartPlugAccordionList(plugs: List<SmartPlugStatus>) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plugs.forEach { plug ->
            val isOpen = expanded[plug.id] == true
            val rotation by animateFloatAsState(if (isOpen) 180f else 0f, label = "arrow-rotate")

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                // Header sintetico
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(online = plug.online)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = plug.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "P = ${"%.1f".format(plug.apower)} W   •   V = ${fmtZeroDash(plug.voltage, "%.0f V")}   •   I = ${fmtZeroDash(plug.current, "%.2f A")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray
                        )
                    }
                    IconButton(onClick = { expanded[plug.id] = !isOpen }) {
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (isOpen) "Chiudi" else "Apri",
                            modifier = Modifier.rotate(rotation)
                        )
                    }
                }

                AnimatedVisibility(visible = isOpen) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Relay/Fault
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Relay ${if (plug.output == true) "ON" else "OFF"}") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (plug.output == true)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else
                                        MaterialTheme.colorScheme.surface
                                )
                            )
                            if (plug.overpower == true) WarningChip("Overpower")
                            if (plug.overtemperature == true) WarningChip("Overtemp")
                            plug.errors?.takeIf { it.isNotEmpty() }?.let {
                                WarningChip("Errors: ${it.size}")
                            }
                        }

                        // Misure elettriche
                        val measures: List<Pair<String, String>> = listOf(
                            "Power factor" to (plug.pf?.let { "%.2f".format(it) } ?: "—"),
                            "Freq" to (plug.freq?.let { "%.1f Hz".format(it) } ?: "—"),
                            "Energia (tot.)" to (if (!plug.aenergyTotal.isNaN())
                                "%.2f Wh".format(plug.aenergyTotal) else "—"),
                            "Energia/min (avg/last)" to (plug.aenergyByMinute?.let { list ->
                                if (list.isNotEmpty()) {
                                    val last = list.last()
                                    val avg = list.average()
                                    "%.2f / %.2f Wh".format(avg, last)
                                } else "—"
                            } ?: "—")
                        )
                        KeyValueGrid(items = measures)

                        // Rete/Cloud
                        val net: List<Pair<String, String>> = listOf(
                            "SSID" to (plug.ssid.ifEmpty { "—" }),
                            "RSSI" to (if (plug.rssi != 0) "${plug.rssi} dBm" else "—"),
                            "IP" to (plug.ip ?: "—"),
                            "Wi-Fi" to yn(plug.wifiConnected),
                            "Cloud" to yn(plug.cloudConnected),
                            "MQTT" to yn(plug.mqttConnected)
                        )
                        KeyValueGrid(items = net)

                        // Device / FW
                        val dev: List<Pair<String, String>> = listOf(
                            "Model" to (plug.model ?: "—"),
                            "FW" to (plug.fw ?: "—"),
                            "FW id" to (plug.fwId ?: "—"),
                            "MAC" to (plug.mac ?: "—"),
                            "Uptime" to (plug.uptimeSec?.let { fmtUptime(it) } ?: "—"),
                            "Aggiornato" to fmtTimestamp(plug.fetchedAt)
                        )
                        KeyValueGrid(items = dev)

                        plug.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "Error log:",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                                errs.forEach { e ->
                                    Text("• $e", style = MaterialTheme.typography.labelMedium, color = Color.Red)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------- UI helpers prese ---------- */

@Composable
private fun StatusDot(online: Boolean) {
    val color = if (online) Color(0xFF2E7D32) else Color(0xFFC62828)
    Box(
        modifier = Modifier
            .size(10.dp)
            .padding(top = 2.dp)
            .background(color = color, shape = MaterialTheme.shapes.extraSmall)
    )
}

@Composable
private fun WarningChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    )
}

@Composable
private fun KeyValueGrid(items: List<Pair<String, String>>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { (k, v) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/* ---------- formatter utils ---------- */

private fun yn(b: Boolean?): String = when (b) {
    true -> "Yes"
    false -> "No"
    null -> "—"
}

private fun fmtZeroDash(v: Double, fmt: String): String =
    if (v.isNaN() || abs(v) < 1e-9) "—" else fmt.format(v)

private fun fmtUptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3_600
    val m = (seconds % 3_600) / 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (h > 0 || d > 0) append("${h}h ")
        append("${m}m")
    }.trim()
}

private fun fmtTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ms))
}

private fun fmtSigned(v: Double): String =
    (if (v >= 0) "+%.2f" else "%.2f").format(v)

/* ——— helpers nullable per i KPI ——— */
private fun formatNullable(v: Double?, fmt: String): String =
    v?.let { if (it.isFinite()) fmt.format(it) else "—" } ?: "—"

private fun fmtSignedNullable(v: Double?): String =
    v?.let { if (it.isFinite()) fmtSigned(it) else "—" } ?: "—"
