package com.planboq.app.data.dxf

/**
 * DXF $INSUNITS header value -> millimetres per drawing unit.
 * Unknown / unitless (0) returns null so the caller can ask the user.
 */
object DxfUnits {
    fun mmPerUnit(insUnits: Int): Double? = when (insUnits) {
        0 -> null            // unitless -> user must choose
        1 -> 25.4            // inches
        2 -> 304.8           // feet
        4 -> 1.0             // millimetres
        5 -> 10.0            // centimetres
        6 -> 1000.0          // metres
        8 -> 25.4e-6         // microinches
        9 -> 25.4e-3         // mils
        10 -> 1.0e6          // kilometres
        13 -> 2.54           // centi-inch? (decimeters per some CADs)
        14 -> 2540.0         // ? not standard; treat conservatively below
        else -> null
    }

    /** Options offered to the user when a DXF has no usable unit. */
    val userOptions: List<Pair<String, Double>> = listOf(
        "Millimetres" to 1.0,
        "Centimetres" to 10.0,
        "Metres" to 1000.0,
        "Inches" to 25.4,
        "Feet" to 304.8,
    )
}
