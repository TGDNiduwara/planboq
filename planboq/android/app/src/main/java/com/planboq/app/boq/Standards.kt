package com.planboq.app.boq

/** Output units for a measurement standard. */
data class UnitSystem(
    val area: String,
    val volume: String,
    val length: String,
    val mass: String,
    val metric: Boolean,
) {
    fun areaFromM2(v: Double): Double = if (metric) v else v * 10.7639
    fun volumeFromM3(v: Double): Double = if (metric) v else v * 1.30795
    fun lengthFromM(v: Double): Double = if (metric) v else v * 3.28084
    fun massFromKg(v: Double): Double = if (metric) v else v / 907.18474
}

/**
 * Data-driven measurement standard: output units, default currency, whether
 * brickwork is billed by area or volume, and item wording.
 */
data class StandardProfile(
    val standard: MeasurementStandard,
    val currency: String,
    val units: UnitSystem,
    val brickworkByArea: Boolean,
    val descriptions: Map<String, String>,
    /** Openings at or below this area are not deducted (per standard). */
    val openingDeductionThresholdM2: Double,
    /** Measurement notes printed as the preamble of the BOQ. */
    val preamble: List<String>,
) {
    fun desc(code: String): String = descriptions[code] ?: code
}

object Standards {

    private val METRIC = UnitSystem(area = "m2", volume = "m3", length = "m", mass = "kg", metric = true)
    private val IMPERIAL = UnitSystem(area = "sf", volume = "cy", length = "ft", mass = "ton", metric = false)

    private val GENERIC_DESCRIPTIONS = mapOf(
        "W01" to "Brickwork / blockwork in walls (external and internal)",
        "W02" to "Cement plaster to wall surfaces (both faces)",
        "W03" to "Painting to wall surfaces (two coats)",
        "F01" to "Floor finish (tiles / screed)",
        "F02" to "Skirting to walls",
        "F03" to "Ceiling finish",
        "S01" to "Concrete in footings / foundation",
        "S02" to "Concrete in columns",
        "S03" to "Concrete in beams / slab",
        "S04" to "Reinforcement steel (all elements)",
    )

    private val INDIA_DESCRIPTIONS = mapOf(
        "W01" to "Brickwork with burnt clay bricks in walls (external/internal), cement mortar 1:6",
        "W02" to "12 mm cement plaster to walls, both faces, mortar 1:4",
        "W03" to "Painting to wall surfaces, two coats over primer",
        "F01" to "Floor finish with ceramic tiles / cement screed",
        "F02" to "Skirting 100 mm high",
        "F03" to "Ceiling plaster / finish",
        "S01" to "Plain cement concrete in footings",
        "S02" to "Reinforced cement concrete in columns",
        "S03" to "Reinforced cement concrete in beams and slab",
        "S04" to "Reinforcement steel (TMT bars)",
    )

    private val UK_DESCRIPTIONS = mapOf(
        "W01" to "Brickwork / blockwork to walls, thickness stated",
        "W02" to "Plaster to walls, two coats",
        "W03" to "Painting to walls",
        "F01" to "Floor finishes",
        "F02" to "Skirting",
        "F03" to "Ceiling finishes",
        "S01" to "Concrete in footings",
        "S02" to "Concrete in columns",
        "S03" to "Concrete in beams and slab",
        "S04" to "Reinforcement steel",
    )

    private val US_DESCRIPTIONS = mapOf(
        "W01" to "Masonry walls",
        "W02" to "Plaster / stucco to walls",
        "W03" to "Painting to walls",
        "F01" to "Floor finishes",
        "F02" to "Skirting / base",
        "F03" to "Ceiling finishes",
        "S01" to "Concrete in footings",
        "S02" to "Concrete in columns",
        "S03" to "Concrete in beams and slab",
        "S04" to "Reinforcing steel",
    )

    val GENERIC = StandardProfile(
        MeasurementStandard.GENERIC_METRIC, "$", METRIC,
        brickworkByArea = false, descriptions = GENERIC_DESCRIPTIONS,
        openingDeductionThresholdM2 = 0.10,
        preamble = listOf(
            "Measured from the traced plan; quantities are net unless a wastage allowance is stated.",
            "Openings up to 0.10 m2 are not deducted from wall areas.",
            "Rates are built up from the regional price book (material + labour + overhead).",
        ),
    )
    val INDIA = StandardProfile(
        MeasurementStandard.INDIA_CPWD, "Rs.", METRIC,
        brickworkByArea = false, descriptions = INDIA_DESCRIPTIONS,
        openingDeductionThresholdM2 = 0.10,
        preamble = listOf(
            "Measurements generally in accordance with CPWD measurement conventions.",
            "Openings exceeding 0.10 m2 are deducted from wall areas.",
            "12 mm plaster is measured on both faces; painting on the same finished surface.",
        ),
    )
    val UK = StandardProfile(
        MeasurementStandard.UK_BS, "GBP", METRIC,
        brickworkByArea = true, descriptions = UK_DESCRIPTIONS,
        openingDeductionThresholdM2 = 0.50,
        preamble = listOf(
            "Measurements generally in accordance with BS/SMM conventions.",
            "Openings exceeding 0.50 m2 are deducted from wall areas.",
            "Brickwork/blockwork is billed by area with the thickness stated.",
        ),
    )
    val US = StandardProfile(
        MeasurementStandard.US_IMPERIAL, "$", IMPERIAL,
        brickworkByArea = true, descriptions = US_DESCRIPTIONS,
        openingDeductionThresholdM2 = 0.929, // 10 sf
        preamble = listOf(
            "Measured in imperial units (sf, cy, ft, ton).",
            "Openings up to 10 sf (0.93 m2) are not deducted.",
            "Concrete is billed in cubic yards; reinforcement in short tons.",
        ),
    )

    fun of(standard: MeasurementStandard): StandardProfile = when (standard) {
        MeasurementStandard.GENERIC_METRIC -> GENERIC
        MeasurementStandard.INDIA_CPWD -> INDIA
        MeasurementStandard.UK_BS -> UK
        MeasurementStandard.US_IMPERIAL -> US
    }

    fun defaultCurrency(standard: MeasurementStandard): String = of(standard).currency

    val all: List<StandardProfile> = listOf(GENERIC, INDIA, UK, US)
}
