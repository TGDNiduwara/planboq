package com.planboq.app.imaging

import android.graphics.Bitmap
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device photo -> line geometry pipeline:
 *
 * 1. Perspective correction from the 4 corners the user tapped (TL, TR, BR, BL)
 * 2. Grayscale + illumination flattening (divide by blurred estimate)
 * 3. Adaptive threshold -> binary plan
 * 4. Line detection: LSD when available, HoughLinesP fallback
 * 5. [LinePostProcessor] cleans the raw lines
 *
 * Output plan units are PIXELS of the corrected image; the user calibrates
 * real-world scale later by tapping two points with a known dimension.
 *
 * Call [ensureOpenCv] once from Application startup.
 */
class PlanImageProcessor(private val postProcessor: LinePostProcessor = LinePostProcessor()) {

    data class Result(
        val correctedBitmap: Bitmap,
        val segments: List<Segment>,
        val widthPx: Int,
        val heightPx: Int,
        /** which detector produced the lines, for the UI status line */
        val detector: String,
    )

    fun ensureOpenCv(): Boolean = try {
        org.opencv.android.OpenCVLoader.initLocal()
    } catch (t: Throwable) {
        false
    }

    /**
     * @param corners bitmap-space corners in order TL, TR, BR, BL.
     */
    fun process(bitmap: Bitmap, corners: List<Vec2>, defaultLayerId: String): Result {
        require(corners.size == 4) { "4 corners required (TL, TR, BR, BL)" }

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // --- 1. perspective warp to a rectangle ---
        val outW = max(corners[0].distanceTo(corners[1]), corners[3].distanceTo(corners[2])).roundToInt().coerceAtLeast(16)
        val outH = max(corners[0].distanceTo(corners[3]), corners[1].distanceTo(corners[2])).roundToInt().coerceAtLeast(16)
        // cap resolution to keep memory sane on phones
        val scale = min(1.0, 2400.0 / max(outW, outH))
        val dstW = (outW * scale).toInt()
        val dstH = (outH * scale).toInt()

        val srcPts = MatOfPoint2f(
            Point(corners[0].x, corners[0].y),
            Point(corners[1].x, corners[1].y),
            Point(corners[2].x, corners[2].y),
            Point(corners[3].x, corners[3].y),
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(dstW.toDouble(), 0.0),
            Point(dstW.toDouble(), dstH.toDouble()), Point(0.0, dstH.toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(dstW.toDouble(), dstH.toDouble()))
        src.release(); srcPts.release(); dstPts.release(); transform.release()

        // --- 2. cleanup ---
        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
        val illumination = Mat()
        Imgproc.GaussianBlur(gray, illumination, Size(0.0, 0.0), max(dstW, dstH) / 120.0)
        val flattened = Mat()
        Core.divide(gray, illumination, flattened, 255.0)

        var blockSize = max(dstW, dstH) / 40
        blockSize = max(7, blockSize)
        if (blockSize % 2 == 0) blockSize += 1
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            flattened, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
            blockSize, 10.0,
        )

        // --- 3. text / small-blob removal (room labels must not become lines) ---
        maskTextNoise(binary)

        // --- 4. deskew: align walls to the H/V axes ---
        val skew = estimateDeskewDegrees(binary)
        if (abs(skew) >= 0.4) {
            val center = Point(dstW / 2.0, dstH / 2.0)
            val rotation = Imgproc.getRotationMatrix2D(center, skew, 1.0)
            val rotated = Mat()
            Imgproc.warpAffine(
                flattened, rotated, rotation, Size(dstW.toDouble(), dstH.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0),
            )
            Imgproc.adaptiveThreshold(
                rotated, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                blockSize, 10.0,
            )
            maskTextNoise(binary)
            rotated.release(); rotation.release()
        }

        // preview bitmap of the cleaned plan
        val previewRgba = Mat()
        Imgproc.cvtColor(binary, previewRgba, Imgproc.COLOR_GRAY2RGBA)
        val cleanedBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(previewRgba, cleanedBitmap)
        previewRgba.release()

        // --- 5. line detection + cleanup + corner joining ---
        val rawSegments = detectLines(binary, defaultLayerId)
        val cleaned = postProcessor.process(rawSegments)
        val segments = WallJoiner.join(
            cleaned,
            tolerance = max(6.0, min(dstW, dstH) * 0.012),
        )

        gray.release(); illumination.release(); flattened.release()
        binary.release(); warped.release()

        return Result(cleanedBitmap, segments, dstW, dstH, detectorName)
    }

