package com.planboq.app.imaging

import com.planboq.app.boq.BoqSettings
import com.planboq.app.boq.WallCenterlines
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Room
import com.planboq.app.data.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detects closed rooms.
 *
 * Walls are drawn as THICK BANDS (wall centreline + measured thickness), not
 * as the two thin face lines - otherwise the space between the faces forms
 * corridors that merge rooms. Door/window gaps are bridged with virtual wall
 * bands; a small morphological close seals T-junction gaps (a partition face
 * typically stops at the perpendicular wall's face).
 *
 * Output polygons are in the document's plan units.
 */
class RoomDetector {

    companion object {
        private const val MAX_RASTER = 1400
        private const val MAX_GAP_MM = 1300.0
        private const val MIN_ROOM_FRACTION = 0.004 // of the plan area
        private const val JUNCTION_KERNEL_PX = 11

        /** Debug builds write the rasterised wall mask here for inspection. */
        @Volatile
        var debugMaskPath: String? = null
    }

    fun detect(doc: PlanDocument): List<Room> {
        val ratio = if (doc.scale.realMmPerPlanUnit > 0.0) doc.scale.realMmPerPlanUnit else 1.0
        val settings = BoqSettings()
        val centerlines = WallCenterlines.extract(doc.segments, doc.layers, doc.scale, settings)
        if (centerlines.size < 3) return emptyList()

        val minX = centerlines.minOf { min(it.a.x, it.b.x) } - 200.0 / ratio
        val maxX = centerlines.maxOf { max(it.a.x, it.b.x) } + 200.0 / ratio
        val minY = centerlines.minOf { min(it.a.y, it.b.y) } - 200.0 / ratio
        val maxY = centerlines.maxOf { max(it.a.y, it.b.y) } + 200.0 / ratio
        val width = (maxX - minX).coerceAtLeast(1.0)
        val height = (maxY - minY).coerceAtLeast(1.0)
        val pad = max(width, height) * 0.02
        val planW = width + 2 * pad
        val planH = height + 2 * pad

        val rasterScale = min(1.0, MAX_RASTER / max(planW, planH))
        val cols = (planW * rasterScale).toInt().coerceAtLeast(8)
        val rows = (planH * rasterScale).toInt().coerceAtLeast(8)

        fun px(p: Vec2): Point = Point((p.x - minX + pad) * rasterScale, (p.y - minY + pad) * rasterScale)

        val mask = Mat.zeros(rows, cols, CvType.CV_8UC1)

        // ---- solid wall bands from centre lines ----
        centerlines.forEach { line ->
            val bandPlan = if (line.thicknessMeasured || doc.scale.calibrated) {
                // thicknessMm is real mm; convert back to plan units
                if (ratio > 0.0) line.thicknessMm / ratio else 0.0
            } else {
                0.0 // uncalibrated: no reliable thickness, thin line
            }
            val thicknessPx = max(3, (bandPlan * rasterScale).toInt())
            Imgproc.line(mask, px(line.a), px(line.b), Scalar(255.0), thicknessPx)
        }

        // ---- bridge collinear gaps: doors / windows ----
        val maxGapPlan = if (doc.scale.calibrated) MAX_GAP_MM / ratio else max(width, height) * 0.12
        val collinearTol = max(4.0, max(width, height) * 0.006)

        fun bandThicknessPx(index: Int): Int {
            val l = centerlines[index]
            val bandPlan = if (doc.scale.calibrated && ratio > 0.0) l.thicknessMm / ratio else 0.0
            return max(3, (bandPlan * rasterScale).toInt())
        }

        fun bridge(horizontal: Boolean) {
            val list = centerlines.withIndex().filter { (_, l) ->
                val dx = abs(l.b.x - l.a.x)
                val dy = abs(l.b.y - l.a.y)
                val angle = Math.toDegrees(kotlin.math.atan2(dy, dx))
                if (horizontal) angle <= 2.5 || angle >= 177.5 else abs(angle - 90.0) <= 2.5
            }
            for (i in list.indices) {
                val (idxA, a) = list[i]
                for (j in i + 1 until list.size) {
                    val (idxB, b) = list[j]
                    val constA = if (horizontal) (a.a.y + a.b.y) / 2.0 else (a.a.x + a.b.x) / 2.0
                    val constB = if (horizontal) (b.a.y + b.b.y) / 2.0 else (b.a.x + b.b.x) / 2.0
                    if (abs(constA - constB) > collinearTol) continue

                    fun axis(v: Vec2) = if (horizontal) v.x else v.y
                    var bestGap = Double.MAX_VALUE
                    var pa = a.a
                    var pb = b.a
                    for (ea in listOf(a.a, a.b)) for (eb in listOf(b.a, b.b)) {
                        val gap = abs(axis(ea) - axis(eb))
                        if (gap < bestGap) { bestGap = gap; pa = ea; pb = eb }
                    }
                    if (bestGap <= 0.0 || bestGap > maxGapPlan) continue
                    val thickness = max(bandThicknessPx(idxA), bandThicknessPx(idxB))
                    Imgproc.line(mask, px(pa), px(pb), Scalar(255.0), thickness)
                }
            }
        }

        bridge(horizontal = true)
        bridge(horizontal = false)

        // ---- seal T-junction gaps (partition face stops short of the wall) ----
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(JUNCTION_KERNEL_PX.toDouble(), JUNCTION_KERNEL_PX.toDouble()),
        )
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        // ---- flood fill free space ----
        val free = Mat()
        Core.bitwise_not(mask, free)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(free, labels, stats, centroids, 8, CvType.CV_32S)

        val planAreaPx = cols.toDouble() * rows.toDouble()
        val minAreaPx = planAreaPx * MIN_ROOM_FRACTION

        val rooms = mutableListOf<Room>()
        // label 0 is the background of `free` = the WALL pixels themselves
        for (label in 1 until count) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            if (area < minAreaPx) continue
            val left = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
            val top = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
            val w = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val h = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            if (left <= 1 || top <= 1 || left + w >= cols - 1 || top + h >= rows - 1) continue

            val component = Mat()
            Core.compare(labels, Scalar(label.toDouble()), component, Core.CMP_EQ)
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(component, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) }
            if (biggest != null) {
                val curve = MatOfPoint2f(*biggest.toArray())
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.02, true)
                val pts = approx.toArray()
                if (pts.size >= 4) {
                    val poly = pts.map { p ->
                        Vec2(p.x / rasterScale + minX - pad, p.y / rasterScale + minY - pad)
                    }
                    rooms += Room(name = "Room ${rooms.size + 1}", points = poly)
                }
                curve.release(); approx.release()
            }
            contours.forEach { it.release() }
            hierarchy.release()
            component.release()
        }

        debugMaskPath?.let { path ->
            try {
                org.opencv.imgcodecs.Imgcodecs.imwrite(path, mask)
            } catch (t: Throwable) {
                // debug only
            }
        }

        mask.release(); free.release(); labels.release(); stats.release(); centroids.release()
        return rooms
    }
}
