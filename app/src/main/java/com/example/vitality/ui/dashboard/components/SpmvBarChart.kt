// file: app/src/main/java/com/example/vitality/ui/dashboard/components/SpmvBarChart.kt
package com.example.vitality.ui.dashboard.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import ir.ehsannarmani.compose_charts.ColumnChart
import ir.ehsannarmani.compose_charts.models.AnimationMode
import ir.ehsannarmani.compose_charts.models.Bars
import ir.ehsannarmani.compose_charts.models.DividerProperties
import ir.ehsannarmani.compose_charts.models.GridProperties
import ir.ehsannarmani.compose_charts.models.LabelHelperProperties
import ir.ehsannarmani.compose_charts.models.LabelProperties
import ir.ehsannarmani.compose_charts.models.PopupProperties
import ir.ehsannarmani.compose_charts.models.StrokeStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * ColumnChart per sPMV.
 * Input: lista di (timestampMs, spmv).
 * - Y range adattivo (con padding).
 * - Scroll orizzontale se molte barre.
 */
@Composable
fun SpmvBarChart(
    spmvSeries: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    title: String = "sPMV"
) {
    if (spmvSeries.isEmpty()) return

    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Preparo punti validi
    val points = remember(spmvSeries) {
        spmvSeries
            .filter { it.second.isFinite() }
            .map { (ts, v) -> fmt.format(Date(ts)) to v }
    }
    if (points.isEmpty()) return

    // Y dinamico (padding 5%). Se min==max espando ±0.1
    val values = remember(points) { points.map { it.second } }
    val (minY, maxY) = remember(values) {
        val min = values.minOrNull()!!
        val max = values.maxOrNull()!!
        if (min == max) (min - 0.1) to (max + 0.1) else {
            val span = max - min
            (min - 0.05 * span) to (max + 0.05 * span)
        }
    }

    // Costruisco le barre (una Bars per campione, 1 valore ciascuna)
    val bars = remember(points) {
        points.map { (label, v) ->
            Bars(
                label = label,
                values = listOf(
                    Bars.Data(
                        value = v,
                        color = SolidColor(spmvColor(v))
                    )
                )
            )
        }
    }

    // Larghezza contenuto scrollabile
    val cell = 32.dp
    val contentWidth = cell * bars.size.coerceAtLeast(8)
    val scroll = rememberScrollState()

    ChartCard(
        title = title,
        legend = {
            LegendItem(Color(0xFF2E7D32), "comfort (|≤0.5|)")
            LegendItem(Color(0xFFF9A825), "marginale (≤1.0)")
            LegendItem(Color(0xFFC62828), "discomfort (>1.0)")
        },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
        ) {
            ColumnChart(
                modifier = Modifier
                    .width(contentWidth)
                    .height(260.dp)
                    .padding(bottom = 6.dp),
                data = bars,
                minValue = minY-0.2,
                maxValue = maxY+0.2,
                animationMode = AnimationMode.Together(),
                // Popup come negli altri grafici (nessun contentBuilder custom)
                popupProperties = PopupProperties(
                    enabled = true,
                    duration = 2000L,
                    textStyle = MaterialTheme.typography.labelSmall
                ),
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
                    textStyle = MaterialTheme.typography.labelSmall,
                    rotation = LabelProperties.Rotation(
                        mode = LabelProperties.Rotation.Mode.Force,
                        degree = -45f,
                        padding = 2.dp
                    )
                ),
                labelHelperProperties = LabelHelperProperties(enabled = true),
                dividerProperties = DividerProperties(enabled = false)
            )
        }
    }
}

private fun spmvColor(v: Double): Color = when {
    abs(v) <= 0.5 -> Color(0xFF2E7D32) // comfort
    abs(v) <= 1.0 -> Color(0xFFF9A825) // marginale
    else          -> Color(0xFFC62828) // discomfort
}
