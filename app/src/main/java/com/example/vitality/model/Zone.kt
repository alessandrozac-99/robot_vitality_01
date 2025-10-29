package com.example.vitality.model

data class Zone(
    val name: String,
    val vertices: List<Vertex>
) {
    data class Vertex(
        val x: Float,
        val y: Float
    )
}
