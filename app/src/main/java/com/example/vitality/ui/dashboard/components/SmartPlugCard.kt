package com.example.vitality.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp        // ⭐ MANCAVA!
import com.example.vitality.data.SmartPlugStatus
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween

/* ======================================================================================
   CARD SMART PLUG LIVE — con espansione fluida
====================================================================================== */

@Composable
fun SmartPlugLiveCard(plugs: List<SmartPlugStatus>) {

    // 🔥 Regola: stanza occupata se una presa supera 10W
    val occupied = plugs.any { it.apower > 10 }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("⚡ Prese Smart Plug")

                // 🔥 Badge Occupazione (dinamico + animato)
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(500)) + slideInVertically { it / 2 }
                ) {
                    val bg = if (occupied) Color(0xFFD32F2F) else Color(0xFF388E3C)
                    val text = if (occupied) "Stanza occupata" else "Stanza Vuota"

                    Box(
                        modifier = Modifier
                            .background(
                                color = bg,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = text,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (plugs.isEmpty()) {
                Text("Nessuna presa disponibile", color = Color.Gray)
                return@Column
            }

            // 🔌 lista prese
            plugs.forEach { plug ->
                SmartPlugItem(plug)
            }
        }
    }
}


/* ======================================================================================
   SINGOLO ITEM (con animazione expand/collapse)
====================================================================================== */

@Composable
private fun SmartPlugItem(plug: SmartPlugStatus) {
    var expanded by remember { mutableStateOf(false) }

    val rotateArrow by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(300),
        label = ""
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {

        Column(Modifier.padding(12.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                StatusDot(plug.online)

                Spacer(Modifier.width(8.dp))

                Text(
                    plug.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.rotate(rotateArrow)
                    )
                }
            }

            Text(
                text = "Potenza: %.1f W".format(plug.apower),
                fontSize = 14.sp,
                color = Color.DarkGray,
                modifier = Modifier.padding(top = 4.dp)
            )

            AnimatedVisibility(expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    InfoRow("Corrente", plug.current?.let { "%.2f A".format(it) } ?: "—")
                    InfoRow("Voltaggio", plug.voltage?.let { "%.0f V".format(it) } ?: "—")
                    InfoRow("Power Factor", plug.pf?.let { "%.2f".format(it) } ?: "—")
                    InfoRow("Wi-Fi", plug.ssid.ifEmpty { "—" })
                    InfoRow("RSSI", plug.rssi.toString())
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
}

/* ======================================================================================
   COMPONENTI INTERIORS
====================================================================================== */

@Composable
private fun StatusDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (online) Color(0xFF2E7D32) else Color(0xFFC62828),
                shape = MaterialTheme.shapes.small
            )
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp)
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
