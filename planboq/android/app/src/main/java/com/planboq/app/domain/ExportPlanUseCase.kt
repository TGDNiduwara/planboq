package com.planboq.app.domain

import com.planboq.app.data.dxf.DxfWriter
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.pdf.PdfExporter
import com.planboq.app.data.repo.PlanDocumentStore
import java.io.File
import java.io.StringWriter

/** Writes a PlanDocument out as vector PDF or DXF into the exports folder. */
class ExportPlanUseCase(private val store: PlanDocumentStore) {

    enum class Format(val extension: String, val mime: String) {
        PDF("pdf", "application/pdf"),
        DXF("dxf", "application/dxf"),
    }

    data class Result(val file: File, val watermarked: Boolean)

    fun export(doc: PlanDocument, format: Format, watermark: Boolean): Result {
        val bytes = when (format) {
            Format.PDF -> PdfExporter().export(doc, watermark)
            Format.DXF -> {
                val sw = StringWriter()
                DxfWriter().write(doc, sw)
                sw.toString().toByteArray(Charsets.ISO_8859_1)
            }
        }
        // avoid "plan.dxf.pdf": drop a known source extension before appending
        val baseName = doc.name.let { n ->
            val dot = n.lastIndexOf('.')
            if (dot > 0 && n.length - dot <= 5) n.substring(0, dot) else n
        }
        val safeName = baseName.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(60).ifBlank { "plan" }
        val file = File(store.exportsDir(), "$safeName.${format.extension}")
        file.writeBytes(bytes)
        return Result(file, watermark)
    }
}
