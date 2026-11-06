package com.planboq.app

import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import com.planboq.app.imaging.LinePostProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinePostProcessorTest {

    private val proc = LinePostProcessor()
    private val layer = "L"

    @Test
    fun `snaps near-horizontal lines to exact horizontal`() {
        val raw = listOf(
            Segment(a = Vec2(0.0, 100.2), b = Vec2(500.0, 99.4), layerId = layer), // 0.09 deg
        )
        val out = proc.process(raw)
        assertEquals(1, out.size)
        assertEquals(out[0].a.y, out[0].b.y, 1e-9)
    }

    @Test
    fun `merges collinear fragments with small gap`() {
        val raw = listOf(
            Segment(a = Vec2(0.0, 0.0), b = Vec2(100.0, 0.0), layerId = layer),
            Segment(a = Vec2(108.0, 0.5), b = Vec2(300.0, 0.0), layerId = layer), // near gap 8 < 15
        )
        val out = proc.process(raw)
        assertEquals(1, out.size)
        assertEquals(300.0, out[0].b.x, 0.5)
    }

    @Test
    fun `keeps distinct parallel lines separate`() {
        val raw = listOf(
            Segment(a = Vec2(0.0, 0.0), b = Vec2(300.0, 0.0), layerId = layer),
            Segment(a = Vec2(0.0, 12.0), b = Vec2(300.0, 12.0), layerId = layer), // 12 > collinearTol
        )
        val out = proc.process(raw)
        assertEquals(2, out.size)
    }

    @Test
    fun `welds close endpoints`() {
        val raw = listOf(
            Segment(a = Vec2(0.0, 0.0), b = Vec2(100.0, 0.0), layerId = layer),
            Segment(a = Vec2(100.0, 0.0), b = Vec2(100.0, 100.0), layerId = layer),
        )
        val out = proc.process(raw)
        assertEquals(2, out.size)
        // shared corner identical
        val corner1 = out.first { it.b.x == 100.0 && it.b.y == 0.0 || it.a.x == 100.0 && it.a.y == 0.0 }
        val corner2 = out.first { it !== corner1 }
        val c1 = if (corner1.b.x == 100.0 && corner1.b.y == 0.0) corner1.b else corner1.a
        val c2 = if (corner2.b.x == 100.0 && corner2.b.y == 0.0) corner2.b else corner2.a
        assertEquals(c1, c2)
    }

    @Test
    fun `drops tiny fragments`() {
        val raw = listOf(
            Segment(a = Vec2(0.0, 0.0), b = Vec2(2.0, 0.0), layerId = layer),
            Segment(a = Vec2(0.0, 0.0), b = Vec2(500.0, 0.0), layerId = layer),
        )
        val out = proc.process(raw)
        assertEquals(1, out.size)
        assertTrue(out[0].length() > 100)
    }
}
