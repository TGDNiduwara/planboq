package com.planboq.app.imaging

import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Closes wall corners: every endpoint is extended to the nearest
 * non-parallel segment whose infinite line passes within [tolerance].
 * Pure Kotlin so it is unit-tested.
 */
object WallJoiner {

    private const val MIN_ANGLE_DEG = 20.0
    private const val MAX_EXTEND_FACTOR = 1.5 // never grow a wall by more than 50%

    fun join(segments: List<Segment>, tolerance: Double, iterations: Int = 2): List<Segment> {
        if (segments.size < 2) return segments
        var current = segments.toMutableList()

        repeat(iterations) {
            var changed = false
            current = current.mapIndexed { index, seg ->
                var a = seg.a
                var b = seg.b

                val aJoined = nearestJoin(segments, index, a, tolerance)
                if (aJoined != null) { a = aJoined; changed = true }
                val bJoined = nearestJoin(segments, index, b, tolerance)
                if (bJoined != null) { b = bJoined; changed = true }

                if (a != seg.a || b != seg.b) seg.copy(a = a, b = b) else seg
            }.toMutableList()
            if (!changed) return current
        }
        return current
    }

    private fun nearestJoin(segments: List<Segment>, selfIndex: Int, point: Vec2, tolerance: Double): Vec2? {
        var best: Vec2? = null
        var bestDist = Double.MAX_VALUE

        for (i in segments.indices) {
            if (i == selfIndex) continue
            val other = segments[i]
            if (other.length() < 1e-6) continue
            val self = segments[selfIndex]
            if (self.length() < 1e-6) continue

            val angle = angleBetween(self, other)
            if (angle < MIN_ANGLE_DEG || abs(angle - 180.0) < MIN_ANGLE_DEG) continue

            val candidate = perpendicularProjection(point, other) ?: continue
            val dist = point.distanceTo(candidate)
            if (dist > tolerance * MAX_EXTEND_FACTOR) continue
            // do not extend beyond the far end of the target segment
            val t = projectionParam(candidate, other)
            if (t < -0.25 || t > 1.25) continue
            if (dist < bestDist) { bestDist = dist; best = candidate }
        }
        return best
    }

    /** Foot of the perpendicular from [p] onto the infinite line through [seg]. */
    fun perpendicularProjection(p: Vec2, seg: Segment): Vec2? {
        val abx = seg.b.x - seg.a.x
        val aby = seg.b.y - seg.a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-12) return null
        val t = ((p.x - seg.a.x) * abx + (p.y - seg.a.y) * aby) / lenSq
        return Vec2(seg.a.x + abx * t, seg.a.y + aby * t)
    }

    private fun projectionParam(p: Vec2, seg: Segment): Double {
        val abx = seg.b.x - seg.a.x
        val aby = seg.b.y - seg.a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-12) return Double.NaN
        return ((p.x - seg.a.x) * abx + (p.y - seg.a.y) * aby) / lenSq
    }

    fun angleBetween(a: Segment, b: Segment): Double {
        val ax = a.b.x - a.a.x
        val ay = a.b.y - a.a.y
        val bx = b.b.x - b.a.x
        val by = b.b.y - b.a.y
        val na = sqrt(ax * ax + ay * ay)
        val nb = sqrt(bx * bx + by * by)
        if (na < 1e-12 || nb < 1e-12) return 0.0
        val cos = ((ax * bx + ay * by) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }
}
