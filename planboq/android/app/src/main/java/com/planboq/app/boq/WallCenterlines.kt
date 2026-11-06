package com.planboq.app.boq

import com.planboq.app.data.geometry.GeometrySnap
import com.planboq.app.data.geometry.Layer
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.ScaleState
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floor plans draw walls as a pair of parallel face lines. Summing traced
 * lines would double-count brickwork and plaster, so quantities are computed
 * from CENTRELINEs obtained by pairing faces ~one wall thickness apart.
 *
 * Pure Kotlin; the drawing itself is never modified.
 */
data class Centerline(
    val a: Vec2,
    val b: Vec2,
    val thicknessMm: Double,
    val kind: LayerKind,
    val thicknessMeasured: Boolean,
) {
    fun lengthPlanUnits(): Double = a.distanceTo(b)
}

object WallCenterlines {

    const val MIN_THICKNESS_MM = 40.0
    const val MAX_THICKNESS_MM = 350.0

    private const val MIN_OVERLAP_FRACTION = 0.35
    private const val AXIS_TOLERANCE = 2.5 // degrees; anything else is skipped

    fun extract(
        segments: List<Segment>,
        layers: List<Layer>,
        scale: ScaleState,
        settings: BoqSettings,
    ): List<Centerline> {
        val ratio = if (scale.realMmPerPlanUnit > 0.0) scale.realMmPerPlanUnit else 1.0
        val wallSegments = segments.filter { seg ->
            val kind = layers.firstOrNull { it.id == seg.layerId }?.kind
            kind == LayerKind.EXTERNAL_WALL || kind == LayerKind.INTERNAL_WALL
        }
        if (wallSegments.isEmpty()) return emptyList()

        // pairing distances are in plan units: mm -> plan units via the scale
        val minGap = MIN_THICKNESS_MM / ratio
        val maxGap = MAX_THICKNESS_MM / ratio

        val out = mutableListOf<Centerline>()
        val used = BooleanArray(wallSegments.size)

        fun layerOf(seg: Segment): Layer? = layers.firstOrNull { it.id == seg.layerId }

        fun kindOf(seg: Segment): LayerKind =
            layerOf(seg)?.kind ?: LayerKind.INTERNAL_WALL

        fun defaultThicknessMm(kind: LayerKind): Double =
            if (kind == LayerKind.EXTERNAL_WALL) settings.externalWallThicknessMm
            else settings.internalWallThicknessMm

        /** Explicit per-layer override wins over both measured pairs and defaults. */
        fun thicknessFor(seg: Segment, measuredMm: Double?): Pair<Double, Boolean> {
            val override = layerOf(seg)?.thicknessMm
            return when {
                override != null -> override to true
                measuredMm != null -> measuredMm to true
                else -> defaultThicknessMm(kindOf(seg)) to false
            }
        }

        // --- horizontal pairs ---
        val horizontal = wallSegments.withIndex().filter { (_, s) -> isHorizontal(s) }
        val vertical = wallSegments.withIndex().filter { (_, s) -> isVertical(s) }

        fun process(entries: List<IndexedValue<Segment>>, horizontalAxis: Boolean) {
            val sorted = entries.sortedBy { (_, s) ->
                if (horizontalAxis) (s.a.y + s.b.y) / 2.0 else (s.a.x + s.b.x) / 2.0
            }
            for (i in sorted.indices) {
                val (idxI, si) = sorted[i]
                if (used[idxI]) continue
                val ci = constCoord(si, horizontalAxis)
                val li = span(si, horizontalAxis)
                var bestJ = -1
                var bestScore = Double.MAX_VALUE
                for (j in i + 1 until sorted.size) {
                    val (idxJ, sj) = sorted[j]
                    if (used[idxJ]) continue
                    val gap = abs(constCoord(sj, horizontalAxis) - ci)
                    if (gap < minGap) continue
                    if (gap > maxGap) break // sorted by const: farther entries only grow
                    val lj = span(sj, horizontalAxis)
                    val overlap = min(li.second, lj.second) - max(li.first, lj.first)
                    val minLen = min(li.second - li.first, lj.second - lj.first)
                    if (minLen <= 0 || overlap < minLen * MIN_OVERLAP_FRACTION) continue
                    val kind = kindOf(si)
                    val score = abs(gap * ratio - defaultThicknessMm(kind))
                    if (score < bestScore) { bestScore = score; bestJ = idxJ }
                }
                if (bestJ >= 0) {
                    used[idxI] = true
                    used[bestJ] = true
                    val sj = wallSegments[bestJ]
                    val cj = constCoord(sj, horizontalAxis)
                    val lj = span(sj, horizontalAxis)
                    val centerConst = (ci + cj) / 2.0
                    val lo = min(li.first, lj.first)
                    val hi = max(li.second, lj.second)
                    val kind = kindOf(si)
                    val measured = abs(cj - ci) * ratio
                    val (thickness, measuredFlag) = thicknessFor(si, measured)
                    out += centerline(lo, hi, centerConst, horizontalAxis, thickness, kind, measured = measuredFlag)
                }
            }
        }

        process(horizontal, horizontalAxis = true)
        process(vertical, horizontalAxis = false)

        // unpaired wall lines become centre lines (layer override or default)
        wallSegments.forEachIndexed { idx, seg ->
            if (used[idx]) return@forEachIndexed
            val kind = kindOf(seg)
            val (thickness, measuredFlag) = thicknessFor(seg, null)
            out += Centerline(
                a = seg.a,
                b = seg.b,
                thicknessMm = thickness,
                kind = kind,
                thicknessMeasured = measuredFlag,
            )
        }
        return out
    }

    private fun centerline(
        lo: Double,
        hi: Double,
        const: Double,
        horizontal: Boolean,
        thickness: Double,
        kind: LayerKind,
        measured: Boolean,
    ): Centerline = if (horizontal) {
        Centerline(Vec2(lo, const), Vec2(hi, const), thickness, kind, measured)
    } else {
        Centerline(Vec2(const, lo), Vec2(const, hi), thickness, kind, measured)
    }

    private fun constCoord(s: Segment, horizontal: Boolean): Double =
        if (horizontal) (s.a.y + s.b.y) / 2.0 else (s.a.x + s.b.x) / 2.0

    private fun span(s: Segment, horizontal: Boolean): Pair<Double, Double> =
        if (horizontal) {
            min(s.a.x, s.b.x) to max(s.a.x, s.b.x)
        } else {
            min(s.a.y, s.b.y) to max(s.a.y, s.b.y)
        }

    private fun isHorizontal(s: Segment): Boolean {
        val angle = Math.toDegrees(kotlin.math.atan2(abs(s.b.y - s.a.y), abs(s.b.x - s.a.x)))
        return angle <= AXIS_TOLERANCE
    }

    private fun isVertical(s: Segment): Boolean {
        val angle = Math.toDegrees(kotlin.math.atan2(abs(s.b.x - s.a.x), abs(s.b.y - s.a.y)))
        return angle <= AXIS_TOLERANCE
    }
}
