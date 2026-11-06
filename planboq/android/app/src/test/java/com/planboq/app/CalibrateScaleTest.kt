package com.planboq.app

import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Vec2
import com.planboq.app.domain.CalibrateScaleUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrateScaleTest {

    @Test
    fun `1 to 100 scale from 10px reference`() {
        val doc = PlanDocument()
        // 10 plan units = 1000 mm real  -> 100 mm per unit
        val scale = CalibrateScaleUseCase.calibrate(doc, Vec2(0.0, 0.0), Vec2(10.0, 0.0), 1000.0)
        assertTrue(scale.calibrated)
        assertEquals(100.0, scale.realMmPerPlanUnit, 1e-9)
        assertEquals("1 : 100", scale.label)
    }

    @Test
    fun `verification passes within 2 percent`() {
        val doc = PlanDocument()
        val scale = CalibrateScaleUseCase.calibrate(doc, Vec2(0.0, 0.0), Vec2(10.0, 0.0), 1000.0)
        // second reference: 5 units = 502mm -> implied 100.4 -> 0.4% error
        val v = CalibrateScaleUseCase.verify(scale, Vec2(0.0, 0.0), Vec2(5.0, 0.0), 502.0)
        assertNotNull(v)
        assertTrue(v!!.pass)
    }

    @Test
    fun `verification fails beyond 2 percent`() {
        val doc = PlanDocument()
        val scale = CalibrateScaleUseCase.calibrate(doc, Vec2(0.0, 0.0), Vec2(10.0, 0.0), 1000.0)
        val v = CalibrateScaleUseCase.verify(scale, Vec2(0.0, 0.0), Vec2(5.0, 0.0), 530.0)
        assertNotNull(v)
        assertFalse(v!!.pass)
        assertTrue(v.errorPct > 2.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero distance rejected`() {
        val doc = PlanDocument()
        CalibrateScaleUseCase.calibrate(doc, Vec2(1.0, 1.0), Vec2(1.0, 1.0), 1000.0)
    }
}
