// file: ui/dashboard/components/TemperatureChart.kt
package com.example.vitality.ui.dashboard.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import ir.ehsannarmani.compose_charts.LineChart
import ir.ehsannarmani.compose_charts.models.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TemperatureChart(
    values: List<Pair<Long, Float>>,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return

    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val points = values.map {
        formatter.format(Date(it.first)) to it.second.toDouble()
    }.filter { it.second.isFinite() }

    if (points.isEmpty()) return

    val labels = points.map { it.first }
    val yValues = points.map { it.second }

    val (yMin, yMax) = run {
        val mn = yValues.minOrNull()!!
        val mx = yValues.maxOrNull()!!
        if (mn == mx) (mn - 0.2) to (mx + 0.2)
        else {
            val span = mx - mn
            (mn - 0.05 * span) to (mx + 0.05 * span)
        }
    }

    val line = listOf(
        Line(
            label = "Temperatura (°C)",
            values = yValues,
            color = SolidColor(Color(0xFFFF7043)),
            curvedEdges = true,
            firstGradientFillColor = Color(0xFFFFAB91).copy(alpha = 0.28f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 3.dp),
            popupProperties = PopupProperties(
                enabled = true,
                containerColor = Color(0xFFFF7043)
            )
        )
    )

    val scroll = rememberScrollState()
    val contentWidth = 42.dp * labels.size.coerceAtLeast(8)

    ChartCard(
        title = "Temperatura Ambiente",
        legend = { LegendItem(Color(0xFFFF7043), "T_amb (°C)") },
        modifier = modifier
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
        ) {
            LineChart(
                modifier = Modifier
                    .width(contentWidth)
                    .height(260.dp),
                data = line,
                curvedEdges = true,
                animationMode = AnimationMode.OneByOne,
                gridProperties = GridProperties(enabled = true),
                labelProperties = LabelProperties(
                    enabled = true,
                    labels = labels,
                    rotation = LabelProperties.Rotation(
                        mode = LabelProperties.Rotation.Mode.Force,
                        degree = -45f
                    )
                ),
                minValue = yMin,
                maxValue = yMax
            )
        }
    }
}
