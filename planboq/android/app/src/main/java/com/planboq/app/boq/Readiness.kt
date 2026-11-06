package com.planboq.app.boq

import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument

enum class GapSeverity { BLOCKING, IMPORTANT, OPTIONAL }

enum class GapKey(val id: String) {
    SCALE("scale"),
    ROOMS("rooms"),
    WALLS("walls"),
    OPENINGS("openings"),
    STOREY("storey_height"),
    SLAB("slab"),
    STOREYS("storeys"),
    THICKNESS("thickness"),
    OPENING_TYPES("opening_types"),
    CONTINGENCY("contingency"),
    WASTAGE("wastage"),
    RATES("rates"),
    STRUCTURAL("structural"),
    PROJECT_INFO("project_info"),
}

data class DataGap(
    val key: GapKey,
    val severity: GapSeverity,
    val title: String,
    val question: String,
    val detail: String = "",
)

data class Readiness(
    val score: Int,
    val gaps: List<DataGap>,
) {
    val blocking: Int get() = gaps.count { it.severity == GapSeverity.BLOCKING }
    val important: Int get() = gaps.count { it.severity == GapSeverity.IMPORTANT }
    val optional: Int get() = gaps.count { it.severity == GapSeverity.OPTIONAL }
    val isExportable: Boolean get() = blocking == 0
    fun has(key: GapKey): Boolean = gaps.any { it.key == key }
}

/**
 * Evaluates how close a BOQ is to being submittable and what data is missing.
 *
 * BLOCKING gaps make quantities wrong (uncalibrated scale, no rooms, no walls)
 * and cannot be "accepted". IMPORTANT gaps are assumptions that must at least
 * be reviewed. OPTIONAL gaps are nice-to-haves (project info, default types).
 *
 * Accepted gap ids count as reviewed: they stop lowering the score but are
 * still printed in the assumptions annex of the BOQ.
 */
object BoqReadiness {

    private val weights = mapOf(
        GapKey.SCALE to 25,
        GapKey.ROOMS to 15,
        GapKey.WALLS to 10,
        GapKey.OPENINGS to 6,
        GapKey.STOREY to 6,
        GapKey.SLAB to 5,
        GapKey.STOREYS to 4,
        GapKey.THICKNESS to 6,
        GapKey.OPENING_TYPES to 3,
        GapKey.CONTINGENCY to 4,
        GapKey.WASTAGE to 2,
        GapKey.RATES to 8,
        GapKey.STRUCTURAL to 4,
        GapKey.PROJECT_INFO to 2,
    )

