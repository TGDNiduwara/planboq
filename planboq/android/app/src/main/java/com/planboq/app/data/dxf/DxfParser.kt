package com.planboq.app.data.dxf

import com.planboq.app.data.geometry.Layer
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Polyline
import com.planboq.app.data.geometry.ScaleState
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.SourceType
import com.planboq.app.data.geometry.Vec2
import java.io.BufferedReader
import java.io.Reader
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal ASCII DXF reader (R12-R2018) for the entities a floor plan uses:
 * LINE, LWPOLYLINE, POLYLINE/VERTEX, CIRCLE, ARC. Bulges are read as straight
 * chords (documented limitation); 3D polylines are flattened by their X/Y.
 */
class DxfParser {

    private class Pair(val code: Int, val value: String)

    private fun readPairs(reader: Reader): MutableList<Pair> {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader)
        val out = mutableListOf<Pair>()
        while (true) {
            val codeLine = br.readLine() ?: break
            val valueLine = br.readLine() ?: break
            val code = codeLine.trim().toIntOrNull() ?: continue
            out.add(Pair(code, valueLine.trim()))
        }
        return out
    }

    /**
     * @param fallbackMmPerUnit conversion used when the file is unitless
     * (INSUNITS 0/missing). Pass the value the user picked in the UI.
     */
    fun parse(reader: Reader, fallbackMmPerUnit: Double = 1.0, fileName: String = "plan"): PlanDocument {
        val pairs = readPairs(reader)

        // --- header units ---
        var mmPerUnit: Double? = null
        var i = 0
        while (i < pairs.size - 2) {
            val p = pairs[i]
            if (p.code == 9 && p.value.equals("\$INSUNITS", ignoreCase = true)) {
                // next pair should be (70, n)
                val v = pairs[i + 1]
                if (v.code == 70) {
                    mmPerUnit = DxfUnits.mmPerUnit(v.value.toIntOrNull() ?: 0)
                }
                break
            }
            i++
        }
        val factor = mmPerUnit ?: fallbackMmPerUnit

        // --- entities section ---
        var idx = 0
        var entitiesStart = -1
        while (idx < pairs.size - 1) {
            if (idx > 0 &&
                pairs[idx].code == 2 && pairs[idx].value.equals("ENTITIES", true) &&
                pairs[idx - 1].code == 0 && pairs[idx - 1].value.equals("SECTION", true)
            ) {
                entitiesStart = idx + 1
                break
            }
            idx++
        }
        val doc = PlanDocument(name = fileName, sourceType = SourceType.DXF, scale = ScaleState(true, 1.0, "DXF units (mm)"))
        if (entitiesStart < 0) return doc

        val layerNames = LinkedHashSet<String>()
        val segments = mutableListOf<Segment>()
        val polylines = mutableListOf<Polyline>()

        var j = entitiesStart
        while (j < pairs.size) {
            val p = pairs[j]
            if (p.code == 0 && p.value.equals("ENDSEC", true)) break
            if (p.code != 0) { j++; continue }

            val type = p.value.uppercase()
            // gather body pairs until next code 0
            var k = j + 1
            val body = mutableListOf<Pair>()
            while (k < pairs.size && pairs[k].code != 0) { body.add(pairs[k]); k++ }

            when (type) {
                "LINE" -> {
                    val x1 = body.d(10); val y1 = body.d(20)
                    val x2 = body.d(11); val y2 = body.d(21)
                    val layerName = body.s(8) ?: "0"
                    layerNames.add(layerName)
                    if (x1 != null && y1 != null && x2 != null && y2 != null) {
                        segments += Segment(
                            a = Vec2(x1 * factor, y1 * factor),
                            b = Vec2(x2 * factor, y2 * factor),
                            layerId = layerName,
                        )
                    }
                }
                "LWPOLYLINE" -> {
                    val layerName = body.s(8) ?: "0"
                    layerNames.add(layerName)
                    val closed = (body.i(70) ?: 0) and 1 == 1
                    val pts = mutableListOf<Vec2>()
                    var m = 0
                    while (m < body.size) {
                        if (body[m].code == 10) {
                            val x = body[m].value.toDoubleOrNull()
                            val y = body.getOrNull(m + 1)?.takeIf { it.code == 20 }?.value?.toDoubleOrNull()
                            if (x != null && y != null) pts.add(Vec2(x * factor, y * factor))
                        }
                        m++
                    }
                    if (pts.size >= 2) polylines += Polyline(points = pts, closed = closed, layerId = layerName)
                }
                "POLYLINE" -> {
                    val layerName = body.s(8) ?: "0"
                    layerNames.add(layerName)
                    val flags = body.i(70) ?: 0
                    val closed = flags and 1 == 1
                    val is3D = flags and 8 == 8 || flags and 16 == 16
                    // vertices follow as separate entities; collect until SEQEND
                    val pts = mutableListOf<Vec2>()
                    var q = k
                    var ended = false
                    while (q < pairs.size && !ended) {
                        val vp = pairs[q]
                        if (vp.code == 0) {
                            when (vp.value.uppercase()) {
                                "VERTEX" -> {
                                    var m = q + 1
                                    var x: Double? = null; var y: Double? = null
                                    while (m < pairs.size && pairs[m].code != 0) {
                                        when (pairs[m].code) {
                                            10 -> x = pairs[m].value.toDoubleOrNull()
                                            20 -> y = pairs[m].value.toDoubleOrNull()
                                        }
                                        m++
                                    }
                                    if (x != null && y != null) pts.add(Vec2(x * factor, y * factor))
                                    q = m
                                }
                                "SEQEND" -> ended = true
                                else -> ended = true // another entity started; polyline without SEQEND
                            }
                        } else q++
                    }
                    if (pts.size >= 2) polylines += Polyline(points = pts, closed = closed, layerId = layerName)
                    j = q
                    continue
                }
                "CIRCLE" -> {
                    val layerName = body.s(8) ?: "0"
                    layerNames.add(layerName)
                    val cx = body.d(10) ?: 0.0; val cy = body.d(20) ?: 0.0
                    val r = body.d(40) ?: 0.0
                    if (r > 0) polylines += Polyline(points = circlePoints(cx * factor, cy * factor, r * factor, 0.0, 360.0), closed = true, layerId = layerName)
                }
                "ARC" -> {
                    val layerName = body.s(8) ?: "0"
                    layerNames.add(layerName)
                    val cx = body.d(10) ?: 0.0; val cy = body.d(20) ?: 0.0
                    val r = body.d(40) ?: 0.0
                    val a0 = body.d(50) ?: 0.0
                    val a1 = body.d(51) ?: 360.0
                    if (r > 0) {
                        val sweep = if (a1 < a0) a1 + 360.0 - a0 else a1 - a0
                        polylines += Polyline(points = circlePoints(cx * factor, cy * factor, r * factor, a0, sweep), closed = false, layerId = layerName)
                    }
                }
            }
            j = k
        }

        // DXF is y-up; the editor canvas is y-down. Flip once on import so the
        // plan displays like a CAD viewer. DxfWriter flips back on export.
        val maxY = (segments.flatMap { listOf(it.a.y, it.b.y) } +
            polylines.flatMap { p -> p.points.map { it.y } }).maxOrNull() ?: 0.0
        val flippedSegments = segments.map {
            it.copy(a = it.a.copy(y = maxY - it.a.y), b = it.b.copy(y = maxY - it.b.y))
        }
        val flippedPolylines = polylines.map { p ->
            p.copy(points = p.points.map { it.copy(y = maxY - it.y) })
        }

        val layers = layerNames.map { name ->
            val kind = guessKind(name)
            Layer(id = name, name = name, kind = kind, colorArgb = aciToArgb(aciForKind(kind)))
        }
        return doc.copyWithTouch(
            segments = flippedSegments,
            polylines = flippedPolylines,
            layers = layers.ifEmpty { PlanDocument.defaultLayers() },
        )
    }

    private fun aciForKind(kind: LayerKind): Int = when (kind) {
        LayerKind.EXTERNAL_WALL -> 7
        LayerKind.INTERNAL_WALL -> 8
        LayerKind.DOOR -> 3
        LayerKind.WINDOW -> 5
        LayerKind.SLAB -> 4
        LayerKind.COLUMN -> 6
        LayerKind.FOOTING -> 31
        LayerKind.DIMENSION -> 2
        LayerKind.GENERIC -> 30
    }

    private fun guessKind(name: String): LayerKind {
        val n = name.lowercase()
        return when {
            n.contains("wall") && (n.contains("ext") || n.contains("out")) -> LayerKind.EXTERNAL_WALL
            n.contains("wall") -> LayerKind.INTERNAL_WALL
            n.contains("part") || n.contains("partition") || n.contains("internal") -> LayerKind.INTERNAL_WALL
            n.contains("door") -> LayerKind.DOOR
            n.contains("window") || n.contains("glaz") -> LayerKind.WINDOW
            n.contains("slab") || n.contains("floor") -> LayerKind.SLAB
            n.contains("column") || n.contains("col") -> LayerKind.COLUMN
            n.contains("foot") || n.contains("found") -> LayerKind.FOOTING
            n.contains("dim") -> LayerKind.DIMENSION
            else -> LayerKind.GENERIC
        }
    }

    private fun circlePoints(cx: Double, cy: Double, r: Double, startDeg: Double, sweepDeg: Double): List<Vec2> {
        val n = 48
        return (0..n).map { idx ->
            val a = Math.toRadians(startDeg + sweepDeg * idx / n)
            Vec2(cx + r * cos(a), cy + r * sin(a))
        }
    }

    /** ACI colour index -> ARGB, for a handful of common indices. */
    fun aciToArgb(aci: Int): Long = when (aci) {
        1 -> 0xFFD32F2F
        2 -> 0xFFF9A825
        3 -> 0xFF2E7D32
        4 -> 0xFF1565C0
        5 -> 0xFF0B5CD7
        6 -> 0xFF6A1B9A
        7 -> 0xFF111111
        8 -> 0xFF555555
        9 -> 0xFF9E9E9E
        30 -> 0xFF9A6A00
        31 -> 0xFF795548
        else -> 0xFF404040
    }.toLong()

    private fun List<Pair>.d(code: Int): Double? =
        firstOrNull { it.code == code }?.value?.toDoubleOrNull()

    private fun List<Pair>.i(code: Int): Int? =
        firstOrNull { it.code == code }?.value?.toIntOrNull()

    private fun List<Pair>.s(code: Int): String? =
        firstOrNull { it.code == code }?.value
}
