package com.planboq.app.boq

import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Segment
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** One auditable measurement row. */
data class MeasurementLine(
    val description: String,
    val quantity: Double,
    val unit: String,
    val note: String = "",
)

/** Element-level derivation of one BOQ item. */
data class MeasurementSheet(
    val itemCode: String,
    val itemDescription: String,
    val unit: String,
    val lines: List<MeasurementLine>,
    val adjustments: List<MeasurementLine>,
    val total: Double,
) {
    val lineTotal: Double get() = lines.sumOf { it.quantity } + adjustments.sumOf { it.quantity }
}

/**
 * Rebuilds element-by-element measurement sheets that sum to the BOQ item
 * quantities (walls per centreline, rooms per room, openings as adjustments).
 */
object MeasurementEngine {

    private const val OPENING_CLUSTER_MM = 700.0

    private fun f(v: Double): String = "%.3f".format(Locale.US, v)

    fun compute(doc: PlanDocument, settings: BoqSettings, items: List<BoqItem>): List<MeasurementSheet> {
        val ratio = if (doc.scale.realMmPerPlanUnit > 0.0) doc.scale.realMmPerPlanUnit else 1.0
        val profile = Standards.of(settings.standard)
        val units = profile.units
        val threshold = profile.openingDeductionThresholdM2
        val wallHeight = settings.wallHeightM
        val storeys = settings.storeys.coerceAtLeast(1)
        val wastagePct = settings.wastagePercent

        val centerlines = WallCenterlines.extract(doc.segments, doc.layers, doc.scale, settings)
        val totalLenPlan = centerlines.sumOf { it.lengthPlanUnits() }
        val groups = centerlines
            .groupBy { (it.thicknessMm / 5.0).roundToInt() * 5.0 }
            .toSortedMap()

        // openings (mirrors QuantityEngine)
        val types = settings.openingTypes
        val typedCounts = LinkedHashMap<String, Int>()
        val untypedDoors = mutableListOf<Segment>()
        val untypedWindows = mutableListOf<Segment>()
        doc.segments.forEach { seg ->
            val kind = doc.layers.firstOrNull { it.id == seg.layerId }?.kind
            if (kind != LayerKind.DOOR && kind != LayerKind.WINDOW) return@forEach
            val type = seg.openingTypeId?.let { id -> types.firstOrNull { it.id == id } }?.takeIf { it.kind == kind }
            when {
                type != null -> typedCounts[type.id] = (typedCounts[type.id] ?: 0) + 1
                kind == LayerKind.DOOR -> untypedDoors += seg
                else -> untypedWindows += seg
            }
        }
        val defaultDoor = settings.firstTypeOf(LayerKind.DOOR)
        val defaultWindow = settings.firstTypeOf(LayerKind.WINDOW)
        var openingArea = 0.0
        var doorWidth = 0.0
        typedCounts.forEach { (id, count) ->
            val type = types.firstOrNull { it.id == id } ?: return@forEach
            if (type.areaM2() > threshold) openingArea += count * type.areaM2()
            if (type.kind == LayerKind.DOOR) doorWidth += count * type.widthM
        }
        val untypedDoorsCount = countClusters(untypedDoors, OPENING_CLUSTER_MM / ratio)
        val untypedWindowsCount = countClusters(untypedWindows, OPENING_CLUSTER_MM / ratio)
        if (untypedDoorsCount > 0 && defaultDoor != null) {
            if (defaultDoor.areaM2() > threshold) openingArea += untypedDoorsCount * defaultDoor.areaM2()
            doorWidth += untypedDoorsCount * defaultDoor.widthM
        }
        if (untypedWindowsCount > 0 && defaultWindow != null && defaultWindow.areaM2() > threshold) {
            openingArea += untypedWindowsCount * defaultWindow.areaM2()
        }

        val rooms = doc.rooms.filter { !it.excluded }
        val roomArea = rooms.sumOf { it.areaM2(ratio) ?: 0.0 }
        val perimeter = rooms.sumOf { it.perimeterPlanUnits() * ratio / 1000.0 }
        val doorDeduction = minOf(doorWidth, perimeter)

        return items.map { item ->
            when {
                item.code == "P01" -> MeasurementSheet(
                    item.code, item.description, item.unit,
                    listOf(MeasurementLine("Lump sum item", 1.0, "item")),
                    emptyList(),
                    item.quantity,
                )

                item.code.startsWith("W01-") -> {
                    val thickness = item.code.substringAfter("-").toDoubleOrNull() ?: 0.0
                    val lines = groups[thickness].orEmpty()
                    val groupLenPlan = lines.sumOf { it.lengthPlanUnits() }
                    val share = if (totalLenPlan > 1e-9) openingArea * (groupLenPlan / totalLenPlan) else 0.0
                    val thicknessM = thickness / 1000.0
                    val isArea = profile.brickworkByArea
                    val measured = lines.mapIndexed { index, line ->
                        val lenM = line.lengthPlanUnits() * ratio / 1000.0
                        val value = if (isArea) units.areaFromM2(lenM * wallHeight) else units.volumeFromM3(lenM * wallHeight * thicknessM)
                        MeasurementLine(
                            "Wall ${index + 1} - ${if (line.kind == LayerKind.EXTERNAL_WALL) "external" else "internal"}",
                            value, item.unit,
                            "${f(lenM)} m x ${f(wallHeight)} m" + if (isArea) "" else " x ${f(thicknessM)} m",
                        )
                    }
                    val netMeasure = if (isArea) units.areaFromM2(share) else units.volumeFromM3(share * thicknessM)
                    val adjustments = mutableListOf<MeasurementLine>()
                    if (share > 0) {
                        adjustments += MeasurementLine("Openings share (${f(share)} m2)", -netMeasure, item.unit)
                    }
                    val netPerStorey = (measured.sumOf { it.quantity } + adjustments.sumOf { it.quantity })
                    val afterStoreys = netPerStorey * storeys
                    val waste = afterStoreys * wastagePct / 100.0
                    if (waste != 0.0) adjustments += MeasurementLine("Wastage $wastagePct%", waste, item.unit)
                    MeasurementSheet(item.code, item.description, item.unit, measured, adjustments, item.quantity)
                }

                item.code == "W02" || item.code == "W03" -> {
                    val lines = centerlines.mapIndexed { index, line ->
                        val lenM = line.lengthPlanUnits() * ratio / 1000.0
                        MeasurementLine(
                            "Wall ${index + 1} - two faces",
                            units.areaFromM2(lenM * wallHeight * 2.0),
                            item.unit,
                            "${f(lenM)} m x ${f(wallHeight)} m x 2",
                        )
                    }
                    val adjustments = mutableListOf<MeasurementLine>()
                    if (openingArea > 0) {
                        adjustments += MeasurementLine(
                            "Deduct openings (${f(openingArea)} m2 per storey x $storeys x 2 faces)",
                            -units.areaFromM2(openingArea * storeys * 2.0),
                            item.unit,
                        )
                    }
                    MeasurementSheet(item.code, item.description, item.unit, lines, adjustments, item.quantity)
                }

                item.code == "F01" -> {
                    val lines = rooms.map { room ->
                        MeasurementLine(
                            "Room: ${room.name}",
                            units.areaFromM2((room.areaM2(ratio) ?: 0.0) * storeys),
                            item.unit,
                        )
                    }
                    val waste = roomArea * storeys * wastagePct / 100.0
                    val adjustments = if (waste != 0.0) {
                        listOf(MeasurementLine("Wastage $wastagePct%", units.areaFromM2(waste), item.unit))
                    } else emptyList()
                    MeasurementSheet(item.code, item.description, item.unit, lines, adjustments, item.quantity)
                }

                item.code == "F02" -> {
                    val lines = rooms.map { room ->
                        MeasurementLine(
                            "Room: ${room.name} - perimeter",
                            units.lengthFromM(room.perimeterPlanUnits() * ratio / 1000.0 * storeys),
                            item.unit,
                        )
                    }
                    val adjustments = mutableListOf<MeasurementLine>()
                    if (doorDeduction > 0) {
                        adjustments += MeasurementLine(
                            "Deduct door widths",
                            -units.lengthFromM(doorDeduction * storeys),
                            item.unit,
                        )
                    }
                    val net = (perimeter - doorDeduction) * storeys
                    if (wastagePct != 0.0) {
                        adjustments += MeasurementLine(
                            "Wastage $wastagePct%",
                            units.lengthFromM(net * wastagePct / 100.0),
                            item.unit,
                        )
                    }
                    MeasurementSheet(item.code, item.description, item.unit, lines, adjustments, item.quantity)
                }

                item.code == "F03" -> {
                    val lines = rooms.map { room ->
                        MeasurementLine(
                            "Room: ${room.name}",
                            units.areaFromM2((room.areaM2(ratio) ?: 0.0) * storeys),
                            item.unit,
                        )
                    }
                    MeasurementSheet(item.code, item.description, item.unit, lines, emptyList(), item.quantity)
                }

                item.code == "S01" || item.code == "S02" || item.code == "S03" || item.code == "S04" -> {
                    val lines = listOf(
                        MeasurementLine(
                            item.description,
                            item.quantity,
                            item.unit,
                            item.detail.ifBlank { item.note },
                        ),
                    )
                    MeasurementSheet(item.code, item.description, item.unit, lines, emptyList(), item.quantity)
                }

                else -> MeasurementSheet(
                    item.code, item.description, item.unit,
                    listOf(MeasurementLine(item.description, item.quantity, item.unit)),
                    emptyList(), item.quantity,
                )
            }
        }
    }

    private fun countClusters(segments: List<Segment>, clusterDistance: Double): Int {
        if (segments.isEmpty()) return 0
        val centers = segments.map {
            com.planboq.app.data.geometry.Vec2((it.a.x + it.b.x) / 2.0, (it.a.y + it.b.y) / 2.0)
        }
        val used = BooleanArray(centers.size)
        var count = 0
        for (i in centers.indices) {
            if (used[i]) continue
            used[i] = true
            count++
            for (j in i + 1 until centers.size) {
                if (used[j]) continue
                if (centers[i].distanceTo(centers[j]) <= clusterDistance) used[j] = true
            }
        }
        return count
    }
}
