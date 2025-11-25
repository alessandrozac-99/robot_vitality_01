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
fun FredduraCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val robot = Robot.getInstance()

    var freddura by remember { mutableStateOf<String?>(null) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(3.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text = "Hai bisogno di un sorriso?",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold
                )
            )

            // 🔥 BOTTONE ACCATTIVANTE
            Button(
                onClick = {
                    val text = FreddureReader.loadRandomFreddura(context)
                    freddura = text
                    text?.let {
                        robot.speak(TtsRequest.create(it, false))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.large, // ← forma pill
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                Text(
                    "Enjoy 😄",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold
                    )
                )
            }

            AnimatedVisibility(
                visible = freddura != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                freddura?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
