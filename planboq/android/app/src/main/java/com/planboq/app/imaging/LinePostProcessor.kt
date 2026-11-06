package com.planboq.app.imaging

import com.planboq.app.data.geometry.GeometrySnap
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cleans raw detected lines into plan-like geometry. Pure Kotlin so it is
 * unit-testable on the JVM (OpenCV only produces the input list).
 *
 * Pipeline: snap near-H/V lines to exact H/V -> merge collinear fragments ->
 * weld close endpoints so corners meet.
 */
class LinePostProcessor {

    data class Config(
        /** max perpendicular offset for two lines to count as the same line */
        val collinearTol: Double = 2.0,
        /** max gap between collinear fragments that are joined */
        val gapTol: Double = 15.0,
        /** endpoints closer than this get welded together */
        val weldTol: Double = 3.0,
        /** ignore lines shorter than this */
        val minLineLength: Double = 6.0,
    )

    fun process(raw: List<Segment>, config: Config = Config()): List<Segment> {
        if (raw.isEmpty()) return raw

        val snapped = raw.map { GeometrySnap.snapToHV(it) }
            .filter { it.length() >= config.minLineLength }

        val merged = mergeCollinear(snapped, config)
        val welded = weldEndpoints(merged, config.weldTol)
        return welded.filter { it.length() >= config.minLineLength }
    }

    /**
     * Groups near-horizontal and near-vertical lines by their constant axis
     * value (within [Config.collinearTol]) and merges overlapping/adjacent
     * spans into single segments.
     */
    private fun mergeCollinear(segments: List<Segment>, config: Config): List<Segment> {
        val out = mutableListOf<Segment>()

        data class Span(val lo: Double, val hi: Double, val const: Double, val src: Segment)

        fun mergeAxis(list: List<Span>, horizontal: Boolean): List<Segment> {
            if (list.isEmpty()) return emptyList()
            val sorted = list.sortedBy { it.lo }
            val result = mutableListOf<Segment>()
            var curLo = sorted[0].lo
            var curHi = sorted[0].hi
            var curConst = sorted[0].const
            var curSrc = sorted[0].src
            for (i in 1 until sorted.size) {
                val s = sorted[i]
                val sameLine = abs(s.const - curConst) <= config.collinearTol
                val overlapsOrNear = s.lo <= curHi + config.gapTol
                if (sameLine && overlapsOrNear) {
                    if (s.hi > curHi) {
                        // extend; keep the source with the longer span for metadata
                        curHi = s.hi
                        curSrc = s.src
                    }
                } else {
                    result += toSegment(curLo, curHi, curConst, horizontal, curSrc)
                    curLo = s.lo; curHi = s.hi; curConst = s.const; curSrc = s.src
                }
            }
            result += toSegment(curLo, curHi, curConst, horizontal, curSrc)
            return result
        }

        val horizontal = mutableListOf<Span>()
        val vertical = mutableListOf<Span>()
        val other = mutableListOf<Segment>()

        segments.forEach { s ->
            when {
                GeometrySnap.isNearHorizontal(s) -> {
                    val y = (s.a.y + s.b.y) / 2.0
                    horizontal += Span(min(s.a.x, s.b.x), max(s.a.x, s.b.x), y, s)
                }
                GeometrySnap.isNearVertical(s) -> {
                    val x = (s.a.x + s.b.x) / 2.0
                    vertical += Span(min(s.a.y, s.b.y), max(s.a.y, s.b.y), x, s)
                }
                else -> other += s
            }
        }

        out += mergeAxis(horizontal, horizontal = true)
        out += mergeAxis(vertical, horizontal = false)
        out += other
        return out
    }

    private data class Span(val lo: Double, val hi: Double, val const: Double, val src: Segment)

    private fun toSegment(lo: Double, hi: Double, const: Double, horizontal: Boolean, src: Segment): Segment =
        if (horizontal) {
            src.copy(a = Vec2(lo, const), b = Vec2(hi, const))
        } else {
            src.copy(a = Vec2(const, lo), b = Vec2(const, hi))
        }

    /** Welds endpoints closer than [tol] using a coarse spatial grid. */
    private fun weldEndpoints(segments: List<Segment>, tol: Double): List<Segment> {
        val cell = tol
        fun key(v: Vec2): Pair<Int, Int> = Pair((v.x / cell).roundToInt(), (v.y / cell).roundToInt())

        val grid = HashMap<Pair<Int, Int>, Vec2>()
        val result = ArrayList<Segment>(segments.size)

        fun snap(v: Vec2): Vec2 {
            val (cx, cy) = key(v)
            for (dx in -1..1) for (dy in -1..1) {
                val existing = grid[Pair(cx + dx, cy + dy)] ?: continue
                if (v.distanceTo(existing) <= tol) return existing
            }
            grid[Pair(cx, cy)] = v
            return v
        }

        segments.forEach { s ->
            result += s.copy(a = snap(s.a), b = snap(s.b))
        }
        return result
    }
}
