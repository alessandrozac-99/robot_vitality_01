package com.example.vitality.ui.dashboard.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.example.vitality.data.HistoricalItem
import ir.ehsannarmani.compose_charts.LineChart
import ir.ehsannarmani.compose_charts.models.AnimationMode
import ir.ehsannarmani.compose_charts.models.DividerProperties
import ir.ehsannarmani.compose_charts.models.DrawStyle
import ir.ehsannarmani.compose_charts.models.GridProperties
import ir.ehsannarmani.compose_charts.models.LabelHelperProperties
import ir.ehsannarmani.compose_charts.models.LabelProperties
import ir.ehsannarmani.compose_charts.models.Line
import ir.ehsannarmani.compose_charts.models.PopupProperties
import ir.ehsannarmani.compose_charts.models.StrokeStyle
import ir.ehsannarmani.compose_charts.models.ZeroLineProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grafico Temperatura con asse X scrollabile e Y range auto dai dati
 */
@Composable
fun TemperatureChart(
    data: List<HistoricalItem>,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val points = remember(data) {
        data.map { item ->
            formatter.format(Date(item.timestamp)) to item.temperature.toDouble()
        }.filter { it.second.isFinite() }
    }
    if (points.isEmpty()) return

    val labels = remember(points) { points.map { it.first } }
    val values = remember(points) { points.map { it.second } }

    // Y-range dai dati reali con piccolo padding proporzionale
    val (yMin, yMax) = remember(values) {
        val min = values.minOrNull()!!
        val max = values.maxOrNull()!!
        if (min == max) (min - 0.1) to (max + 0.1) else {
            val span = max - min
            (min - 0.05 * span) to (max + 0.05 * span)
        }
    }

    val line = remember(values) {
        listOf(
            Line(
                label = "Temperatura (°C)",
                values = values,
                color = SolidColor(Color(0xFFFF7043)),
                curvedEdges = true,
                firstGradientFillColor = Color(0xFFFFAB91).copy(alpha = 0.28f),
                secondGradientFillColor = Color.Transparent,
                drawStyle = DrawStyle.Stroke(width = 3.dp, strokeStyle = StrokeStyle.Normal),
                popupProperties = PopupProperties(
                    enabled = true,
                    duration = 2500,
                    containerColor = Color(0xFFFF7043),
                    cornerRadius = 6.dp
                )
            )
        )
    }

    // larghezza contenuto per permettere lo scroll X
    val minCellWidth = 42.dp
    val contentWidth = minCellWidth * points.size.coerceAtLeast(8)
    val scrollState = rememberScrollState()

    ChartCard(
        title = "Andamento Temperatura",
        legend = { LegendItem(Color(0xFFFF7043), "Temperatura (°C)") },
        modifier = modifier,

    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) {
            LineChart(
                modifier = Modifier
                    .width(contentWidth)
                    .height(260.dp)
                    .padding(bottom = 6.dp),
                data = line,
                curvedEdges = true,
                animationMode = AnimationMode.OneByOne,
                gridProperties = GridProperties(
                    enabled = true,
                    xAxisProperties = GridProperties.AxisProperties(
                        enabled = true,
                        thickness = 1.dp,
                        style = StrokeStyle.Dashed(intervals = floatArrayOf(8f, 8f))
                    ),
                    yAxisProperties = GridProperties.AxisProperties(
                        enabled = true,
                        thickness = 1.dp,
                        style = StrokeStyle.Dashed(intervals = floatArrayOf(8f, 8f))
                    ),
                ),
                labelProperties = LabelProperties(
                    enabled = true,
                    labels = labels,
                    textStyle = MaterialTheme.typography.labelSmall,
                    rotation = LabelProperties.Rotation(
                        mode = LabelProperties.Rotation.Mode.Force,
                        degree = -45f,
                        padding = 2.dp
                    )
                ),
                labelHelperProperties = LabelHelperProperties(enabled = true),
                zeroLineProperties = ZeroLineProperties(enabled = false),
                minValue = yMin,
                maxValue = yMax,
                dividerProperties = DividerProperties(enabled = false)
            )
        }
    }
}

/**
 * Grafico Umidità con asse X scrollabile e Y range auto dai dati
 */
@Composable
fun HumidityChart(
    data: List<HistoricalItem>,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val points = remember(data) {
        data.map { item ->
            formatter.format(Date(item.timestamp)) to item.humidity.toDouble()
        }.filter { it.second.isFinite() }
    }
    if (points.isEmpty()) return

    val labels = remember(points) { points.map { it.first } }
    val values = remember(points) { points.map { it.second } }

    val (yMin, yMax) = remember(values) {
        val min = values.minOrNull()!!
        val max = values.maxOrNull()!!
        if (min == max) (min - 0.1) to (max + 0.1) else {
            val span = max - min
            (min - 0.05 * span) to (max + 0.05 * span)
        }
    }

    val line = remember(values) {
        listOf(
            Line(
                label = "Umidità (%)",
                values = values,
                color = SolidColor(Color(0xFF42A5F5)),
                curvedEdges = true,
                firstGradientFillColor = Color(0xFF90CAF9).copy(alpha = 0.28f),
                secondGradientFillColor = Color.Transparent,
                drawStyle = DrawStyle.Stroke(
                    width = 3.dp,
                    strokeStyle = StrokeStyle.Dashed(intervals = floatArrayOf(12f, 8f))
                ),
                popupProperties = PopupProperties(
                    enabled = true,
                    duration = 2500,
                    containerColor = Color(0xFF42A5F5),
                    cornerRadius = 6.dp
                )
            )
        )
    }

    val minCellWidth = 42.dp
    val contentWidth = minCellWidth * points.size.coerceAtLeast(8)
    val scrollState = rememberScrollState()

    ChartCard(
        title = "Andamento Umidità",
        legend = { LegendItem(Color(0xFF42A5F5), "Umidità (%)") },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) {
            LineChart(
                modifier = Modifier
                    .width(contentWidth)
                    .height(260.dp)
                    .padding(bottom = 6.dp),
                data = line,
                curvedEdges = true,
                animationMode = AnimationMode.OneByOne,
                gridProperties = GridProperties(
                    enabled = true,
                    xAxisProperties = GridProperties.AxisProperties(
                        enabled = true,
                        thickness = 1.dp,
                        style = StrokeStyle.Dashed(intervals = floatArrayOf(8f, 8f))
                    ),
                    yAxisProperties = GridProperties.AxisProperties(
                        enabled = true,
                        thickness = 1.dp,
                        style = StrokeStyle.Dashed(intervals = floatArrayOf(8f, 8f))
                    ),
                ),
                labelProperties = LabelProperties(
                    enabled = true,
                    labels = labels,
                    textStyle = MaterialTheme.typography.labelSmall,
                    rotation = LabelProperties.Rotation(
                        mode = LabelProperties.Rotation.Mode.Force,
                        degree = -45f,
                        padding = 2.dp
                    )
                ),
                labelHelperProperties = LabelHelperProperties(enabled = true),
                zeroLineProperties = ZeroLineProperties(enabled = false),
                minValue = yMin,
                maxValue = yMax,
                dividerProperties = DividerProperties(enabled = false)
            )
        }
    }
}
