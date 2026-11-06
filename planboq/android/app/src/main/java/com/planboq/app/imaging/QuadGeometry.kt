package com.planboq.app.imaging

import com.planboq.app.data.geometry.PolygonMath
import com.planboq.app.data.geometry.Vec2

/**
 * Pure geometry for the 4-point page detection: ordering, validity, area.
 * The OpenCV contour search lives in [PageDetector]; all checks here are
 * JVM-testable.
 */
object QuadGeometry {

    /**
     * Orders four arbitrary quad corners as TL, TR, BR, BL using the
     * sum/difference trick (works for rotated pages too).
     */
    fun orderCorners(points: List<Vec2>): List<Vec2>? {
        if (points.size != 4) return null
        val tl = points.minByOrNull { it.x + it.y } ?: return null
        val br = points.maxByOrNull { it.x + it.y } ?: return null
        val tr = points.maxByOrNull { it.x - it.y } ?: return null
        val bl = points.minByOrNull { it.x - it.y } ?: return null
        val ordered = listOf(tl, tr, br, bl)
        return if (ordered.toSet().size == 4) ordered else null
    }

    fun area(quad: List<Vec2>): Double = PolygonMath.area(quad)

    /** Convexity via consistent cross-product signs around the loop. */
    fun isConvex(quad: List<Vec2>): Boolean {
        if (quad.size != 4) return false
        var positive = 0
        var negative = 0
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross > 1e-9) positive++ else if (cross < -1e-9) negative++
        }
        return positive == 4 || negative == 4
    }

    /** Rejects tiny or degenerate quads (slivers, self-intersections). */
    fun isUsablePage(
        quad: List<Vec2>,
        imageWidth: Int,
        imageHeight: Int,
        minAreaFraction: Double = 0.15,
    ): Boolean {
        if (quad.size != 4) return false
        if (!isConvex(quad)) return false
        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        if (imageArea <= 0.0) return false
        val a = area(quad)
        if (a < imageArea * minAreaFraction) return false

        // reject thin slivers: either side pair must be reasonably long
        val sides = (0 until 4).map { quad[it].distanceTo(quad[(it + 1) % 4]) }
        return sides.all { it > 10.0 } && sides.max() < 4.0 * sides.min()
    }
}
