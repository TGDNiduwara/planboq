package com.planboq.app.data.pdf

import android.graphics.Path
import android.graphics.PointF
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.ScaleState
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.SourceType
import com.planboq.app.data.geometry.Vec2
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.IOException

/**
 * Reads vector geometry from page 1 of a PDF by walking the content stream
 * (m / l / c / re / h operators). Curves are flattened to short chords.
 *
 * Output coordinates are millimetres ON PAPER; real-world scale comes from
 * [com.planboq.app.domain.CalibrateScaleUseCase] afterwards.
 *
 * NOTE: PDFBox-Android uses android.graphics internally, so this class only
 * runs on a device/emulator (instrumented tests), not in JVM unit tests.
 */
class VectorPdfExtractor {

    companion object {
        const val PT_TO_MM: Double = 25.4 / 72.0
        private const val CURVE_STEPS = 10
    }

    fun extractFirstPage(bytes: ByteArray, fileName: String): PlanDocument {
        PDDocument.load(bytes).use { doc ->
            if (doc.numberOfPages == 0) return newDocument(fileName)
            val page: PDPage = doc.getPage(0)
            val engine = CollectingEngine(page)
            engine.processPage(page)
            val pageHeightMm = page.mediaBox.height * PT_TO_MM

            val planDoc = newDocument(fileName)
            val layerId = PlanDocument.defaultLayerId(planDoc.layers)
            val segments = engine.results.map { (p1, p2) ->
                // PDF user space has Y up; flip so the editor shows the plan upright.
                Segment(
                    a = Vec2(p1.x * PT_TO_MM, pageHeightMm - p1.y * PT_TO_MM),
                    b = Vec2(p2.x * PT_TO_MM, pageHeightMm - p2.y * PT_TO_MM),
                    layerId = layerId,
                )
            }
            return planDoc.copyWithTouch(segments = segments)
        }
    }

    private fun newDocument(fileName: String) = PlanDocument(
        name = fileName,
        sourceType = SourceType.VECTOR_PDF,
        scale = ScaleState(calibrated = false, realMmPerPlanUnit = 1.0, label = "paper mm"),
    )

    private data class Pt(val x: Float, val y: Float)

    /** Gathers painted path segments in PDF user space (already CTM-transformed). */
    private class CollectingEngine(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val results = mutableListOf<Pair<Pt, Pt>>()
        private var current: Pt? = null
        private var subpathStart: Pt? = null
        private val pending = mutableListOf<Pair<Pt, Pt>>()

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            pending += Pt(p0.x, p0.y) to Pt(p1.x, p1.y)
            pending += Pt(p1.x, p1.y) to Pt(p2.x, p2.y)
            pending += Pt(p2.x, p2.y) to Pt(p3.x, p3.y)
            pending += Pt(p3.x, p3.y) to Pt(p0.x, p0.y)
            current = Pt(p3.x, p3.y)
            subpathStart = current
        }

        override fun drawImage(pdImage: PDImage) = Unit

        override fun clip(windingRule: Path.FillType) = Unit

        override fun moveTo(x: Float, y: Float) {
            current = Pt(x, y)
            subpathStart = current
        }

        override fun lineTo(x: Float, y: Float) {
            val c = current ?: Pt(x, y).also { moveTo(x, y) }
            pending += c to Pt(x, y)
            current = Pt(x, y)
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val start = current ?: Pt(x1, y1).also { moveTo(x1, y1) }
            var px = start.x; var py = start.y
            for (i in 1..CURVE_STEPS) {
                val t = i.toDouble() / CURVE_STEPS
                val mt = 1 - t
                val bx = mt * mt * mt * start.x + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x3
                val by = mt * mt * mt * start.y + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y3
                pending += Pt(px, py) to Pt(bx.toFloat(), by.toFloat())
                px = bx.toFloat(); py = by.toFloat()
            }
            current = Pt(x3, y3)
        }

        override fun getCurrentPoint(): PointF? =
            current?.let { PointF(it.x, it.y) }

        override fun closePath() {
            val c = current
            val s = subpathStart
            if (c != null && s != null) pending += c to s
            current = s
        }

        override fun endPath() {
            pending.clear()
            current = null
            subpathStart = null
        }

        override fun strokePath() {
            results += pending
            pending.clear()
        }

        override fun fillPath(windingRule: Path.FillType) {
            // Outline of a filled shape is still useful geometry for a plan.
            results += pending
            pending.clear()
        }

        override fun fillAndStrokePath(windingRule: Path.FillType) {
            results += pending
            pending.clear()
        }

        override fun shadingFill(shadingName: COSName) = Unit
    }
}
