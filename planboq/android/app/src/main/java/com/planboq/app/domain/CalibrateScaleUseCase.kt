package com.planboq.app.domain

import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.ScaleReference
import com.planboq.app.data.geometry.ScaleState
import com.planboq.app.data.geometry.Vec2
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scale calibration: the user taps two points on the plan and types the real
 * distance between them. [verify] supports the doc's "check a second known
 * dimension" rule.
 */
object CalibrateScaleUseCase {

    data class Verification(val errorPct: Double, val pass: Boolean)

    fun calibrate(
        doc: PlanDocument,
        p1: Vec2,
        p2: Vec2,
        knownLengthMm: Double,
    ): ScaleState {
        require(knownLengthMm > 0) { "Known length must be positive" }
        val d = p1.distanceTo(p2)
        require(d > 1e-9) { "The two points must be distinct" }
        val ratio = knownLengthMm / d
        val label = if (ratio >= 1.0) {
            "1 : ${ratio.roundToInt().coerceAtLeast(1)}"
        } else {
            "%.2f mm/unit".format(ratio)
        }
        return ScaleState(
            calibrated = true,
            realMmPerPlanUnit = ratio,
            label = label,
            reference = ScaleReference(p1, p2, knownLengthMm),
        )
    }

    /** Checks a second reference against the calibrated scale. Tolerance 2%. */
    fun verify(
        state: ScaleState,
        p1: Vec2,
        p2: Vec2,
        knownLengthMm: Double,
    ): Verification? {
        if (!state.calibrated) return null
        val d = p1.distanceTo(p2)
        if (d <= 1e-9 || knownLengthMm <= 0) return null
        val implied = knownLengthMm / d
        val errorPct = abs(implied - state.realMmPerPlanUnit) / state.realMmPerPlanUnit * 100.0
        return Verification(errorPct, errorPct <= 2.0)
    }
}