    private var detectorName = "Hough"

    /**
     * Blank out small text-like connected components (labels, dimension
     * numbers) so LSD/Hough do not turn letters into hundreds of tiny lines.
     */
    private fun maskTextNoise(binary: Mat) {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(
                binary, labels, stats, centroids, 8, CvType.CV_32S,
            )
            val imageArea = binary.cols().toDouble() * binary.rows().toDouble()
            val maxWidth = binary.cols() * 0.25
            val maxHeight = binary.rows() * 0.08
            for (i in 0 until count) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area > imageArea * 0.002) continue // real geometry, keep
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]
                if (w > maxWidth || h > maxHeight) continue // long/thin lines, keep
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0]
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0]
                Imgproc.rectangle(
                    binary,
                    Point(x, y),
                    Point(x + w, y + h),
                    Scalar(0.0),
                    Imgproc.FILLED,
                )
            }
        } catch (t: Throwable) {
            // masking is best-effort
        } finally {
            labels.release(); stats.release(); centroids.release()
        }
    }

    /**
     * Estimates the dominant H/V tilt of the plan lines (degrees, image
     * coords). Positive means the walls tilt clockwise on screen, so the
     * image is rotated counter-clockwise by the returned value to correct it.
     */
    private fun estimateDeskewDegrees(binary: Mat): Double {
        val lines = Mat()
        try {
            val minDim = min(binary.cols(), binary.rows())
            Imgproc.HoughLinesP(
                binary, lines, 1.0, Math.PI / 360.0, 90,
                minDim * 0.25, 8.0,
            )
            val weighted = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until lines.rows()) {
                val l = lines.get(i, 0)
                if (l.size < 4) continue
                val dx = l[2] - l[0]
                val dy = l[3] - l[1]
                val length = Math.hypot(dx, dy)
                if (length < minDim * 0.15) continue
                var angle = Math.toDegrees(Math.atan2(dy, dx))
                while (angle >= 90.0) angle -= 180.0
                while (angle < -90.0) angle += 180.0
                val deviation = when {
                    abs(angle) <= 12.0 -> angle
                    abs(angle - 90.0) <= 12.0 -> angle - 90.0
                    abs(angle + 90.0) <= 12.0 -> angle + 90.0
                    else -> null
                } ?: continue
                weighted += deviation to length
            }
            if (weighted.isEmpty()) return 0.0
            weighted.sortBy { it.first }
            val total = weighted.sumOf { it.second }
            var acc = 0.0
            for ((deviation, weight) in weighted) {
                acc += weight
                if (acc >= total / 2.0) return deviation
            }
            return weighted.last().first
        } catch (t: Throwable) {
            return 0.0
        } finally {
            lines.release()
        }
    }

    private fun detectLines(binary: Mat, defaultLayerId: String): List<Segment> {
        val lines = Mat()
        var raw = mutableListOf<Segment>()

        try {
            val lsd = Imgproc.createLineSegmentDetector()
            lsd.detect(binary, lines)
            detectorName = "LSD"
        } catch (t: Throwable) {
            // LSD unavailable in this build -> HoughLinesP fallback
            val minLen = min(binary.cols(), binary.rows()) * 0.02
            Imgproc.HoughLinesP(binary, lines, 1.0, Math.PI / 720.0, 60, minLen, 5.0)
            detectorName = "Hough"
        }

        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0)
            if (l.size < 4) continue
            raw += Segment(
                a = Vec2(l[0].toDouble(), l[1].toDouble()),
                b = Vec2(l[2].toDouble(), l[3].toDouble()),
                layerId = defaultLayerId,
            )
        }
        lines.release()

        // keep the longest N lines so the editor stays responsive
        val cap = 6000
        if (raw.size > cap) {
            raw = raw.sortedByDescending { it.length() }.take(cap).toMutableList()
        }
        return raw
    }
}
