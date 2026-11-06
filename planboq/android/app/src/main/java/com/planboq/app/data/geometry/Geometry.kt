package com.planboq.app.data.geometry

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * PlanBOQ internal geometry format.
 *
 * Every importer (DXF, vector PDF, traced photo) converts into a [PlanDocument],
 * and every exporter (PDF, DXF) reads from it. Phase 2 tagging will attach
 * quantity categories to [Layer]s, so keep this model stable.
 *
 * Coordinates are stored in "plan units" which are millimetres ON PAPER for
 * image/PDF sources, or real-world millimetres for DXF sources. A calibrated
 * [ScaleState] converts plan units to real-world millimetres.
 */
@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(f: Double) = Vec2(x * f, y * f)

    fun distanceTo(o: Vec2): Double = hypot(x - o.x, y - o.y)

    companion object {
        fun zero() = Vec2(0.0, 0.0)
    }
}

/** Element categories. Phase 2 quantity rules will key off this enum. */
enum class LayerKind {
    EXTERNAL_WALL,
    INTERNAL_WALL,
    DOOR,
    WINDOW,
    SLAB,
    COLUMN,
    FOOTING,
    DIMENSION,
    GENERIC,
}

@Serializable
data class Layer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: LayerKind = LayerKind.GENERIC,
    val colorArgb: Long = 0xFF404040,
    val visible: Boolean = true,
    /** Explicit wall thickness override; null = use BOQ defaults / measured pairs. */
    val thicknessMm: Double? = null,
)

@Serializable
data class Segment(
    val id: String = UUID.randomUUID().toString(),
    val a: Vec2,
    val b: Vec2,
    val layerId: String,
    /** Opening family (see BoqSettings.openingTypes) for door/window tagging. */
    val openingTypeId: String? = null,
) {
    fun length(): Double = a.distanceTo(b)
}

/** Dense polyline (DXF LWPOLYLINE/POLYLINE, circle/arc approximations). */
@Serializable
data class Polyline(
    val id: String = UUID.randomUUID().toString(),
    val points: List<Vec2>,
    val closed: Boolean = false,
    val layerId: String,
) {
    /** Total centreline length, including the closing edge when [closed]. */
    fun length(): Double {
        var sum = 0.0
        for (i in 0 until points.size - 1) sum += points[i].distanceTo(points[i + 1])
        if (closed && points.size > 2) sum += points.last().distanceTo(points.first())
        return sum
    }
}

enum class RoomKind { ROOM, BATH, KITCHEN, BALCONY, HALL, OTHER }

/** A closed floor area detected from the plan (or entered later in Phase 2). */
@Serializable
data class Room(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<Vec2>,
    val kind: RoomKind = RoomKind.ROOM,
    /** Excluded rooms stay on the drawing but drop out of BOQ quantities. */
    val excluded: Boolean = false,
) {
    /** Area in square plan units (see PolygonMath.area). */
    fun areaPlanUnits(): Double = PolygonMath.area(points)

    /** Area in m² under the document scale; null when the scale is uncalibrated. */
    fun areaM2(realMmPerPlanUnit: Double): Double? {
        if (realMmPerPlanUnit <= 0.0) return null
        val sideM = realMmPerPlanUnit / 1000.0
        return areaPlanUnits() * sideM * sideM
    }

    fun perimeterPlanUnits(): Double = PolygonMath.perimeter(points)
}

@Serializable
data class ScaleReference(
    val p1: Vec2,
    val p2: Vec2,
    val knownLengthMm: Double,
)

/**
 * Scale calibration. [realMmPerPlanUnit] converts plan-unit distances to real
 * distances; e.g. a 1:100 drawing has ratio 100. Uncalibrated documents use 1.
 */
@Serializable
data class ScaleState(
    val calibrated: Boolean = false,
    val realMmPerPlanUnit: Double = 1.0,
    val label: String = "uncalibrated",
    val reference: ScaleReference? = null,
)

