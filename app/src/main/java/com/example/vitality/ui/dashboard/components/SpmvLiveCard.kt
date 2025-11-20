package com.example.vitality.ui.dashboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitality.viewmodel.TemperatureViewModel
import kotlin.math.abs
import androidx.compose.animation.togetherWith

@Composable
fun SpmvLiveCard(spmv: TemperatureViewModel.SpmvUi) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {

            SectionTitle("🎯 Comfort – sPMV Live")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpmvTile("sPMV1", spmv.pmv)
                SpmvTile("sPMV2", spmv.pmv2)
                SpmvTile("sPMV3", spmv.pmv3)
                SpmvTile("CLO", spmv.cloPred, isClo = true)
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SpmvTile(
    title: String,
    value: Double?,
    isClo: Boolean = false
) {
    val color = when {
        isClo -> MaterialTheme.colorScheme.outline
        value == null -> MaterialTheme.colorScheme.outlineVariant
        abs(value) <= 0.5 -> Color(0xFF2E7D32)   // verde
        abs(value) <= 1.0 -> Color(0xFFF9A825)   // giallo
        else -> Color(0xFFC62828)                // rosso
    }

    // Animazione altezza barra (no animateDpAsState)
    val animatedHeight = remember { Animatable(0f) }

    val targetHeightPx = ((abs(value ?: 0.0) * 22.0)
        .coerceAtMost(22.0)).toFloat()

    LaunchedEffect(value) {
        animatedHeight.animateTo(
            targetHeightPx,
            animationSpec = tween(
                durationMillis = 450,
                easing = FastOutSlowInEasing
            )
        )
    }

    ElevatedCard(
        modifier = Modifier.padding(4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .width(IntrinsicSize.Min)
        ) {

            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {

                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(animatedHeight.value.dp)
                        .background(color, shape = MaterialTheme.shapes.small)
                )

                Spacer(Modifier.width(10.dp))

                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        (fadeIn(tween(200)) +
                                slideInVertically { it / 3 }) togetherWith
                                (fadeOut(tween(200)) +
                                        slideOutVertically { -it / 3 })
                    },
                    label = "spmvValueAnim"
                ) { newValue ->
                    val txt = when (newValue) {
                        null -> "—"
                        else -> if (!isClo) "%+.2f".format(newValue)
                        else "%.2f".format(newValue)
                    }

                    Text(
                        text = txt,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
