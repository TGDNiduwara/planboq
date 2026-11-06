package com.planboq.app.boq

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal multi-sheet XLSX (OOXML) writer: bold rows, a money format, column
 * widths. No external dependency.
 */
class XlsxWriter(defaultSheetName: String = "BOQ") {

    sealed class Cell {
        data class Text(val value: String, val style: Int = 0) : Cell()
        data class Number(val value: Double, val style: Int = 2) : Cell()
    }

    data class Row(val cells: List<Cell>, val style: Int = 0)

    inner class Sheet(val name: String) {
        val rows = mutableListOf<Row>()
        var columnWidthList: List<Double> = emptyList()

        fun addRow(vararg cells: Cell) { rows += Row(cells.toList()) }
        fun addRow(cells: List<Cell>, style: Int = 0) { rows += Row(cells, style) }
        fun setColumnWidths(widths: List<Double>) { columnWidthList = widths }
    }

    private val sheets = mutableListOf<Sheet>().apply { add(Sheet(defaultSheetName)) }

    fun addSheet(name: String): Sheet {
        val sheet = Sheet(name)
        sheets += sheet
        return sheet
    }

    // Convenience: operate on the first sheet
    fun addRow(vararg cells: Cell) = sheets[0].addRow(*cells)
    fun addRow(cells: List<Cell>, style: Int = 0) = sheets[0].addRow(cells, style)
    fun setColumnWidths(widths: List<Double>) = sheets[0].setColumnWidths(widths)

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entry(zip, "[Content_Types].xml", contentTypes())
            entry(zip, "_rels/.rels", rootRels())
            entry(zip, "xl/workbook.xml", workbook())
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            entry(zip, "xl/styles.xml", styles())
            sheets.forEachIndexed { index, sheet ->
                entry(zip, "xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet))
            }
        }
        return out.toByteArray()
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sheetXml(sheet: Sheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (sheet.columnWidthList.isNotEmpty()) {
            sb.append("<cols>")
            sheet.columnWidthList.forEachIndexed { i, w ->
                sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val r = rowIndex + 1
            sb.append("""<row r="$r">""")
            row.cells.forEachIndexed { colIndex, cell ->
                val ref = columnLetter(colIndex) + r
                when (cell) {
                    is Cell.Text -> {
                        val style = when {
                            cell.style != 0 -> cell.style
                            row.style != 0 -> row.style
                            else -> 0
                        }
                        val styleAttr = if (style != 0) " s=\"$style\"" else ""
                        sb.append("""<c r="$ref"$styleAttr t="inlineStr"><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>""")
                    }
                    is Cell.Number -> {
                        val style = if (row.style != 0) row.style else cell.style
                        sb.append("""<c r="$ref" s="$style"><v>${format(cell.value)}</v></c>""")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    private fun format(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(java.util.Locale.US, v)

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun contentTypes(): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sheets.indices.forEach { i ->
            sb.append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        sb.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        sb.append("</Types>")
        return sb.toString()
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("<sheets>")
        sheets.forEachIndexed { i, sheet ->
            sb.append("""<sheet name="${escape(sheet.name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        sb.append("</sheets></workbook>")
        return sb.toString()
    }

    private fun workbookRels(): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.indices.forEach { i ->
            sb.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
        }
        sb.append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="3">""" +
            """<font><sz val="10"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="10"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="14"/><name val="Calibri"/></font>""" +
            """</fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border/></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="5">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="4" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""
}
