package com.planboq.app.boq

import com.planboq.app.data.geometry.LayerKind
import kotlinx.serialization.Serializable
import java.util.UUID

// (BoqMerge below keeps library rates when a stored project rate is still zero.)

enum class QuantitySource { MEASURED, ASSUMED, MANUAL }

/** A door/window family with its own size; openings reference it by id. */
@Serializable
data class OpeningType(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: LayerKind,
    val widthM: Double,
    val heightM: Double,
) {
    fun areaM2(): Double = widthM * heightM
}

@Serializable
data class BoqItem(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val description: String,
    val unit: String,
    val quantity: Double = 0.0,
    val rate: Double = 0.0,
    val category: String = "General",
    val source: QuantitySource = QuantitySource.MEASURED,
    val note: String = "",
    /** "How calculated" text shown in the expandable item card. */
    val detail: String = "",
) {
    fun amount(): Double = quantity * rate
}

enum class MeasurementStandard(val label: String) {
    GENERIC_METRIC("Generic metric"),
    INDIA_CPWD("India - CPWD style"),
    UK_BS("UK - BS style"),
    US_IMPERIAL("US - imperial"),
}

enum class FoundationType(val label: String) {
    ISOLATED("Isolated footings"),
    RAFT("Raft"),
    PILE("Piles"),
}

@Serializable
data class StructuralData(
    val enabled: Boolean = false,
    val foundationType: FoundationType = FoundationType.ISOLATED,
    val columnCount: Int = 0,
    val columnWidthMm: Double = 300.0,
    val columnDepthMm: Double = 300.0,
    val footingLengthM: Double = 1.2,
    val footingWidthM: Double = 1.2,
    val footingDepthM: Double = 0.3,
    val beamWidthMm: Double = 230.0,
    val beamDepthMm: Double = 450.0,
    /** 0 = derive as 0.6 x total wall centreline length */
    val beamLengthM: Double = 0.0,
    val steelKgPerM3: Double = 90.0,
)

@Serializable
data class BoqSettings(
    val standard: MeasurementStandard = MeasurementStandard.GENERIC_METRIC,
    val storeyHeightM: Double = 3.0,
    val slabThicknessM: Double = 0.125,
    val storeys: Int = 1,
    val externalWallThicknessMm: Double = 230.0,
    val internalWallThicknessMm: Double = 115.0,
    val openingTypes: List<OpeningType> = defaultOpeningTypes(),
    val wastagePercent: Double = 5.0,
    val contingencyPercent: Double = 0.0,
    val currency: String = "$",
    val structural: StructuralData = StructuralData(),
) {
    /** Clear wall height above the floor finish, under the slab. */
    val wallHeightM: Double get() = (storeyHeightM - slabThicknessM).coerceAtLeast(0.1)

    fun firstTypeOf(kind: LayerKind): OpeningType? = openingTypes.firstOrNull { it.kind == kind }

    companion object {
        const val DEFAULT_DOOR_ID = "door-default"
        const val DEFAULT_WINDOW_ID = "window-default"

        fun defaultOpeningTypes(): List<OpeningType> = listOf(
            OpeningType(id = DEFAULT_DOOR_ID, name = "Door D1", kind = LayerKind.DOOR, widthM = 0.9, heightM = 2.1),
            OpeningType(id = DEFAULT_WINDOW_ID, name = "Window W1", kind = LayerKind.WINDOW, widthM = 1.2, heightM = 1.2),
        )
    }
}

@Serializable
data class BoqDocument(
    val projectId: String,
    val settings: BoqSettings = BoqSettings(),
    val items: List<BoqItem> = emptyList(),
    /** Gaps the user explicitly reviewed and accepted (still printed as assumptions). */
    val acceptedGaps: Set<String> = emptySet(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Sum of item amounts before contingency. */
    fun total(): Double = items.sumOf { it.amount() }

    fun contingencyAmount(): Double = total() * settings.contingencyPercent / 100.0

    fun grandTotal(): Double = total() + contingencyAmount()

    fun categoryTotals(): LinkedHashMap<String, Double> {
        val map = LinkedHashMap<String, Double>()
        items.forEach { item ->
            map[item.category] = (map[item.category] ?: 0.0) + item.amount()
        }
        return map
    }
}

data class QuantityResult(
    val items: List<BoqItem>,
    val warnings: List<String> = emptyList(),
)

/** Recalculation: keep user rates, and keep manual edits/custom lines. */
object BoqMerge {
    fun merge(existing: List<BoqItem>, computed: List<BoqItem>): List<BoqItem> {
        val byCode = existing.associateBy { it.code }
        val merged = computed.map { c ->
            val old = byCode[c.code]
            when {
                old == null -> c
                old.source == QuantitySource.MANUAL -> {
                    // A manual line is only a user edit when it has content.
                    // Engine placeholders (0 quantity / 0 rate) must not block
                    // newly computed structural estimates.
                    val looksEdited = old.quantity != 0.0 || old.rate != 0.0
                    if (looksEdited) {
                        c.copy(
                            quantity = old.quantity,
                            rate = old.rate,
                            source = QuantitySource.MANUAL,
                            note = old.note,
                        )
                    } else {
                        c.copy(rate = if (old.rate != 0.0) old.rate else c.rate)
                    }
                }
                // a stored 0 means "not set yet" - let the library rate through
                else -> c.copy(rate = if (old.rate != 0.0) old.rate else c.rate)
            }
        }
        // Legacy aggregate codes are superseded by split codes (e.g. W01 by
        // W01-230); drop them so stale rows do not double the bill.
        val custom = existing.filter { old ->
            computed.none { it.code == old.code } &&
                computed.none { it.code.startsWith(old.code + "-") }
        }
        return merged + custom
    }
}
