package com.example.vitality.coaching

object Normalizer {

    /**
     * Normalizza i nomi dei POI, delle Zone, RoomName:
     * - minuscole
     * - rimozione spazi
     * - rimozione underscore
     * - rimozione trattini
     */
    fun normalize(name: String?): String {
        if (name == null) return ""
        return name
            .lowercase()
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
            .trim()
    }
}
