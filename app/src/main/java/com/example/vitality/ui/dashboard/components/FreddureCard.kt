package com.example.vitality.ui.dashboard.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vitality.freddure.FreddureReader
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
@Composable
fun FredduraCard() {
    val context = LocalContext.current
    val robot = Robot.getInstance()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Text("Freddura del giorno", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    FreddureReader.speakRandomFreddura(robot, context)
                }
            ) {
                Text("Racconta una freddura 🤖")
            }
        }
    }
}
