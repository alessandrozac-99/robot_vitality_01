package com.example.vitality.data

/**
 * 📡 Stato completo presa Shelly (compatibile con RPC locale)
 */
data class SmartPlugStatus(
    val name: String,
    val id: String,
    val online: Boolean,

    // Misure elettriche
    val apower: Double,             // Potenza istantanea (W)
    val voltage: Double,            // Tensione (V)
    val current: Double,            // Corrente (A)

    // Temperatura interna
    val temperature: Double,

    // Energia totale
    val aenergyTotal: Double,

    // Wi-Fi & metadata
    val ssid: String,
    val rssi: Int,
    val room: String,

    // Errore (se offline)
    val error: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),

    // Extra opzionali (in base al modello Shelly)
    val output: Boolean? = null,
    val pf: Double? = null,
    val freq: Double? = null,
    val overpower: Boolean? = null,
    val overtemperature: Boolean? = null,
    val errors: List<String>? = null,

    // Energia minuto
    val aenergyByMinute: List<Double>? = null,
    val aenergyMinuteTs: Long? = null,

    // Network
    val ip: String? = null,
    val wifiConnected: Boolean? = null,
    val wifiStaIP: String? = null,

    // Stato cloud / MQTT (non usati in locale, ma mantenuti per compatibilità)
    val cloudConnected: Boolean? = null,
    val mqttConnected: Boolean? = null,

    // Informazioni hardware
    val mac: String? = null,
    val model: String? = null,
    val fw: String? = null,
    val fwId: String? = null,
    val uptimeSec: Long? = null,
    val hasUpdate: Boolean? = null
)
