package com.planboq.app.boq

import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Segment
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Derives a professional BOQ: bill sections with preliminaries, brickwork split
 * by wall thickness, per-standard opening deductions, structural estimates from
 * collected data. All quantities are metric base values converted to the
 * selected standard's units.
 */
object QuantityEngine {

    private const val OPENING_CLUSTER_MM = 700.0

    private fun f(v: Double): String = "%.3f".format(Locale.US, v)

    fun compute(doc: PlanDocument, settings: BoqSettings): QuantityResult {
        val warnings = mutableListOf<String>()
        val calibrated = doc.scale.calibrated
        val ratio = if (doc.scale.realMmPerPlanUnit > 0.0) doc.scale.realMmPerPlanUnit else 1.0
        val profile = Standards.of(settings.standard)
        val units = profile.units
        val threshold = profile.openingDeductionThresholdM2

        if (!calibrated) {
            warnings += "Scale is not calibrated - quantities assume plan units are millimetres. " +
                "Calibrate the plan (two points + a known dimension) for real quantities."
        }

        val rooms = doc.rooms.filter { !it.excluded }
        if (rooms.isEmpty()) {
            warnings += "No rooms to measure - flooring, ceiling and skirting are zero. " +
                "Detect rooms or include at least one room."
        }
        val excludedCount = doc.rooms.count { it.excluded }
        val measuredSource = if (calibrated) QuantitySource.MEASURED else QuantitySource.ASSUMED

        // ---------------- walls ----------------
        val centerlines = WallCenterlines.extract(doc.segments, doc.layers, doc.scale, settings)
        val wallHeight = settings.wallHeightM
        val storeys = settings.storeys.coerceAtLeast(1)
        val totalLengthPlan = centerlines.sumOf { it.lengthPlanUnits() }

        // ---------------- openings ----------------
        val types = settings.openingTypes
        val typedCounts = LinkedHashMap<String, Int>()
        val untypedDoors = mutableListOf<Segment>()
        val untypedWindows = mutableListOf<Segment>()

        doc.segments.forEach { seg ->
            val kind = doc.layers.firstOrNull { it.id == seg.layerId }?.kind
            if (kind != LayerKind.DOOR && kind != LayerKind.WINDOW) return@forEach
            val type = seg.openingTypeId?.let { id -> types.firstOrNull { it.id == id } }
                ?.takeIf { it.kind == kind }
            when {
                type != null -> typedCounts[type.id] = (typedCounts[type.id] ?: 0) + 1
                kind == LayerKind.DOOR -> untypedDoors += seg
                else -> untypedWindows += seg
            }
        }

        val untypedDoorCount = countClusters(untypedDoors, OPENING_CLUSTER_MM / ratio)
        val untypedWindowCount = countClusters(untypedWindows, OPENING_CLUSTER_MM / ratio)
        val defaultDoorType = settings.firstTypeOf(LayerKind.DOOR)
        val defaultWindowType = settings.firstTypeOf(LayerKind.WINDOW)

        var openingAreaPerStorey = 0.0
        var doorWidthTotal = 0.0
        var doorCount = 0
        var windowCount = 0

        typedCounts.forEach { (typeId, count) ->
            val type = types.firstOrNull { it.id == typeId } ?: return@forEach
            if (type.areaM2() > threshold) openingAreaPerStorey += count * type.areaM2()
            if (type.kind == LayerKind.DOOR) {
                doorCount += count
                doorWidthTotal += count * type.widthM
            } else {
                windowCount += count
            }
        }
        if (untypedDoorCount > 0 && defaultDoorType != null) {
            if (defaultDoorType.areaM2() > threshold) openingAreaPerStorey += untypedDoorCount * defaultDoorType.areaM2()
            doorWidthTotal += untypedDoorCount * defaultDoorType.widthM
            doorCount += untypedDoorCount
        }
        if (untypedWindowCount > 0 && defaultWindowType != null) {
            if (defaultWindowType.areaM2() > threshold) openingAreaPerStorey += untypedWindowCount * defaultWindowType.areaM2()
            windowCount += untypedWindowCount
        }
        if (doorCount == 0 && windowCount == 0) {
            warnings += "No doors/windows tagged - opening deductions are zero. " +
                "Use the Door/Window tools in the editor to tag openings."
        }

        val wallAreaPerStorey = totalLengthPlan * ratio / 1000.0 * wallHeight
        val netWallAreaPerStorey = max(0.0, wallAreaPerStorey - openingAreaPerStorey)
        val netWallAreaAll = netWallAreaPerStorey * storeys
        val wallAreaAll = wallAreaPerStorey * storeys

        // ---------------- brickwork by wall thickness ----------------
        val grouped = centerlines
            .groupBy { (it.thicknessMm / 5.0).roundToInt() * 5.0 }
            .toSortedMap()
        val wastage = 1.0 + settings.wastagePercent / 100.0

        val wallItems = if (grouped.isEmpty()) {
            listOf(
                BoqItem(
                    code = "W01", category = "Walls",
                    unit = if (profile.brickworkByArea) units.area else units.volume,
                    description = profile.desc("W01"),
                    quantity = 0.0, source = QuantitySource.ASSUMED,
                    note = "no wall layers found",
                ),
            )
        } else {
            grouped.map { (thickness, lines) ->
                val lenPlan = lines.sumOf { it.lengthPlanUnits() }
                val lenM = lenPlan * ratio / 1000.0
                val share = if (totalLengthPlan > 1e-9) openingAreaPerStorey * (lenPlan / totalLengthPlan) else 0.0
                val thicknessM = thickness / 1000.0
                val netAreaPerStorey = max(0.0, lenM * wallHeight - share)
                val volumePerStorey = max(0.0, lenM * wallHeight * thicknessM - share * thicknessM)
                val quantity = if (profile.brickworkByArea) {
                    units.areaFromM2(netAreaPerStorey * storeys * wastage)
                } else {
                    units.volumeFromM3(volumePerStorey * storeys * wastage)
                }
                BoqItem(
                    code = "W01-${thickness.toInt()}",
                    category = "Walls",
                    unit = if (profile.brickworkByArea) units.area else units.volume,
                    description = profile.desc("W01") + " - ${thickness.toInt()} mm",
                    quantity = quantity,
                    source = QuantitySource.ASSUMED,
                    note = "${thickness.toInt()} mm walls; ${settings.wastagePercent}% wastage",
                    detail = if (profile.brickworkByArea) {
                        "Length ${f(lenM)} m x height ${f(wallHeight)} m - opening share ${f(share)} m2, " +
                            "x $storeys storey(s), +${settings.wastagePercent}%"
                    } else {
                        "Length ${f(lenM)} m x height ${f(wallHeight)} m x ${f(thicknessM)} m " +
                            "- opening share ${f(share)} m2 x ${f(thicknessM)} m, " +
                            "x $storeys storey(s), +${settings.wastagePercent}%"
                    },
                )
            }
        }

        // ---------------- rooms ----------------
        val flooringAreaPerStorey = rooms.sumOf { room -> room.areaM2(ratio) ?: 0.0 }
        val perimeterM = rooms.sumOf { room -> room.perimeterPlanUnits() * ratio / 1000.0 }
        val doorDeduction = minOf(doorWidthTotal, perimeterM)
        val flooringAreaM2 = flooringAreaPerStorey * storeys * wastage
        val ceilingAreaM2 = flooringAreaPerStorey * storeys
        val skirtingM = max(0.0, perimeterM - doorDeduction) * storeys * wastage

        val roomNote = buildString {
            append("${rooms.size} room(s)")
            if (excludedCount > 0) append(", $excludedCount excluded")
        }

        // ---------------- structural estimates ----------------
        val st = settings.structural
        val structuralEnabled = st.enabled && st.columnCount > 0
        var footingM3 = 0.0
        var columnM3 = 0.0
        var beamM3 = 0.0
        var slabM3 = 0.0
        var steelKg = 0.0
        val beamLengthM = if (st.beamLengthM > 0.0) st.beamLengthM else 0.6 * (totalLengthPlan * ratio / 1000.0)

        if (structuralEnabled) {
            footingM3 = st.columnCount * st.footingLengthM * st.footingWidthM * st.footingDepthM
            columnM3 = st.columnCount * (st.columnWidthMm / 1000.0) * (st.columnDepthMm / 1000.0) *
                wallHeight * storeys
            beamM3 = beamLengthM * (st.beamWidthMm / 1000.0) * (st.beamDepthMm / 1000.0) * storeys
            slabM3 = flooringAreaPerStorey * settings.slabThicknessM * storeys
            steelKg = (footingM3 + columnM3 + beamM3 + slabM3) * st.steelKgPerM3
        }

        fun structuralQty(code: String, m3: Double, manualNote: String, detail: String): BoqItem =
            if (structuralEnabled) {
                BoqItem(
                    code = code, category = "Structure (assumed)", unit = units.volume,
                    description = profile.desc(code),
                    quantity = units.volumeFromM3(m3), source = QuantitySource.ASSUMED,
                    note = "${st.foundationType.label}; estimated from collected structural data",
                    detail = detail,
                )
            } else {
                BoqItem(
                    code = code, category = "Structure (manual)", unit = units.volume,
                    description = profile.desc(code), source = QuantitySource.MANUAL,
                    note = manualNote,
                )
            }

        val structuralItems = listOf(
            structuralQty(
                "S01", footingM3,
                "Not derivable from a floor plan - enter from structural drawings",
                "${st.columnCount} footings x ${f(st.footingLengthM)} x ${f(st.footingWidthM)} x " +
                    "${f(st.footingDepthM)} m = ${f(footingM3)} m3 (${st.foundationType.label})",
            ),
            structuralQty(
                "S02", columnM3,
                "Not derivable from a floor plan - enter from structural drawings",
                "${st.columnCount} columns x ${f(st.columnWidthMm / 1000.0)} x " +
                    "${f(st.columnDepthMm / 1000.0)} m x ${f(wallHeight)} m x $storeys storey(s)",
            ),
            structuralQty(
                "S03", beamM3 + slabM3,
                "Not derivable from a floor plan - enter from structural drawings",
                "Beams ${f(beamLengthM)} m x ${f(st.beamWidthMm / 1000.0)} x ${f(st.beamDepthMm / 1000.0)} m " +
                    "x $storeys + slab ${f(flooringAreaPerStorey)} m2 x ${f(settings.slabThicknessM)} m x $storeys",
            ),
            if (structuralEnabled) {
                BoqItem(
                    code = "S04", category = "Structure (assumed)", unit = units.mass,
                    description = profile.desc("S04"),
                    quantity = units.massFromKg(steelKg), source = QuantitySource.ASSUMED,
                    note = "steel allowance ${f(st.steelKgPerM3)} kg/m3 of concrete",
                    detail = "(${f(footingM3)} + ${f(columnM3)} + ${f(beamM3 + slabM3)}) m3 x " +
                        "${f(st.steelKgPerM3)} kg/m3 = ${f(steelKg)} kg",
                )
            } else {
                BoqItem(
                    code = "S04", category = "Structure (manual)", unit = units.mass,
                    description = profile.desc("S04"), source = QuantitySource.MANUAL,
                    note = "Enter from bar bending schedule",
                )
            },
        )

        // ---------------- items (bill order) ----------------
        val items = mutableListOf<BoqItem>()
        items += BoqItem(
            code = "P01", category = "Preliminaries", unit = "item",
            description = "Preliminaries, site establishment and general obligations (lump sum)",
            quantity = 1.0, rate = 0.0, source = QuantitySource.MANUAL,
            note = "Enter a lump sum or a percentage of the measured works",
        )
        items += wallItems
        items += BoqItem(
            code = "W02", category = "Finishes", unit = units.area,
            description = profile.desc("W02"),
            quantity = units.areaFromM2(netWallAreaAll * 2.0), source = measuredSource,
            note = "deducts ${f(openingAreaPerStorey)} m2 openings per storey",
            detail = "Gross wall area ${f(wallAreaAll)} m2 - openings " +
                "${f(openingAreaPerStorey * storeys)} m2, x2 faces = ${f(netWallAreaAll * 2.0)} m2",
        )
        items += BoqItem(
            code = "W03", category = "Finishes", unit = units.area,
            description = profile.desc("W03"),
            quantity = units.areaFromM2(netWallAreaAll * 2.0), source = measuredSource,
            detail = "Same surface as plaster: ${f(netWallAreaAll * 2.0)} m2",
        )
        items += BoqItem(
            code = "F01", category = "Flooring", unit = units.area,
            description = profile.desc("F01"),
            quantity = units.areaFromM2(flooringAreaM2), source = measuredSource,
            note = "$roomNote; ${settings.wastagePercent}% wastage",
            detail = "Room areas ${f(flooringAreaPerStorey)} m2 x $storeys storey(s), " +
                "+${settings.wastagePercent}% = ${f(flooringAreaM2)} m2",
        )
        items += BoqItem(
            code = "F02", category = "Flooring", unit = units.length,
            description = profile.desc("F02"),
            quantity = units.lengthFromM(skirtingM), source = measuredSource,
            note = "room perimeters less door widths",
            detail = "Perimeters ${f(perimeterM)} m - door widths ${f(doorDeduction)} m, " +
                "x $storeys storey(s), +${settings.wastagePercent}%",
        )
        items += BoqItem(
            code = "F03", category = "Finishes", unit = units.area,
            description = profile.desc("F03"),
            quantity = units.areaFromM2(ceilingAreaM2), source = measuredSource,
            detail = "Room areas ${f(flooringAreaPerStorey)} m2 x $storeys storey(s)",
        )
        items += structuralItems

        if (wallAreaAll <= 0.0) {
            warnings += "No wall layers found - check that walls are on the Walls/Partitions layers."
        }

        return QuantityResult(items = items, warnings = warnings.distinct())
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
