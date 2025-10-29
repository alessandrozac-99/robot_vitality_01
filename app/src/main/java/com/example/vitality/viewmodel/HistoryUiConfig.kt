package com.example.vitality.viewmodel

/** Configurazione UI per lo storico (finestra temporale + passo di resampling). */
data class HistoryUiConfig(
    val windowMs: Long = 5L * 3600L * 1000L,     // default: 6 ore
    val stepMs: Long = 5L * 60L * 1000L          // default: 5 minuti
)
