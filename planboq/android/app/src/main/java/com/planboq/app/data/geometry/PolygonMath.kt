package com.planboq.app.data.geometry

import kotlin.math.abs

/** Planar polygon helpers (plan units, y-down or y-up - area is absolute). */
object PolygonMath {

    fun signedArea(points: List<Vec2>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            sum += p.x * q.y - q.x * p.y
        }
        return sum / 2.0
    }

    fun area(points: List<Vec2>): Double = abs(signedArea(points))

    fun perimeter(points: List<Vec2>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            sum += points[i].distanceTo(points[(i + 1) % points.size])
        }
        return sum
    }

    fun centroid(points: List<Vec2>): Vec2 {
        if (points.isEmpty()) return Vec2.zero()
        return Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
    }

    /** True when the point lies inside the polygon (ray casting). */
    fun contains(points: List<Vec2>, p: Vec2): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
