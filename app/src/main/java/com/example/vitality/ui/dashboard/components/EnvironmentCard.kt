package com.example.vitality.ui.dashboard.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============================================================
   CARD PARAMETRI AMBIENTALI — VERSIONE PROFESSIONALE
============================================================ */

@Composable
fun EnvironmentCard(
    temperature: Double?,
    humidity: Double?,
    co2: Double?,
    voc: Double?,
    iaq: Double?,
    illumination: Double?,
    sound: Double?,
    airQualityScore: Double?,
    externalTemperature: Double?
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(6.dp)
    ) {
        Column(Modifier.padding(20.dp)) {

            Text(
                " Parametri Ambientali",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            /* ---------------------------------------------------------
               GRID 2×4 — Layout professionale
            --------------------------------------------------------- */
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTile("Temperatura", temperature, "°C")
                    MetricTile("Umidità", humidity, "%")
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTile("CO₂", co2, "ppm")
                    MetricTile("VOC", voc, "ppb")
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTile("Illuminazione", illumination, "lx")
                    MetricTile("Rumore", sound, "dB")
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTile("IAQ", iaq, "")
                    MetricTile("T esterna", externalTemperature, "°C")
                }
            }
        }
    }
}

/* ============================================================
   TILE PROFESSIONALE — Value + Unit + Color semantics
============================================================ */

@Composable
private fun MetricTile(
    label: String,
    value: Double?,
    unit: String
) {
    // Versione per trigger animazioni: cambia SEMPRE quando arriva un nuovo valore
    val trigger = remember { mutableStateOf(0) }

    // Quando il valore cambia -> incrementa il trigger
    LaunchedEffect(value) {
        trigger.value++
    }

    val formatted = value?.let { "%.1f".format(it) } ?: "—"

    // Color semantics
    val color = when {
        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
        label == "Rumore" && value > 70 -> Color(0xFFD32F2F)
        label == "CO₂" && value > 1200 -> Color(0xFFD32F2F)
        label == "Umidità" && (value < 30 || value > 70) -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .width(150.dp)
            .padding(6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.Bottom) {

            /* ---------------------------------------------------------
               🔥 Animazione SEMPRE, non solo quando cambia stanza
            --------------------------------------------------------- */
            AnimatedContent(
                targetState = trigger.value to formatted,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInVertically { it / 4 }) togetherWith
                            (fadeOut(tween(200)) + slideOutVertically { -it / 4 })
                },
                label = ""
            ) { (_, txt) ->
                Text(
                    txt,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }

            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


