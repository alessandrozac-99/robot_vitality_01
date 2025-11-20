package com.example.vitality.ui.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SpmvLegend(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "Legenda SPMV",
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        ) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFF0000FF),   // blu (-1)
                        Color(0xFF00FF00),   // verde (0)
                        Color(0xFFFFFF00),   // giallo (0.5)
                        Color(0xFFFF0000),   // rosso (1)
                    )
                )
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-0.5")
            Text("0.0")
            Text("+0.5")
        }
    }
}
