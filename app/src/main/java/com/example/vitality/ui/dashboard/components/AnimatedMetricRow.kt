package com.example.vitality.ui.dashboard.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

@Composable
fun AnimatedMetricRow(label: String, value: String) {
    var oldValue by remember { mutableStateOf(value) }
    val highlight = remember { Animatable(0f) }

    LaunchedEffect(value) {
        if (value != oldValue) {
            highlight.snapTo(1f)
            highlight.animateTo(0f, tween(700))
            oldValue = value
        }
    }

    val bgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    val animatedColor = bgColor.copy(alpha = highlight.value)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(animatedColor),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 15.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
