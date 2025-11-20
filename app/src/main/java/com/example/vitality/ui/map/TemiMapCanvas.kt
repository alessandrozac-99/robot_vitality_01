package com.example.vitality.ui.map

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.vitality.model.Zone
import kotlin.math.abs
import kotlin.math.min

@Composable
fun TemiMapCanvas(
    mapBitmap: ImageBitmap,
    zones: List<Zone>,
    mapInfo: MapInfo?,
    selectedZone: String?,
    onZoneSelected: (String) -> Unit,
    tintOverlay: ImageBitmap? = null,
    heatmapOverlay: ImageBitmap? = null,
    poi: List<TemiMapViewModel.Poi> = emptyList(),
    modifier: Modifier = Modifier
) {

    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }

    // Bounding box precalcolati
    val zoneBounds = remember(zones) {
        zones.associateWith { z ->
            Bounds(
                z.vertices.minOf { it.x },
                z.vertices.minOf { it.y },
                z.vertices.maxOf { it.x },
                z.vertices.maxOf { it.y }
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { canvasSize = it.size }
            .pointerInput(zones, mapBitmap) {
                detectTapGestures { pos ->
                    val (sx, sy) = computeScale(canvasSize, mapBitmap.width, mapBitmap.height)
                    val bx = pos.x / sx
                    val by = pos.y / sy

                    val hit = zones.firstOrNull { z ->
                        val b = zoneBounds[z]!!
                        b.contains(bx, by) &&
                                pointInPolygon(bx, by, z.vertices)
                    }

                    hit?.let { onZoneSelected(it.name) }
                }
            }
    ) {

        Canvas(modifier = Modifier.fillMaxSize()) {

            val canvasInt = IntSize(size.width.toInt(), size.height.toInt())
            val (scaleX, scaleY) = computeScale(canvasInt, mapBitmap.width, mapBitmap.height)

            val dstSize = IntSize(
                (mapBitmap.width * scaleX).toInt(),
                (mapBitmap.height * scaleY).toInt()
            )

            //----------------------------------------------------------
            // 1) MAPPA BASE
            //----------------------------------------------------------
            drawImage(
                image = mapBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(mapBitmap.width, mapBitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = dstSize
            )

            //----------------------------------------------------------
            // 2) HEATMAP
            //----------------------------------------------------------
            heatmapOverlay?.let {
                drawImage(
                    image = it,
                    srcSize = IntSize(it.width, it.height),
                    dstSize = dstSize
                )
            }

            //----------------------------------------------------------
            // 3) POI — con testo ingrandito se selezionato (senza ombra)
            //----------------------------------------------------------
            poi.forEach { p ->
                val cx = p.x * scaleX
                val cy = p.y * scaleY

                // Punto rosso
                drawCircle(
                    color = Color.Red,
                    radius = 8f,
                    center = Offset(cx, cy)
                )

                // Normalizzazione per il match
                val isSelectedPoi =
                    selectedZone != null &&
                            normalizeName(selectedZone!!) == normalizeName(p.name)

                // Nome POI (normale / ingrandito)
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        textSize = if (isSelectedPoi) 52f else 32f
                        color = android.graphics.Color.RED
                        if (isSelectedPoi) {
                            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                        }
                    }

                    canvas.nativeCanvas.drawText(
                        p.name,
                        cx + 6f,
                        cy,
                        paint
                    )
                }
            }
        }
    }
}

/* ---------------- HELPERS ---------------- */

private data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    fun contains(x: Float, y: Float): Boolean = x in minX..maxX && y in minY..maxY
}

private fun computeScale(canvas: IntSize, bmpW: Int, bmpH: Int): Pair<Float, Float> {
    val sx = canvas.width.toFloat() / bmpW
    val sy = canvas.height.toFloat() / bmpH
    val scale = min(sx, sy)
    return scale to scale
}

private fun pointInPolygon(x: Float, y: Float, vertices: List<Zone.Vertex>): Boolean {
    var inside = false
    var j = vertices.lastIndex
    for (i in vertices.indices) {
        val xi = vertices[i].x
        val yi = vertices[i].y
        val xj = vertices[j].x
        val yj = vertices[j].y

        val intersect = (yi > y) != (yj > y)
        val denom = (yj - yi).let { if (abs(it) < 1e-6f) 1e-6f else it }
        val xCross = (xj - xi) * (y - yi) / denom + xi

        if (intersect && x < xCross) inside = !inside
        j = i
    }
    return inside
}

private fun normalizeName(name: String): String =
    name.trim().lowercase()
        .replace(" ", "")
        .replace("-", "")
        .replace("_", "")
