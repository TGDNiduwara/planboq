package com.planboq.app

import com.planboq.app.data.billing.FreeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTierTest {

    @Test
    fun `free user under limit can convert`() {
        assertTrue(FreeTier.canConvert(entitled = false, usedThisMonth = 2))
    }

    @Test
    fun `free user at limit cannot convert`() {
        assertFalse(FreeTier.canConvert(entitled = false, usedThisMonth = FreeTier.MONTHLY_LIMIT))
    }

    @Test
    fun `entitled user always can convert`() {
        assertTrue(FreeTier.canConvert(entitled = true, usedThisMonth = 999))
    }

    @Test
    fun `remaining counts down and floors at zero`() {
        assertEquals(2, FreeTier.remaining(false, 1))
        assertEquals(0, FreeTier.remaining(false, 5))
        assertEquals(Int.MAX_VALUE, FreeTier.remaining(true, 0))
    }

    @Test
    fun `watermark only for free`() {
        assertTrue(FreeTier.watermarkNeeded(false))
        assertFalse(FreeTier.watermarkNeeded(true))
    }
}