enum class SourceType { DXF, VECTOR_PDF, PHOTO }

/**
 * The one format everything speaks.
 */
@Serializable
data class PlanDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled plan",
    val sourceType: SourceType = SourceType.PHOTO,
    val layers: List<Layer> = defaultLayers(),
    val segments: List<Segment> = emptyList(),
    val polylines: List<Polyline> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val scale: ScaleState = ScaleState(),
    /** True when the geometry came from tracing a photo/scan -> mandatory disclaimer on exports. */
    val tracedFromImage: Boolean = false,
    /** Corrected capture image shown as a review underlay, relative to the project dir. */
    val backgroundImagePath: String? = null,
    // ---- project information printed on deliverables ----
    val client: String = "",
    val location: String = "",
    val floorName: String = "",
    val notes: String = "",
    val preparedBy: String = "",
    val checkedBy: String = "",
    val revision: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun layerById(id: String): Layer? = layers.firstOrNull { it.id == id }

    fun bounds(): Pair<Vec2, Vec2>? {
        val pts = segments.flatMap { listOf(it.a, it.b) } + polylines.flatMap { it.points }
        if (pts.isEmpty()) return null
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        return Vec2(minX, minY) to Vec2(maxX, maxY)
    }

    /** Real-world length of a segment in millimetres, applying calibration. */
    fun realLengthMm(s: Segment): Double = s.length() * scale.realMmPerPlanUnit

    fun copyWithTouch(
        layers: List<Layer> = this.layers,
        segments: List<Segment> = this.segments,
        polylines: List<Polyline> = this.polylines,
        rooms: List<Room> = this.rooms,
        scale: ScaleState = this.scale,
    ): PlanDocument = copy(
        layers = layers,
        segments = segments,
        polylines = polylines,
        rooms = rooms,
        scale = scale,
        updatedAt = System.currentTimeMillis(),
    )

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun defaultLayers(): List<Layer> = listOf(
            Layer(name = "Walls", kind = LayerKind.EXTERNAL_WALL, colorArgb = 0xFF111111),
            Layer(name = "Partitions", kind = LayerKind.INTERNAL_WALL, colorArgb = 0xFF555555),
            Layer(name = "Doors", kind = LayerKind.DOOR, colorArgb = 0xFF0B7A3B),
            Layer(name = "Windows", kind = LayerKind.WINDOW, colorArgb = 0xFF0B5CD7),
            Layer(name = "Other", kind = LayerKind.GENERIC, colorArgb = 0xFF9A6A00),
        )

        /** Default draw target layer: "Walls" if present. */
        fun defaultLayerId(layers: List<Layer>): String =
            layers.firstOrNull { it.kind == LayerKind.EXTERNAL_WALL }?.id ?: layers.first().id
    }
}

/** Simple horizontal/vertical snap helper used by the editor and importer. */
object GeometrySnap {
    /** Angle tolerance in degrees below which a segment is snapped to exact H/V. */
    const val HV_SNAP_DEGREES = 1.5

    fun isNearHorizontal(s: Segment, toleranceDegrees: Double = HV_SNAP_DEGREES): Boolean =
        abs(s.a.y - s.b.y) <= s.length() * Math.tan(Math.toRadians(toleranceDegrees))

    fun isNearVertical(s: Segment, toleranceDegrees: Double = HV_SNAP_DEGREES): Boolean =
        abs(s.a.x - s.b.x) <= s.length() * Math.tan(Math.toRadians(toleranceDegrees))

    fun snapToHV(s: Segment): Segment = when {
        isNearHorizontal(s) -> s.copy(a = s.a.copy(y = (s.a.y + s.b.y) / 2.0), b = s.b.copy(y = (s.a.y + s.b.y) / 2.0))
        isNearVertical(s) -> s.copy(a = s.a.copy(x = (s.a.x + s.b.x) / 2.0), b = s.b.copy(x = (s.a.x + s.b.x) / 2.0))
        else -> s
    }
}
