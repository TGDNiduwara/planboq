package com.planboq.app.data.pdf

import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Vector PDF export: coloured vector lines, room names/areas, legend, scale
 * bar, title block (client/site/floor/date) and the mandatory provenance
 * disclaimer. Free-tier exports get a watermark.
 */
class PdfExporter {

    companion object {
        const val MM_TO_PT: Double = 72.0 / 25.4
        private const val SIDE_MM = 15.0
        private const val TOP_MM = 36.0
        private const val BOTTOM_MM = 20.0
        private const val MAX_PAGE_MM = 2000.0
    }

    fun export(doc: PlanDocument, watermark: Boolean): ByteArray {
        val bounds = doc.bounds()
        val minX = bounds?.first?.x ?: 0.0
        val minY = bounds?.first?.y ?: 0.0
        val w = bounds?.let { max(50.0, it.second.x - it.first.x) } ?: 200.0
        val h = bounds?.let { max(50.0, it.second.y - it.first.y) } ?: 200.0

        val pageW = min(w + 2 * SIDE_MM, MAX_PAGE_MM)
        val pageH = min(h + TOP_MM + BOTTOM_MM, MAX_PAGE_MM)

        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle((pageW * MM_TO_PT).toFloat(), (pageH * MM_TO_PT).toFloat()))
            document.addPage(page)

            fun px(x: Double): Float = ((x - minX + SIDE_MM) * MM_TO_PT).toFloat()
            fun py(y: Double): Float = ((pageH - TOP_MM - (y - minY)) * MM_TO_PT).toFloat()
            fun mmx(x: Double): Float = (x * MM_TO_PT).toFloat()
            fun mmy(y: Double): Float = (y * MM_TO_PT).toFloat()

