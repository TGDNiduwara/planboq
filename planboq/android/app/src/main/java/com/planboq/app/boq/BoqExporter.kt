package com.planboq.app.boq

import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Room
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * "Almost ready" BOQ deliverable:
 * XLSX with Cover / Room Schedule / BOQ / Assumptions sheets, and a PDF with
 * item codes, assumptions, readiness statement and signature lines.
 */
class BoqExporter {

    companion object {
        private const val DISCLAIMER =
            "Quantities are estimates derived from a traced floor plan. " +
                "Verify against site measurements and structural drawings before ordering or construction."
    }

    private fun money(v: Double): String = "%.2f".format(Locale.US, v)

    private fun qty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(Locale.US, v)

    private fun sourceLabel(source: QuantitySource): String = when (source) {
        QuantitySource.MEASURED -> "Measured"
        QuantitySource.ASSUMED -> "Assumed"
        QuantitySource.MANUAL -> "Manual"
    }

    private fun dateStamp(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun roomArea(room: Room, plan: PlanDocument?): String {
        if (plan == null || !plan.scale.calibrated) return "-"
        val area = room.areaM2(plan.scale.realMmPerPlanUnit) ?: return "-"
        return money(area)
    }

    private fun roomRows(plan: PlanDocument?): List<List<String>> {
        if (plan == null || plan.rooms.isEmpty()) return emptyList()
        return plan.rooms.mapIndexed { index, room ->
            listOf(
                (index + 1).toString(),
                room.name + if (room.excluded) " (excluded)" else "",
                room.kind.name.lowercase().replace('_', ' '),
                roomArea(room, plan),
            )
        }
    }

    /** Assumptions and unresolved gaps printed in both formats. */
    fun assumptions(doc: BoqDocument, plan: PlanDocument?, readiness: Readiness?): List<String> {
        val s = doc.settings
        val list = mutableListOf<String>()
        list += "Measurement standard: ${s.standard.label}; currency ${s.currency}"
        list += "Assumed storey height ${qty(s.storeyHeightM)} m, slab ${qty(s.slabThicknessM)} m, " +
            "${s.storeys} storey(s)"
        list += "Wall thickness external ${s.externalWallThicknessMm.toInt()} mm / " +
            "internal ${s.internalWallThicknessMm.toInt()} mm (override per layer in the editor)"
        list += "Wastage ${s.wastagePercent}%; contingency ${s.contingencyPercent}%; " +
            "opening types: ${s.openingTypes.joinToString { it.name }}"
        if (s.structural.enabled) {
            list += "Structural quantities are ESTIMATED from collected data " +
                "(${s.structural.foundationType.label}, ${s.structural.columnCount} columns, " +
                "steel ${qty(s.structural.steelKgPerM3)} kg/m3) - verify with structural drawings"
        } else {
            list += "Structural quantities are MANUAL and currently not estimated"
        }
        if (plan?.tracedFromImage == true) {
            list += "Geometry traced from an image - dimensions are approximate"
        }
        if (doc.acceptedGaps.isNotEmpty()) {
            list += "Reviewed assumptions: ${doc.acceptedGaps.sorted().joinToString(", ")}"
        }
        readiness?.gaps?.forEach { gap ->
            val prefix = when (gap.severity) {
                GapSeverity.BLOCKING -> "MISSING (blocking): "
                GapSeverity.IMPORTANT -> "Unresolved assumption: "
                GapSeverity.OPTIONAL -> "Optional gap: "
            }
            list += prefix + gap.title + " - " + gap.question +
                if (gap.detail.isNotBlank()) " (${gap.detail})" else ""
        }
        return list
    }

    // ------------------------------------------------------------------ XLSX

    fun exportXlsx(
        doc: BoqDocument,
        plan: PlanDocument?,
        projectLabel: String,
        out: File,
        readiness: Readiness? = null,
    ) {
        val wb = XlsxWriter("Cover")
        val moneyStyle = 2

        // ---------------- cover ----------------
        val cover = wb
        cover.setColumnWidths(listOf(26.0, 60.0))
        cover.addRow(XlsxWriter.Cell.Text("BILL OF QUANTITIES", style = 3))
        cover.addRow(XlsxWriter.Cell.Text(projectLabel, style = 1))
        cover.addRow()
        fun coverLine(label: String, value: String) {
            if (value.isNotBlank()) cover.addRow(XlsxWriter.Cell.Text(label), XlsxWriter.Cell.Text(value))
        }
        coverLine("Client", plan?.client ?: "")
        coverLine("Location", plan?.location ?: "")
        coverLine("Floor", plan?.floorName ?: "")
        coverLine("Standard", doc.settings.standard.label)
        coverLine("Currency", doc.settings.currency)
        coverLine("Prepared by", plan?.preparedBy ?: "")
        coverLine("Checked by", plan?.checkedBy ?: "")
        coverLine("Revision", plan?.revision ?: "")
        coverLine("Date", dateStamp())
        coverLine("Scale", plan?.scale?.label ?: "-")
        cover.addRow()
        val readinessText = if (readiness != null) {
            "Readiness ${readiness.score}% - ${readiness.blocking} blocking, " +
                "${readiness.important} assumptions, ${readiness.optional} optional gaps"
        } else "Readiness not evaluated"
        cover.addRow(XlsxWriter.Cell.Text(readinessText, style = 1))
        cover.addRow()
        cover.addRow(XlsxWriter.Cell.Text("Subtotal", style = 1), XlsxWriter.Cell.Number(doc.total(), style = 4))
        if (doc.settings.contingencyPercent != 0.0) {
            cover.addRow(
                XlsxWriter.Cell.Text("Contingency ${doc.settings.contingencyPercent}%", style = 1),
                XlsxWriter.Cell.Number(doc.contingencyAmount(), style = 4),
            )
        }
        cover.addRow(XlsxWriter.Cell.Text("GRAND TOTAL", style = 1), XlsxWriter.Cell.Number(doc.grandTotal(), style = 4))
        cover.addRow()
        cover.addRow(XlsxWriter.Cell.Text(DISCLAIMER))

        // ---------------- room schedule ----------------
        val roomsSheet = wb.addSheet("Room Schedule")
        roomsSheet.setColumnWidths(listOf(5.0, 40.0, 14.0, 12.0))
        roomsSheet.addRow(XlsxWriter.Cell.Text("ROOM SCHEDULE", style = 3))
        roomsSheet.addRow()
        roomsSheet.addRow(
            XlsxWriter.Cell.Text("No", style = 1),
            XlsxWriter.Cell.Text("Room", style = 1),
            XlsxWriter.Cell.Text("Type", style = 1),
            XlsxWriter.Cell.Text("Area (m2)", style = 1),
        )
        val rooms = roomRows(plan)
        if (rooms.isEmpty()) {
            roomsSheet.addRow(XlsxWriter.Cell.Text("No rooms detected"))
        } else {
            rooms.forEach { row ->
                roomsSheet.addRow(
                    XlsxWriter.Cell.Text(row[0]),
                    XlsxWriter.Cell.Text(row[1]),
                    XlsxWriter.Cell.Text(row[2]),
                    XlsxWriter.Cell.Text(row[3]),
                )
            }
        }

        // ---------------- BOQ ----------------
        val boqSheet = wb.addSheet("BOQ")
        boqSheet.setColumnWidths(listOf(5.0, 7.0, 50.0, 11.0, 7.0, 10.0, 10.0, 13.0))
        boqSheet.addRow(XlsxWriter.Cell.Text("BILL OF QUANTITIES", style = 3))
        boqSheet.addRow(XlsxWriter.Cell.Text(projectLabel))
        boqSheet.addRow()
        boqSheet.addRow(
            XlsxWriter.Cell.Text("No", style = 1),
            XlsxWriter.Cell.Text("Code", style = 1),
            XlsxWriter.Cell.Text("Description", style = 1),
            XlsxWriter.Cell.Text("Basis", style = 1),
            XlsxWriter.Cell.Text("Unit", style = 1),
            XlsxWriter.Cell.Text("Qty", style = 1),
            XlsxWriter.Cell.Text("Rate", style = 1),
            XlsxWriter.Cell.Text("Amount", style = 1),
        )
        var number = 1
        doc.items.groupBy { it.category }.forEach { (category, items) ->
            boqSheet.addRow(listOf(XlsxWriter.Cell.Text(category, style = 1)))
            items.forEach { item ->
                val note = if (item.note.isNotBlank()) " - ${item.note}" else ""
                boqSheet.addRow(
                    XlsxWriter.Cell.Text((number++).toString()),
                    XlsxWriter.Cell.Text(item.code),
                    XlsxWriter.Cell.Text(item.description + note),
                    XlsxWriter.Cell.Text(sourceLabel(item.source)),
                    XlsxWriter.Cell.Text(item.unit),
                    XlsxWriter.Cell.Number(item.quantity, moneyStyle),
                    XlsxWriter.Cell.Number(item.rate, moneyStyle),
                    XlsxWriter.Cell.Number(item.amount(), moneyStyle),
                )
            }
            boqSheet.addRow(
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text("Subtotal - $category", style = 1),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Number(doc.categoryTotals()[category] ?: 0.0, style = 4),
            )
        }
        boqSheet.addRow()
        boqSheet.addRow(
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text("SUBTOTAL (${doc.settings.currency})", style = 1),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Number(doc.total(), style = 4),
        )
        if (doc.settings.contingencyPercent != 0.0) {
            boqSheet.addRow(
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text("Contingency ${doc.settings.contingencyPercent}%", style = 1),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Text(""),
                XlsxWriter.Cell.Number(doc.contingencyAmount(), style = 4),
            )
        }
        boqSheet.addRow(
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text("GRAND TOTAL (${doc.settings.currency})", style = 1),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Number(doc.grandTotal(), style = 4),
        )
        boqSheet.addRow()
        boqSheet.addRow(listOf(XlsxWriter.Cell.Text(DISCLAIMER)))

        // ---------------- assumptions ----------------
        val assumptionsSheet = wb.addSheet("Assumptions")
        assumptionsSheet.setColumnWidths(listOf(120.0))
        assumptionsSheet.addRow(XlsxWriter.Cell.Text("ASSUMPTIONS & MISSING DATA", style = 3))
        assumptionsSheet.addRow()
        assumptions(doc, plan, readiness).forEach { line ->
            assumptionsSheet.addRow(XlsxWriter.Cell.Text("- $line"))
        }
        assumptionsSheet.addRow()
        assumptionsSheet.addRow(XlsxWriter.Cell.Text(DISCLAIMER))

        out.parentFile?.mkdirs()
        out.writeBytes(wb.toBytes())
    }

    // ------------------------------------------------------------------- PDF

    fun exportPdf(
        doc: BoqDocument,
        plan: PlanDocument?,
        projectLabel: String,
        out: File,
        readiness: Readiness? = null,
    ) {
        val pageW = 842f
        val pageH = 595f
        val margin = 30f

        val colNo = margin
        val colCode = margin + 20f
        val colDesc = margin + 52f
        val colBasis = margin + 340f
        val colUnit = margin + 415f
        val colQty = margin + 452f
        val colRate = margin + 510f
        val colAmount = margin + 585f
        val descWidth = 280f

        PDDocument().use { pdf ->
            var page = newPage(pdf, pageW, pageH)
            var cs = PDPageContentStream(pdf, page)
            var y = pageH - margin

            fun text(font: PDType1Font, size: Float, x: Float, yy: Float, value: String) {
                textAt(cs, font, size, x, yy, value)
            }

            fun line(x0: Float, yy: Float, x1: Float) {
                drawLine(cs, x0, yy, x1, yy)
            }

            fun tableHeader(yy: Float) {
                text(PDType1Font.HELVETICA_BOLD, 8f, colNo, yy, "No")
                text(PDType1Font.HELVETICA_BOLD, 8f, colCode, yy, "Code")
                text(PDType1Font.HELVETICA_BOLD, 8f, colDesc, yy, "Description")
                text(PDType1Font.HELVETICA_BOLD, 8f, colBasis, yy, "Basis")
                text(PDType1Font.HELVETICA_BOLD, 8f, colUnit, yy, "Unit")
                text(PDType1Font.HELVETICA_BOLD, 8f, colQty, yy, "Qty")
                text(PDType1Font.HELVETICA_BOLD, 8f, colRate, yy, "Rate")
                text(PDType1Font.HELVETICA_BOLD, 8f, colAmount, yy, "Amount")
            }

            fun startPage(withHeader: Boolean) {
                footer(cs, margin)
                cs.close()
                page = newPage(pdf, pageW, pageH)
                cs = PDPageContentStream(pdf, page)
                y = pageH - margin
                if (withHeader) {
                    tableHeader(y)
                    y -= 6f
                    line(margin, y, pageW - margin)
                    y -= 12f
                }
            }

            fun ensureSpace(needed: Float) {
                if (y - needed < margin + 46f) startPage(withHeader = true)
            }

            fun row(cells: List<String>) {
                val descLines = wrap(PDType1Font.HELVETICA, 8f, cells[1], descWidth)
                val rowHeight = max(12f, descLines.size * 10f + 4f)
                ensureSpace(rowHeight)
                text(PDType1Font.HELVETICA, 8f, colNo, y, cells[0])
                text(PDType1Font.HELVETICA, 8f, colCode, y, cells[2])
                descLines.forEachIndexed { i, lineText ->
                    text(PDType1Font.HELVETICA, 8f, colDesc, y - i * 10f, lineText)
                }
                text(PDType1Font.HELVETICA, 8f, colBasis, y, cells[3])
                text(PDType1Font.HELVETICA, 8f, colUnit, y, cells[4])
                text(PDType1Font.HELVETICA, 8f, colQty, y, cells[5])
                text(PDType1Font.HELVETICA, 8f, colRate, y, cells[6])
                text(PDType1Font.HELVETICA, 8f, colAmount, y, cells[7])
                y -= rowHeight
            }

            // ---- cover header ----
            text(PDType1Font.HELVETICA_BOLD, 14f, margin, y, projectLabel)
            y -= 18f
            plan?.client?.takeIf { it.isNotBlank() }?.let { text(PDType1Font.HELVETICA, 9f, margin, y, "Client: $it"); y -= 12f }
            plan?.location?.takeIf { it.isNotBlank() }?.let { text(PDType1Font.HELVETICA, 9f, margin, y, "Location: $it"); y -= 12f }
            plan?.floorName?.takeIf { it.isNotBlank() }?.let { text(PDType1Font.HELVETICA, 9f, margin, y, "Floor: $it"); y -= 12f }
            text(PDType1Font.HELVETICA, 9f, margin, y, "Standard: ${doc.settings.standard.label}")
            text(PDType1Font.HELVETICA, 9f, margin + 230f, y, "Scale: ${plan?.scale?.label ?: "-"}")
            text(PDType1Font.HELVETICA, 9f, margin + 400f, y, "Date: ${dateStamp()}")
            y -= 12f
            if (!plan?.preparedBy.isNullOrBlank() || !plan?.checkedBy.isNullOrBlank()) {
                text(
                    PDType1Font.HELVETICA, 9f, margin, y,
                    "Prepared: ${plan?.preparedBy.orEmpty()}    Checked: ${plan?.checkedBy.orEmpty()}    Rev: ${plan?.revision.orEmpty()}",
                )
                y -= 12f
            }
            if (readiness != null) {
                text(
                    PDType1Font.HELVETICA_BOLD, 9f, margin, y,
                    "Readiness ${readiness.score}%  (${readiness.blocking} blocking, ${readiness.important} assumptions)",
                )
                y -= 14f
            }
            y -= 6f

            // ---- room schedule ----
            val rooms = roomRows(plan)
            if (rooms.isNotEmpty()) {
                text(PDType1Font.HELVETICA_BOLD, 10f, margin, y, "ROOM SCHEDULE")
                y -= 14f
                text(PDType1Font.HELVETICA_BOLD, 8f, colNo, y, "No")
                text(PDType1Font.HELVETICA_BOLD, 8f, colDesc, y, "Room")
                text(PDType1Font.HELVETICA_BOLD, 8f, colBasis, y, "Type")
                text(PDType1Font.HELVETICA_BOLD, 8f, colUnit, y, "Area (m2)")
                y -= 6f
                line(margin, y, pageW - margin)
                y -= 12f
                rooms.forEach { rowData ->
                    ensureSpace(12f)
                    text(PDType1Font.HELVETICA, 8f, colNo, y, rowData[0])
                    text(PDType1Font.HELVETICA, 8f, colDesc, y, rowData[1])
                    text(PDType1Font.HELVETICA, 8f, colBasis, y, rowData[2])
                    text(PDType1Font.HELVETICA, 8f, colUnit, y, rowData[3])
                    y -= 12f
                }
                y -= 10f
            }

            // ---- BOQ ----
            ensureSpace(70f)
            text(PDType1Font.HELVETICA_BOLD, 10f, margin, y, "BILL OF QUANTITIES")
            y -= 14f
            tableHeader(y)
            y -= 6f
            line(margin, y, pageW - margin)
            y -= 12f

            var number = 1
            doc.items.groupBy { it.category }.forEach { (category, items) ->
                ensureSpace(14f)
                text(PDType1Font.HELVETICA_BOLD, 8.5f, margin, y, category)
                y -= 12f
                items.forEach { item ->
                    val note = if (item.note.isNotBlank()) " - ${item.note}" else ""
                    row(
                        listOf(
                            (number++).toString(),
                            item.description + note,
                            item.code,
                            sourceLabel(item.source),
                            item.unit,
                            qty(item.quantity),
                            money(item.rate),
                            money(item.amount()),
                        ),
                    )
                }
                ensureSpace(14f)
                text(PDType1Font.HELVETICA_BOLD, 8.5f, colDesc, y, "Subtotal - $category")
                text(PDType1Font.HELVETICA_BOLD, 8.5f, colAmount, y, money(doc.categoryTotals()[category] ?: 0.0))
                y -= 14f
            }

            ensureSpace(20f)
            line(margin, y, pageW - margin)
            y -= 16f
            text(PDType1Font.HELVETICA_BOLD, 10f, colDesc, y, "SUBTOTAL (${doc.settings.currency})")
            text(PDType1Font.HELVETICA_BOLD, 10f, colAmount, y, money(doc.total()))
            y -= 16f
            if (doc.settings.contingencyPercent != 0.0) {
                text(PDType1Font.HELVETICA, 9f, colDesc, y, "Contingency ${doc.settings.contingencyPercent}%")
                text(PDType1Font.HELVETICA, 9f, colAmount, y, money(doc.contingencyAmount()))
                y -= 16f
            }
            text(PDType1Font.HELVETICA_BOLD, 11f, colDesc, y, "GRAND TOTAL (${doc.settings.currency})")
            text(PDType1Font.HELVETICA_BOLD, 11f, colAmount, y, money(doc.grandTotal()))
            y -= 24f

            // ---- assumptions ----
            ensureSpace(40f)
            text(PDType1Font.HELVETICA_BOLD, 10f, margin, y, "BASIS OF MEASUREMENT & ASSUMPTIONS")
            y -= 14f
            assumptions(doc, plan, readiness).forEach { assumption ->
                val lines = wrap(PDType1Font.HELVETICA, 7.5f, "- $assumption", pageW - 2 * margin)
                ensureSpace(lines.size * 9f + 4f)
                lines.forEachIndexed { index, lineText ->
                    text(PDType1Font.HELVETICA, 7.5f, margin, y - index * 9f, lineText)
                }
                y -= lines.size * 9f + 2f
            }

            // ---- signatures ----
            ensureSpace(50f)
            y -= 18f
            val sigWidth = 150f
            listOf(
                "Prepared by" to (plan?.preparedBy ?: ""),
                "Checked by" to (plan?.checkedBy ?: ""),
                "Approved by" to "",
            ).forEachIndexed { index, (label, name) ->
                val x = margin + index * (sigWidth + 60f)
                drawLine(cs, x, y, x + sigWidth, y)
                text(PDType1Font.HELVETICA, 7f, x, y - 9f, label + if (name.isNotBlank()) ": $name" else "")
            }

            footer(cs, margin)
            cs.close()
            out.parentFile?.mkdirs()
            pdf.save(out)
        }
    }

    private fun newPage(pdf: PDDocument, w: Float, h: Float): PDPage {
        val page = PDPage(PDRectangle(w, h))
        pdf.addPage(page)
        return page
    }

    private fun footer(cs: PDPageContentStream, margin: Float) {
        textAt(cs, PDType1Font.HELVETICA_BOLD, 7f, margin, margin + 12f, DISCLAIMER.split(". ").first() + ".")
        textAt(cs, PDType1Font.HELVETICA, 7f, margin, margin, "Generated by PlanBOQ - verify before construction.")
    }

    private fun drawLine(cs: PDPageContentStream, x0: Float, y0: Float, x1: Float, y1: Float) {
        cs.setLineWidth(0.4f)
        cs.moveTo(x0, y0)
        cs.lineTo(x1, y1)
        cs.stroke()
    }

    private fun textAt(cs: PDPageContentStream, font: PDType1Font, size: Float, x: Float, y: Float, value: String) {
        if (value.isBlank()) return
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(value.replace(Regex("[^\\x20-\\x7E]"), "?"))
        cs.endText()
    }

    private fun wrap(font: PDType1Font, size: Float, value: String, maxWidth: Float): List<String> {
        val words = value.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            val width = try {
                font.getStringWidth(candidate) / 1000f * size
            } catch (t: Throwable) {
                candidate.length * size * 0.5f
            }
            if (width > maxWidth && current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return if (lines.isEmpty()) listOf("") else lines
    }
}
