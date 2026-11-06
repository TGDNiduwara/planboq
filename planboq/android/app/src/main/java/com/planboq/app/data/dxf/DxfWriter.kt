package com.planboq.app.data.dxf

import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import java.io.Writer
import java.util.Locale

/**
 * ASCII DXF R12 (AC1009) writer. R12 opens in every AutoCAD/BricsCAD/LibreCAD
 * release and needs no handles or subclasses, which keeps the file tiny and robust.
 */
class DxfWriter {

    fun write(doc: PlanDocument, out: Writer) {
        val locale = Locale.US
        fun num(v: Double): String = String.format(locale, "%.4f", v)

        val bounds = doc.bounds()
        val minX = bounds?.first?.x ?: 0.0
        val minY = bounds?.first?.y ?: 0.0
        val maxX = bounds?.second?.x ?: 100.0
        val maxY = bounds?.second?.y ?: 100.0

        // PlanDocument is y-down (screen); DXF is y-up. Flip on the way out so
        // a DXF import -> export round trip preserves the original coordinates.
        fun flipY(y: Double): Double = maxY - y
        val planH = (maxY - minY).coerceAtLeast(1.0)

        fun pair(code: Int, value: String) { out.write(code.toString()); out.write("\n"); out.write(value); out.write("\n") }
        fun d(code: Int, value: Double) = pair(code, num(value))
        fun start(name: String) = pair(0, name)
        fun section(name: String) { start("SECTION"); pair(2, name) }
        fun endsec() = start("ENDSEC")

        start("COMMENT"); pair(999, "Exported by PlanBOQ")

        // ---------------- HEADER ----------------
        section("HEADER")
        pair(9, "\$ACADVER"); pair(1, "AC1009")
        pair(9, "\$INSUNITS"); pair(70, "4") // millimetres
        pair(9, "\$EXTMIN"); d(10, minX); d(20, 0.0); d(30, 0.0)
        pair(9, "\$EXTMAX"); d(10, maxX); d(20, planH); d(30, 0.0)
        endsec()

        // ---------------- TABLES (layers) ----------------
        section("TABLES")
        start("TABLE"); pair(2, "LAYER"); pair(70, doc.layers.size.toString())
        doc.layers.forEach { layer ->
            start("LAYER")
            pair(2, dxfLayerName(layer.name))
            pair(70, "0")
            pair(62, aciFor(layer.kind).toString())
            pair(6, "CONTINUOUS")
        }
        start("ENDTAB")
        start("ENDSEC")

        // ---------------- ENTITIES ----------------
        section("ENTITIES")

        doc.segments.forEach { seg ->
            val layer = doc.layerById(seg.layerId)
            val name = dxfLayerName(layer?.name ?: "PLANBOQ")
            start("LINE")
            pair(8, name)
            d(10, seg.a.x); d(20, flipY(seg.a.y)); d(30, 0.0)
            d(11, seg.b.x); d(21, flipY(seg.b.y)); d(31, 0.0)
        }

        doc.polylines.forEach { poly ->
            val layer = doc.layerById(poly.layerId)
            val name = dxfLayerName(layer?.name ?: "PLANBOQ")
            start("POLYLINE")
            pair(8, name)
            pair(66, "1") // vertices follow
            pair(70, if (poly.closed) "1" else "0")
            d(10, 0.0); d(20, 0.0); d(30, 0.0)
            poly.points.forEach { p ->
                start("VERTEX")
                pair(8, name)
                d(10, p.x); d(20, flipY(p.y)); d(30, 0.0)
            }
            start("SEQEND")
        }

        // Scale bar + disclaimer, laid out in exported (y-up) space below the plan
        val barY = -maxOf(planH * 0.03, 500.0)
        val barX0 = minX
        val barX1 = barX0 + 1000.0 // one metre bar at current plan units
        start("LINE"); pair(8, "PLANBOQ_ANNOT")
        d(10, barX0); d(20, barY); d(30, 0.0)
        d(11, barX1); d(21, barY); d(31, 0.0)
        listOf(barX0, barX1).forEach { x ->
            start("LINE"); pair(8, "PLANBOQ_ANNOT")
            d(10, x); d(20, barY - 50.0); d(30, 0.0)
            d(11, x); d(21, barY + 50.0); d(31, 0.0)
        }
        start("TEXT"); pair(8, "PLANBOQ_ANNOT")
        d(10, barX0); d(20, barY + 80.0); d(30, 0.0)
        d(40, maxOf(planH * 0.015, 100.0))
        pair(1, "Scale 1 m (calibrate before use)")
        start("TEXT"); pair(8, "PLANBOQ_ANNOT")
        d(10, barX0); d(20, barY - 250.0); d(30, 0.0)
        d(40, maxOf(planH * 0.012, 80.0))
        pair(1, if (doc.tracedFromImage) "Traced from image. Verify before construction." else "Exported by PlanBOQ - verify dimensions before construction.")

        endsec()
        start("EOF")
    }

    private fun dxfLayerName(name: String): String =
        name.replace(Regex("[<>/\\\\\":;?*|=`,]"), "_").take(31).ifEmpty { "PLANBOQ" }

    private fun aciFor(kind: LayerKind): Int = when (kind) {
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
}
