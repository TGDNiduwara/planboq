package com.planboq.app.boq

import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import kotlin.math.max
import kotlin.math.min

enum class ValidationSeverity { ERROR, WARN, INFO }

data class ValidationIssue(val severity: ValidationSeverity, val message: String)

/**
 * Cross-checks that catch the mistakes a QS would catch by eye: wrong scale
 * side-effects, missing openings, zero quantities, duplicate codes.
 */
object BoqValidator {

    fun validate(plan: PlanDocument?, settings: BoqSettings, items: List<BoqItem>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (plan == null) {
            return listOf(ValidationIssue(ValidationSeverity.ERROR, "Plan document not found"))
        }
        val ratio = if (plan.scale.realMmPerPlanUnit > 0.0) plan.scale.realMmPerPlanUnit else 1.0

        // duplicate codes
        items.groupBy { it.code }.filter { it.value.size > 1 }.forEach { (code, _) ->
            issues += ValidationIssue(ValidationSeverity.ERROR, "Duplicate item code: $code")
        }
        // quantities
        items.forEach { item ->
            if (item.quantity < 0) {
                issues += ValidationIssue(ValidationSeverity.ERROR, "${item.code}: negative quantity")
            } else if (item.quantity == 0.0 && item.unit !in setOf("item", "nos") &&
                item.source != QuantitySource.MANUAL
            ) {
                issues += ValidationIssue(ValidationSeverity.WARN, "${item.code}: quantity is zero")
            }
        }
        val unpriced = items.filter { it.rate == 0.0 }
        if (unpriced.isNotEmpty()) {
            issues += ValidationIssue(
                ValidationSeverity.INFO,
                "${unpriced.size} item(s) still unpriced: ${unpriced.take(8).joinToString { it.code }}",
            )
        }

        if (!plan.scale.calibrated) {
            issues += ValidationIssue(ValidationSeverity.ERROR, "Scale is not calibrated")
        } else {
            // wall length vs room perimeter
            val centerlines = WallCenterlines.extract(plan.segments, plan.layers, plan.scale, settings)
            val wallLength = centerlines.sumOf { it.lengthPlanUnits() } * ratio / 1000.0
            val perimeter = plan.rooms.filter { !it.excluded }
                .sumOf { it.perimeterPlanUnits() * ratio / 1000.0 }
            if (perimeter > 1.0 && wallLength > 0) {
                val ratioValue = wallLength / perimeter
                if (ratioValue < 0.5 || ratioValue > 3.0) {
                    issues += ValidationIssue(
                        ValidationSeverity.WARN,
                        "Wall length %.1f m vs room perimeter %.1f m (ratio %.2f) - check the trace".format(wallLength, perimeter, ratioValue),
                    )
                }
            }
            // room area vs wall envelope
            val wallSegments = plan.segments.filter { seg ->
                plan.layers.firstOrNull { it.id == seg.layerId }?.kind in
                    setOf(LayerKind.EXTERNAL_WALL, LayerKind.INTERNAL_WALL)
            }
            val roomArea = plan.rooms.filter { !it.excluded }.sumOf { it.areaM2(ratio) ?: 0.0 }
            if (wallSegments.isNotEmpty()) {
                val minX = wallSegments.minOf { min(it.a.x, it.b.x) }
                val maxX = wallSegments.maxOf { max(it.a.x, it.b.x) }
                val minY = wallSegments.minOf { min(it.a.y, it.b.y) }
                val maxY = wallSegments.maxOf { max(it.a.y, it.b.y) }
                val envelope = (maxX - minX) * ratio / 1000.0 * ((maxY - minY) * ratio / 1000.0)
                if (envelope > 1.0) {
                    val coverage = roomArea / envelope
                    if (coverage < 0.15 || coverage > 1.0) {
                        issues += ValidationIssue(
                            ValidationSeverity.WARN,
                            "Measured room area %.1f m2 vs envelope %.1f m2 (%.0f%%) - check rooms/exclusions"
                                .format(roomArea, envelope, coverage * 100),
                        )
                    }
                }
            }
            // short openings
            val shortOpenings = plan.segments.count { seg ->
                val kind = plan.layers.firstOrNull { it.id == seg.layerId }?.kind
                (kind == LayerKind.DOOR || kind == LayerKind.WINDOW) &&
                    seg.length() * ratio / 1000.0 < 0.3
            }
            if (shortOpenings > 0) {
                issues += ValidationIssue(
                    ValidationSeverity.WARN,
                    "$shortOpenings opening(s) shorter than 0.3 m - check door/window tagging",
                )
            }
        }

        if (settings.structural.enabled && settings.structural.columnCount <= 0) {
            issues += ValidationIssue(
                ValidationSeverity.WARN,
                "Structural estimate is on but the column count is zero",
            )
        }
        val excluded = plan.rooms.count { it.excluded }
        if (excluded > 0) {
            issues += ValidationIssue(ValidationSeverity.INFO, "$excluded room(s) excluded from quantities")
        }
        return issues
    }
}
