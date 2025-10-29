package com.example.vitality.data

// --- Instant property with optional history (includeHistory=true)
data class HistoryValues(
    val readValues: List<PropertyHistoricalItem> = emptyList(),
    val writeValues: List<PropertyHistoricalItem> = emptyList()
)

data class PropertyHistoricalItem(
    val property: String? = null,
    val timestamp: Long,
    val payload: Double? = null,
    val scaledPayload: Double? = null
)

data class PropertyResponse(
    val deviceId: String,
    val property: String,
    val slug: String? = null,
    val name: String? = null,
    val timestamp: Long? = null,
    val payload: Any? = null,          // Number | String
    val scaledPayload: Double? = null,
    val writeTimestamp: Long? = null,
    val writePayload: Any? = null,
    val writeScaledPayload: Double? = null,
    val writeOutOfService: Boolean? = null,
    val unit: String? = null,
    val equipmentId: String? = null,
    val gatewayInterfaceId: String? = null,
    val tags: Map<String, String>? = null,
    val externalId: String? = null,
    val isOutOfService: Boolean? = null,
    val history: HistoryValues? = null
)

// --- Shape semplice per chart merge T/H
data class HistoricalItem(
    val timestamp: Long,
    val temperature: Double,
    val humidity: Double
)
