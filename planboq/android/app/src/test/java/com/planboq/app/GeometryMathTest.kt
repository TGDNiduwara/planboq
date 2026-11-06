package com.planboq.app

import com.planboq.app.data.geometry.PolygonMath
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import com.planboq.app.imaging.QuadGeometry
import com.planboq.app.imaging.WallJoiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryMathTest {

    @Test
    fun `shoelace area and perimeter of a rectangle`() {
        val rect = listOf(
            Vec2(0.0, 0.0), Vec2(4000.0, 0.0), Vec2(4000.0, 3000.0), Vec2(0.0, 3000.0),
        )
        assertEquals(12_000_000.0, PolygonMath.area(rect), 1e-6)
        assertEquals(14_000.0, PolygonMath.perimeter(rect), 1e-6)
        assertTrue(PolygonMath.contains(rect, Vec2(100.0, 100.0)))
        assertFalse(PolygonMath.contains(rect, Vec2(5000.0, 100.0)))
    }

    @Test
    fun `quad ordering produces TL TR BR BL for a rotated page`() {
        val raw = listOf(
            Vec2(120.0, 300.0), // BL-ish
            Vec2(210.0, 40.0),  // TL
            Vec2(900.0, 120.0), // TR
            Vec2(820.0, 380.0), // BR
        )
        val ordered = QuadGeometry.orderCorners(raw)
        assertNotNull(ordered)
        val q = ordered!!
        // TL has smallest x+y; BR largest
        assertEquals(0.0, (q[0].x + q[0].y) - raw.minOf { it.x + it.y }, 1e-9)
        assertEquals(0.0, (q[2].x + q[2].y) - raw.maxOf { it.x + it.y }, 1e-9)
        assertTrue(QuadGeometry.isConvex(q))
        assertTrue(QuadGeometry.isUsablePage(q, 1000, 500, minAreaFraction = 0.1))
    }

    @Test
    fun `degenerate or tiny quads are rejected`() {
        val tiny = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 5.0), Vec2(0.0, 5.0))
        assertFalse(QuadGeometry.isUsablePage(tiny, 1000, 1000))
        val triangleLike = listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 0.0), Vec2(0.0, 100.0))
        assertFalse(QuadGeometry.isUsablePage(triangleLike, 1000, 1000, 0.1))
        assertNull(QuadGeometry.orderCorners(listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0))))
    }

    @Test
    fun `wall joiner extends endpoints to close a corner`() {
        val horizontal = Segment(a = Vec2(0.0, 0.0), b = Vec2(90.0, 0.0), layerId = "L")
        val vertical = Segment(a = Vec2(100.0, -10.0), b = Vec2(100.0, 100.0), layerId = "L")
        val joined = WallJoiner.join(listOf(horizontal, vertical), tolerance = 15.0)
        val h = joined.first { it.a.y == 0.0 && it.b.y == 0.0 }
        val v = joined.first { it.a.x == 100.0 && it.b.x == 100.0 }
        assertEquals(100.0, h.b.x, 1e-6)
        assertEquals(0.0, v.a.y, 1e-6)
    }

    @Test
    fun `wall joiner leaves parallel lines alone`() {
        val a = Segment(a = Vec2(0.0, 0.0), b = Vec2(100.0, 0.0), layerId = "L")
        val b = Segment(a = Vec2(0.0, 20.0), b = Vec2(100.0, 20.0), layerId = "L")
        val joined = WallJoiner.join(listOf(a, b), tolerance = 30.0)
        assertEquals(a, joined[0])
        assertEquals(b, joined[1])
    }
}
