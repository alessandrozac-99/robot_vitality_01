package com.example.vitality.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.font.FontWeight

/**
 * Card contenitore per i grafici.
 *
 * - Modernizzata per supportare Material 3 (Surface)
 * - Supporto per background variant
 * - Slot "legend" per legenda dinamica
 * - Slot "content" per grafico
 * - Animazioni: fade-in quando compare il grafico
 */
@Composable
fun ChartCard(
    title: String,
    modifier: Modifier = Modifier,
    legend: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium
    ) {

        Column(Modifier.padding(12.dp)) {

            // Titolo sezione grafico
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // Contenuto (grafico)
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                content()
            }

            Spacer(Modifier.height(10.dp))

            // Legenda (opzionale)
            legend?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    content = it
                )
            }
        }
    }
}

/**
 * Item di legenda con colore + etichetta
 */
@Composable
fun LegendItem(
    color: Color,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {

        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, shape = MaterialTheme.shapes.small)
        )

        Spacer(Modifier.width(6.dp))

        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
