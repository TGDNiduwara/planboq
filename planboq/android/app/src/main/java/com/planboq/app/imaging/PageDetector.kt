package com.planboq.app.imaging

import android.graphics.Bitmap
import com.planboq.app.data.geometry.Vec2
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * CamScanner-style page detection: finds the largest convex quadrilateral in
 * the frame (the paper sheet) so the user only confirms a pre-placed box.
 *
 * All output corners are in ORIGINAL bitmap coordinates, ordered TL, TR, BR, BL.
 * Returns null when nothing page-like is found (caller falls back to defaults).
 */
class PageDetector {

    data class Detection(
        val corners: List<Vec2>,
        /** true when the quad came from the min-area-rect fallback */
        val usedFallback: Boolean,
    )

    fun detect(bitmap: Bitmap): Detection? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        try {
            val maxDim = 1600.0
            val scale = min(1.0, maxDim / max(src.cols(), src.rows()).toDouble())
            val small = Mat()
            Imgproc.resize(src, small, Size(src.cols() * scale, src.rows() * scale))

            val gray = Mat()
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = (small.cols() * small.rows()).toDouble()

            var best: List<Vec2>? = null
            var bestArea = 0.0
            var fallback: List<Vec2>? = null
            var fallbackArea = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < frameArea * 0.12) continue

                if (area > bestArea) {
                    val curve = MatOfPoint2f(*contour.toArray())
                    val approx = MatOfPoint2f()
                    val peri = Imgproc.arcLength(curve, true)
                    Imgproc.approxPolyDP(curve, approx, peri * 0.02, true)

                    if (approx.total() == 4L) {
                        val ordered = QuadGeometry.orderCorners(
                            approx.toArray().map { Vec2(it.x / scale, it.y / scale) },
                        )
                        if (ordered != null &&
                            QuadGeometry.isUsablePage(ordered, bitmap.width, bitmap.height, 0.12)
                        ) {
                            best = ordered
                            bestArea = area
                        }
                    }
                    curve.release()
                    approx.release()
                }

                // min-area-rect fallback from the biggest contour
                if (area > fallbackArea) {
                    val rect: RotatedRect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
                    val pts = arrayOf(Point(), Point(), Point(), Point())
                    rect.points(pts)
                    val ordered = QuadGeometry.orderCorners(pts.map { Vec2(it.x / scale, it.y / scale) })
                    if (ordered != null &&
                        QuadGeometry.isUsablePage(ordered, bitmap.width, bitmap.height, 0.12)
                    ) {
                        fallback = ordered
                        fallbackArea = area
                    }
                }
            }

            contours.forEach { it.release() }
            hierarchy.release()
            gray.release(); blurred.release(); edges.release(); small.release(); kernel.release()

            best?.let { return Detection(it, usedFallback = false) }
            fallback?.let { return Detection(it, usedFallback = true) }
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            src.release()
        }
    }
}
