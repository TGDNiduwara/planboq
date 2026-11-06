package com.planboq.app.data.billing

/**
 * Free-tier rules (Phase 1 MVP): limited conversions per month, watermark on
 * exports. Pure logic - JVM-testable.
 */
object FreeTier {
    const val MONTHLY_LIMIT = 3

    fun canConvert(entitled: Boolean, usedThisMonth: Int): Boolean =
        entitled || usedThisMonth < MONTHLY_LIMIT

    fun remaining(entitled: Boolean, usedThisMonth: Int): Int =
        if (entitled) Int.MAX_VALUE else (MONTHLY_LIMIT - usedThisMonth).coerceAtLeast(0)

    fun watermarkNeeded(entitled: Boolean): Boolean = !entitled
}
