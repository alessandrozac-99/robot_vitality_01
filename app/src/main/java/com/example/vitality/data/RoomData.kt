package com.example.vitality.data

/**
 * 🔹 Identificatori univoci dei sensori per una singola stanza.
 * Include deviceId per eliminare hardcoded nei ViewModel/Repository.
 */
data class RoomSensorIds(
    val deviceId: String,
    val temperatureId: String,
    val humidityId: String,
    val co2Id: String,
    val airqualityscoreId: String? = null,
    val iaqindexId: String? = null,
    val soundlevelId: String? = null,
    val illuminationId: String? = null,
    val vocId: String? = null
)

/** 🔌 Identificatori delle prese Shelly associate a una stanza. */
data class RoomPlugIds(
    val plugs: List<String>
)

/** 🌡️ Mappa di tutte le stanze e dei relativi sensori ambientali. */
val roomSensorIds: Map<String, RoomSensorIds> = mapOf(
    "Nicole" to RoomSensorIds(
        deviceId       = "tTeol9dV",
        temperatureId  = "93H3xMUZGszO338S",
        humidityId     = "8NiKWnk7l5zqtwT_",
        co2Id          = "5dqigF_WopUg7Rze",
        airqualityscoreId = "kZlu3qYVOHzcbI3g",
        iaqindexId     = "1Qjr4OkTe7HD2kmV",
        soundlevelId   = "DtMXHzGlDHFYgdSj",
        illuminationId = "YwdfmYOvCftKLWSz",
        vocId          = "d37vb4I4brsUJHzW"
    ),
    "Os" to RoomSensorIds(
        deviceId       = "tTeol9dV",
        temperatureId  = "XLzqqhPeqbKK9crE",
        humidityId     = "GaxAXR4Y2M09s7_e",
        co2Id          = "TgWBwdcmbUYBcybw",
        airqualityscoreId = "x3BzJJSOPOj1hHqu",
        iaqindexId     = "tHnE_bwNMpgBmrVi",
        soundlevelId   = "iaNuDucQEjbd9AYj",
        illuminationId = "eU1DoAGV5v5U0DYd",
        vocId          = "3HXHVRvMbYd_E0xZ"
    ),
    "Serena" to RoomSensorIds(
        deviceId       = "tTeol9dV",
        temperatureId  = "OEEBnrUHMDMlxIuD",
        humidityId     = "OiYHmLkFjGX93wwU",
        co2Id          = "kgBR2Vu8CS077bOT",
        airqualityscoreId = "hW7mTalGN1jlJZWe",
        iaqindexId     = "4CzvW7tYaFWc38sN",
        soundlevelId   = "MKv7ldHorq59x0S0",
        illuminationId = "_8ukhO5DWebq03Mv",
        vocId          = "EcgN_XUzHPfi3G7p"
    ),
    "Gloria" to RoomSensorIds(
        deviceId       = "tTeol9dV",
        temperatureId  = "eq7qDs0MnoFVOzwa",
        humidityId     = "LmogzLXrF0Fo5wb0",
        co2Id          = "6rbaYr6N7EMlNjSA",
        airqualityscoreId = "Uf3d79_4t7sExTBh",
        iaqindexId     = "WeAOUxufd2pnDh29",
        soundlevelId   = "xJIXW4r0acvTi9JT",
        illuminationId = "4s0sSCJOX_DpGl6z",
        vocId          = "ASDWQ6EcTTvv2L1q"
    )
)

/** ⚡ Mappa delle prese Shelly per stanza. */
val roomPlugIds: Map<String, RoomPlugIds> = mapOf(
    "Nicole" to RoomPlugIds(plugs = listOf("PRESA_NICOLE", "PRESA_CECILIA")),
    "Os"     to RoomPlugIds(plugs = listOf("PRESA_VITTORIA", "PRESA_RICHARD")),
    "Serena" to RoomPlugIds(plugs = listOf("PRESA_SERENA")),
    "Gloria" to RoomPlugIds(plugs = listOf("PRESA_GLORIA", "PRESA_NIBRAS"))
)

/** 🗺️ Zone attive da visualizzare nella mappa e nella dashboard. */
val activeZones = listOf("Nicole", "Os", "Serena", "Gloria")

/** 🧩 Normalizzazione globale per confronti robusti. */
fun normalizeName(name: String): String =
    name.trim().lowercase().replace("_", "").replace(" ", "")
