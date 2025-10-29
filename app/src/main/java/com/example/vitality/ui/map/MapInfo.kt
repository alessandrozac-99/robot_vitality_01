package com.example.vitality.ui.map

/**
 * 🔹 Classe dati per rappresentare le informazioni geometriche della mappa
 * caricata da file JSON (es. width, height, origine e risoluzione).
 */
data class MapInfo(
    val width: Int,
    val height: Int,
    val originX: Double,
    val originY: Double,
    val resolution: Double
)
