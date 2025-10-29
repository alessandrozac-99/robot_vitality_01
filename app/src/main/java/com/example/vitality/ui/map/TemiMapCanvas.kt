package com.example.vitality.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.vitality.model.Zone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas della mappa:
 * - Disegna SOLO la mappa e (opzionalmente) l’overlay sPMV.
 * - I poligoni NON sono disegnati (invisibili), ma restano cliccabili (hit-test).
 */
@Composable
fun TemiMapCanvas(
    mapBitmap: ImageBitmap,
    zones: List<Zone>,
    mapInfo: MapInfo?,
    selectedZone: String?,
    onZoneSelected: (String) -> Unit,
    // opzionale: overlay tinteggiato generato dal VM (image con alpha)
    tintOverlay: ImageBitmap? = null,
    modifier: Modifier = Modifier
) {
    // Dimensioni reali del composable (per mappare i click in coordinate bitmap)
    var canvasSize = IntSize(1, 1)

    // Precalcola i bounding box dei poligoni per accelerare l’hit-test
    val zoneBounds = remember(zones) {
        zones.associateWith { z ->
            val minX = z.vertices.minOfOrNull { it.x } ?: 0f
            val maxX = z.vertices.maxOfOrNull { it.x } ?: 0f
            val minY = z.vertices.minOfOrNull { it.y } ?: 0f
            val maxY = z.vertices.maxOfOrNull { it.y } ?: 0f
            Bounds(minX, minY, maxX, maxY)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { canvasSize = it.size }
            .pointerInput(zones, mapBitmap) {
                detectTapGestures { pos ->
                    // Converto il tap in coordinate bitmap
                    val (sx, sy) = computeScale(canvasSize, mapBitmap.width, mapBitmap.height)
                    val bx = pos.x / sx
                    val by = pos.y / sy

                    // Cerca la prima zona che contiene il punto (prima bounding box, poi poligono)
                    val hit = zones.firstOrNull { z ->
                        val b = zoneBounds[z]!!
                        if (!b.contains(bx, by)) return@firstOrNull false
                        pointInPolygon(bx, by, z.vertices)
                    }
                    hit?.let { onZoneSelected(it.name) }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasIntSize = IntSize(size.width.toInt(), size.height.toInt())
            val (scaleX, scaleY) = computeScale(canvasIntSize, mapBitmap.width, mapBitmap.height)

            // Dimensioni di destinazione per riempire il canvas mantenendo aspect ratio per asse (qui indipendente)
            val dstSize = IntSize(
                width = (mapBitmap.width * scaleX).toInt().coerceAtLeast(1),
                height = (mapBitmap.height * scaleY).toInt().coerceAtLeast(1)
            )

            // Disegno mappa base
            drawImage(
                image = mapBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(mapBitmap.width, mapBitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = dstSize
            )

            // Disegno overlay sPMV se presente (stessa scala della mappa)
            tintOverlay?.let { overlay ->
                drawImage(
                    image = overlay,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(overlay.width, overlay.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = dstSize
                )
            }

        }
    }
}

/* ================= helpers ================= */

private data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    fun contains(x: Float, y: Float): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY
}

/**
 * Calcola i fattori di scala per adattare la bitmap alle dimensioni del canvas.
 * Qui uso scaling indipendente per X e Y per riempire tutta l’area.
 */
private fun computeScale(canvas: IntSize, bmpW: Int, bmpH: Int): Pair<Float, Float> {
    val sx = canvas.width.toFloat() / max(1, bmpW)
    val sy = canvas.height.toFloat() / max(1, bmpH)
    return sx to sy
}

/**
 * Ray casting per test punto in poligono.
 * Le coordinate dei vertici sono in pixel bitmap (x,y).
 */
private fun pointInPolygon(x: Float, y: Float, vertices: List<Zone.Vertex>): Boolean {
    var inside = false
    var j = vertices.lastIndex
    for (i in vertices.indices) {
        val xi = vertices[i].x
        val yi = vertices[i].y
        val xj = vertices[j].x
        val yj = vertices[j].y

        val cond = ((yi > y) != (yj > y))
        val denom = (yj - yi)
        val safeDen = if (abs(denom) < 1e-6f) 1e-6f else denom
        val xCross = (xj - xi) * (y - yi) / safeDen + xi

        if (cond && x < xCross) inside = !inside
        j = i
    }
    return inside
}
