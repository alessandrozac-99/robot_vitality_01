package com.example.vitality.ui.dashboard.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChartsCard(
    spmv: List<Pair<Long, Float>>,
    temp: List<Pair<Long, Float>>,
    hum: List<Pair<Long, Float>>
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {

        Column(Modifier.padding(16.dp)) {

            SectionTitle("📈 Indice sPMV – Storico Giornaliero")
            AnimatedChart { SpmvChart(spmv) }

            Spacer(Modifier.height(20.dp))

            SectionTitle("🌡️ Temperatura – Storico Giornaliero")
            AnimatedChart { TemperatureChart(temp) }

            Spacer(Modifier.height(20.dp))

            SectionTitle("💧 Umidità – Storico Giornaliero")
            AnimatedChart { HumidityChart(hum) }
        }
    }
}