    fun evaluate(
        plan: PlanDocument?,
        settings: BoqSettings,
        items: List<BoqItem>,
        accepted: Set<String>,
    ): Readiness {
        val gaps = mutableListOf<DataGap>();

        if (plan == null) {
            gaps += DataGap(
                GapKey.SCALE, GapSeverity.BLOCKING, "Plan not loaded",
                "Re-open the project",
            )
            return finalise(gaps, accepted)
        }

        // ---- blocking ----
        if (!plan.scale.calibrated) {
            gaps += DataGap(
                GapKey.SCALE, GapSeverity.BLOCKING, "Scale not calibrated",
                "Set the scale with two points and a known dimension",
                "Without it, all quantities are meaningless.",
            )
        }
        val activeRooms = plan.rooms.filter { !it.excluded }
        if (activeRooms.isEmpty()) {
            gaps += DataGap(
                GapKey.ROOMS, GapSeverity.BLOCKING, "No rooms to measure",
                "Detect rooms or include at least one room",
                "Flooring, ceiling and skirting depend on room areas.",
            )
        }
        val wallKinds = setOf(LayerKind.EXTERNAL_WALL, LayerKind.INTERNAL_WALL)
        val wallSegments = plan.segments.filter { seg ->
            plan.layers.firstOrNull { it.id == seg.layerId }?.kind in wallKinds
        }
        if (wallSegments.isEmpty()) {
            gaps += DataGap(
                GapKey.WALLS, GapSeverity.BLOCKING, "No walls found",
                "Tag wall lines on the Walls/Partitions layers",
            )
        }

        // ---- important assumptions ----
        val openingSegments = plan.segments.count { seg ->
            val kind = plan.layers.firstOrNull { it.id == seg.layerId }?.kind
            kind == LayerKind.DOOR || kind == LayerKind.WINDOW
        }
        if (openingSegments == 0) {
            gaps += DataGap(
                GapKey.OPENINGS, GapSeverity.IMPORTANT, "No openings tagged",
                "Tag doors and windows with the Door/Window tools",
                "Opening deductions are currently zero.",
            )
        }
        if (settings.storeyHeightM == 3.0) {
            gaps += DataGap(
                GapKey.STOREY, GapSeverity.IMPORTANT, "Storey height not confirmed",
                "Confirm the floor-to-floor height (default 3.00 m)",
            )
        }
        if (settings.slabThicknessM == 0.125) {
            gaps += DataGap(
                GapKey.SLAB, GapSeverity.IMPORTANT, "Slab thickness not confirmed",
                "Confirm the slab thickness (default 125 mm)",
            )
        }
        if (settings.storeys == 1) {
            gaps += DataGap(
                GapKey.STOREYS, GapSeverity.IMPORTANT, "Number of storeys not confirmed",
                "Confirm how many storeys this plan represents",
            )
        }
        val layerThicknessOverride = plan.layers.any { it.thicknessMm != null }
        if (settings.externalWallThicknessMm == 230.0 &&
            settings.internalWallThicknessMm == 115.0 &&
            !layerThicknessOverride
        ) {
            gaps += DataGap(
                GapKey.THICKNESS, GapSeverity.IMPORTANT, "Wall thicknesses are defaults",
                "Confirm external 230 mm / internal 115 mm, or set per-layer thickness",
            )
        }
        if (settings.contingencyPercent == 0.0) {
            gaps += DataGap(
                GapKey.CONTINGENCY, GapSeverity.IMPORTANT, "No contingency",
                "Set a contingency percentage for a submittable BOQ",
            )
        }
        val missingRateCodes = items.filter { it.rate == 0.0 }.map { it.code }.distinct()
        if (missingRateCodes.isNotEmpty()) {
            gaps += DataGap(
                GapKey.RATES, GapSeverity.IMPORTANT, "Rates missing",
                "Add rates in the rate library or per item",
                missingRateCodes.take(8).joinToString(", "),
            )
        }
        if (!settings.structural.enabled) {
            gaps += DataGap(
                GapKey.STRUCTURAL, GapSeverity.IMPORTANT, "Structural data not collected",
                "Collect footing/column/beam data to estimate structural lines",
                "Otherwise structural items stay manual.",
            )
        }

        // ---- optional ----
        if (settings.openingTypes.all {
                it.id == BoqSettings.DEFAULT_DOOR_ID || it.id == BoqSettings.DEFAULT_WINDOW_ID
            }
        ) {
            gaps += DataGap(
                GapKey.OPENING_TYPES, GapSeverity.OPTIONAL, "Only default opening types",
                "Add your door/window families for accurate deductions",
            )
        }
        if (settings.wastagePercent == 5.0) {
            gaps += DataGap(
                GapKey.WASTAGE, GapSeverity.OPTIONAL, "Wastage is the default 5%",
                "Confirm the wastage allowance",
            )
        }
        if (plan.client.isBlank() && plan.location.isBlank()) {
            gaps += DataGap(
                GapKey.PROJECT_INFO, GapSeverity.OPTIONAL, "Project information missing",
                "Add client, site and prepared-by details for the deliverable",
            )
        }

        return finalise(gaps, accepted)
    }

    private fun finalise(gaps: List<DataGap>, accepted: Set<String>): Readiness {
        val activeGaps = gaps.filter { gap ->
            gap.severity == GapSeverity.BLOCKING || gap.key.id !in accepted
        }
        val totalWeight = weights.values.sum()
        val satisfiedWeight = activeGaps.sumOf { weights[it.key] ?: 0 }
        val score = ((totalWeight - satisfiedWeight).toDouble() / totalWeight * 100.0).toInt().coerceIn(0, 100)
        return Readiness(score, activeGaps)
    }

    /** Ids to store when the user reviews/accepts all non-blocking gaps. */
    fun acceptableIds(gaps: List<DataGap>): Set<String> =
        gaps.filter { it.severity != GapSeverity.BLOCKING }.map { it.key.id }.toSet()
}