            PDPageContentStream(document, page).use { cs ->
                // ---- title block ----
                val font = PDType1Font.HELVETICA
                val bold = PDType1Font.HELVETICA_BOLD
                text(cs, bold, 13f, mmx(SIDE_MM), mmy(pageH - 10.0), doc.name.take(70))
                var headerY = pageH - 15.0
                if (doc.client.isNotBlank()) {
                    text(cs, font, 8.5f, mmx(SIDE_MM), mmy(headerY), "Client: ${doc.client}")
                    headerY -= 4.5
                }
                if (doc.location.isNotBlank()) {
                    text(cs, font, 8.5f, mmx(SIDE_MM), mmy(headerY), "Location: ${doc.location}")
                    headerY -= 4.5
                }
                if (doc.floorName.isNotBlank()) {
                    text(cs, font, 8.5f, mmx(SIDE_MM), mmy(headerY), "Floor: ${doc.floorName}")
                    headerY -= 4.5
                }
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                text(cs, font, 8.5f, mmx(SIDE_MM), mmy(headerY), "Scale: ${doc.scale.label}")
                text(cs, font, 8.5f, mmx(SIDE_MM + 70.0), mmy(headerY), "Date: $date")
                if (doc.preparedBy.isNotBlank() || doc.checkedBy.isNotBlank() || doc.revision.isNotBlank()) {
                    val parts = buildList {
                        if (doc.preparedBy.isNotBlank()) add("Prepared: ${doc.preparedBy}")
                        if (doc.checkedBy.isNotBlank()) add("Checked: ${doc.checkedBy}")
                        if (doc.revision.isNotBlank()) add("Rev: ${doc.revision}")
                    }
                    text(cs, font, 8.5f, mmx(SIDE_MM + 70.0), mmy(headerY - 4.5), parts.joinToString("    "))
                }
                if (doc.notes.isNotBlank()) {
                    text(cs, font, 7.5f, mmx(SIDE_MM), mmy(headerY - 9.0), doc.notes.take(140))
                }

                // ---- rooms ----
                doc.rooms.forEach { room ->
                    if (room.points.size < 3) return@forEach
                    cs.setStrokingColor(0.62f, 0.62f, 0.62f)
                    cs.setLineWidth(0.25f)
                    val pts = room.points.map { px(it.x) to py(it.y) }
                    cs.moveTo(pts[0].first, pts[0].second)
                    pts.drop(1).forEach { (x, y) -> cs.lineTo(x, y) }
                    cs.closePath()
                    cs.stroke()
                    val center = com.planboq.app.data.geometry.PolygonMath.centroid(room.points)
                    val area = if (doc.scale.calibrated) room.areaM2(doc.scale.realMmPerPlanUnit) else null
                    val label = if (area != null) {
                        "${room.name}  ${"%.1f".format(Locale.US, area)} m2${if (room.excluded) " (excl.)" else ""}"
                    } else {
                        room.name
                    }
                    text(cs, bold, 6.5f, px(center.x), py(center.y), label)
                }

                // ---- geometry (coloured by layer kind) ----
                doc.segments.forEach { seg ->
                    val layer = doc.layerById(seg.layerId)
                    if (layer == null || !layer.visible) return@forEach
                    val (r, g, b) = colorFor(layer.kind, layer.colorArgb)
                    cs.setStrokingColor(r, g, b)
                    cs.setLineWidth(if (layer.kind == LayerKind.EXTERNAL_WALL) 0.5f else 0.32f)
                    cs.moveTo(px(seg.a.x), py(seg.a.y))
                    cs.lineTo(px(seg.b.x), py(seg.b.y))
                    cs.stroke()
                }
                doc.polylines.forEach { poly ->
                    val layer = doc.layerById(poly.layerId) ?: return@forEach
                    if (!layer.visible) return@forEach
                    val (r, g, b) = colorFor(layer.kind, layer.colorArgb)
                    cs.setStrokingColor(r, g, b)
                    cs.setLineWidth(0.3f)
                    val pts = if (poly.closed && poly.points.size > 2) poly.points + poly.points.first() else poly.points
                    if (pts.size >= 2) {
                        cs.moveTo(px(pts[0].x), py(pts[0].y))
                        pts.drop(1).forEach { cs.lineTo(px(it.x), py(it.y)) }
                        cs.stroke()
                    }
                }

                // ---- footer: legend, scale bar, disclaimer ----
                val legendY = pageH - BOTTOM_MM + 9.0
                text(cs, font, 7f, mmx(SIDE_MM), mmy(legendY + 4.5), "Legend")
                var lx = SIDE_MM
                listOf(
                    Trio("Walls", 0.08f, 0.08f, 0.08f),
                    Trio("Doors", 0.03f, 0.45f, 0.20f),
                    Trio("Windows", 0.03f, 0.24f, 0.60f),
                ).forEach { item ->
                    cs.setStrokingColor(item.r, item.g, item.b)
                    cs.setLineWidth(1.2f)
                    cs.moveTo(mmx(lx), mmy(legendY))
                    cs.lineTo(mmx(lx + 7.0), mmy(legendY))
                    cs.stroke()
                    text(cs, font, 6.5f, mmx(lx + 8.5), mmy(legendY - 2.0), item.label)
                    lx += 34.0
                }

                val barRealMm = if (doc.scale.calibrated) 1000.0 else 100.0
                val barPlanLen = barRealMm / doc.scale.realMmPerPlanUnit
                val barY = pageH - BOTTOM_MM + 3.0
                val barX1 = min(barPlanLen, pageW - 2 * SIDE_MM)
                cs.setStrokingColor(0f, 0f, 0f)
                cs.setLineWidth(0.5f)
                cs.moveTo(px(0.0), mmy(barY)); cs.lineTo(px(barX1), mmy(barY)); cs.stroke()
                listOf(0.0, barX1).forEach { x ->
                    cs.moveTo(px(x), mmy(barY - 1.5)); cs.lineTo(px(x), mmy(barY + 1.5)); cs.stroke()
                }
                text(
                    cs, font, 6.5f, px(0.0), mmy(barY + 2.5),
                    if (doc.scale.calibrated) "1 m" else "100 paper mm (NOT CALIBRATED)",
                )

                val disclaimer = if (doc.tracedFromImage) {
                    "Traced from image. Verify dimensions against site measurements before construction."
                } else {
                    "Verify all dimensions before use in construction."
                }
                text(cs, bold, 6.5f, mmx(SIDE_MM), mmy(pageH - 3.0), disclaimer)

                if (watermark) {
                    cs.setNonStrokingColor(0.85f, 0.85f, 0.85f)
                    text(cs, bold, 36f, px(w * 0.18), py(h * 0.5), "PLANBOQ FREE")
                    cs.setNonStrokingColor(0f, 0f, 0f)
                }
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    private data class Trio(val label: String, val r: Float, val g: Float, val b: Float)

    private fun colorFor(kind: LayerKind, argb: Long): Triple<Float, Float, Float> {
        val fixed = when (kind) {
            LayerKind.EXTERNAL_WALL -> Triple(0.08f, 0.08f, 0.08f)
            LayerKind.INTERNAL_WALL -> Triple(0.35f, 0.35f, 0.35f)
            LayerKind.DOOR -> Triple(0.03f, 0.45f, 0.20f)
            LayerKind.WINDOW -> Triple(0.03f, 0.24f, 0.60f)
            LayerKind.COLUMN -> Triple(0.35f, 0.20f, 0.55f)
            LayerKind.DIMENSION -> Triple(0.55f, 0.55f, 0.55f)
            else -> null
        }
        if (fixed != null) return fixed
        val r = ((argb shr 16) and 0xFF).toFloat() / 255f
        val g = ((argb shr 8) and 0xFF).toFloat() / 255f
        val b = (argb and 0xFF).toFloat() / 255f
        return if (r + g + b > 2.4f) Triple(0.3f, 0.3f, 0.3f) else Triple(r, g, b)
    }

    private fun text(cs: PDPageContentStream, font: PDType1Font, size: Float, x: Float, y: Float, value: String) {
        if (value.isBlank()) return
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(value.replace(Regex("[^\\x20-\\x7E]"), "?"))
        cs.endText()
    }
}
