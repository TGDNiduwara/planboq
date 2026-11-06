package com.planboq.app

import com.planboq.app.data.dxf.DxfParser
import com.planboq.app.data.dxf.DxfUnits
import com.planboq.app.data.dxf.DxfWriter
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class DxfTest {

    @Test
    fun `parses minimal dxf with line and lwpolyline`() {
        val dxf = """
            0
            SECTION
            2
            HEADER
            9
            ${'$'}INSUNITS
            70
            6
            0
            ENDSEC
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            8
            WALLS
            10
            0.0
            20
            0.0
            11
            5.0
            21
            0.0
            0
            LWPOLYLINE
            8
            WALLS
            90
            3
            70
            1
            10
            1.0
            20
            1.0
            10
            2.0
            20
            1.0
            10
            2.0
            20
            2.0
            0
            ENDSEC
            0
            EOF
        """.trimIndent()

        val doc = DxfParser().parse(StringReader(dxf), fallbackMmPerUnit = 1.0, fileName = "t")
        // units metres (INSUNITS 6) -> x1000
        assertEquals(1, doc.segments.size)
        assertEquals(1, doc.polylines.size)
        assertEquals(5000.0, doc.segments[0].b.x, 1e-6)
        assertTrue(doc.polylines[0].closed)
        // import flips y (DXF y-up -> editor y-down): maxY was 2000 -> this point maps to 0
        assertEquals(0.0, doc.polylines[0].points[2].y, 1e-6)
        assertEquals(1000.0, doc.polylines[0].points[0].y, 1e-6)
        assertTrue(doc.layers.any { it.name == "WALLS" })
    }

    @Test
    fun `fallback units used when header missing`() {
        val dxf = """
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            10
            0.0
            20
            0.0
            11
            2.0
            21
            0.0
            0
            ENDSEC
        """.trimIndent()
        val doc = DxfParser().parse(StringReader(dxf), fallbackMmPerUnit = 304.8, fileName = "t")
        assertEquals(609.6, doc.segments[0].b.x, 1e-6)
    }

    @Test
    fun `write then parse round-trips segments`() {
        val doc = PlanDocument(
            name = "roundtrip",
            segments = listOf(
                com.planboq.app.data.geometry.Segment(a = Vec2(0.0, 0.0), b = Vec2(3600.0, 0.0), layerId = "walls"),
                com.planboq.app.data.geometry.Segment(a = Vec2(0.0, 0.0), b = Vec2(0.0, 2400.0), layerId = "walls"),
            ),
            polylines = listOf(
                com.planboq.app.data.geometry.Polyline(
                    points = listOf(Vec2(0.0, 0.0), Vec2(1000.0, 0.0), Vec2(1000.0, 500.0)),
                    closed = true,
                    layerId = "other",
                ),
            ),
        )
        val sw = StringWriter()
        DxfWriter().write(doc, sw)
        val parsed = DxfParser().parse(StringReader(sw.toString()), fallbackMmPerUnit = 1.0, fileName = "rt")

        // the writer adds a scale bar + tick marks on layer PLANBOQ_ANNOT
        val geometry = parsed.segments.filter { it.layerId != "PLANBOQ_ANNOT" }
        assertEquals(2, geometry.size)
        assertEquals(3, parsed.segments.size - geometry.size)
        assertEquals(1, parsed.polylines.size)
        val orig = doc.segments.first { it.b.x == 3600.0 }
        val got = geometry.first { it.b.x == 3600.0 }
        assertEquals(orig.a.x, got.a.x, 1e-3)
        assertEquals(orig.b.y, got.b.y, 1e-3)
        assertTrue(parsed.polylines[0].closed)
        assertEquals(3, parsed.polylines[0].points.size)
        // EOF marker present
        assertTrue(sw.toString().contains("EOF"))
    }

    @Test
    fun `unit table converts common values`() {
        assertEquals(1.0, DxfUnits.mmPerUnit(4)!!, 1e-9)
        assertEquals(25.4, DxfUnits.mmPerUnit(1)!!, 1e-9)
        assertEquals(1000.0, DxfUnits.mmPerUnit(6)!!, 1e-9)
        assertEquals(null, DxfUnits.mmPerUnit(0))
    }

    @Test
    fun `layer kind guessed from name`() {
        val dxf = """
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            8
            WALL-EXT
            10
            0.0
            20
            0.0
            11
            1.0
            21
            0.0
            0
            LINE
            8
            DOORS
            10
            0.0
            20
            0.0
            11
            1.0
            21
            0.0
            0
            ENDSEC
        """.trimIndent()
        val doc = DxfParser().parse(StringReader(dxf), fallbackMmPerUnit = 1.0, fileName = "t")
        assertEquals(LayerKind.EXTERNAL_WALL, doc.layers.first { it.name == "WALL-EXT" }.kind)
        assertEquals(LayerKind.DOOR, doc.layers.first { it.name == "DOORS" }.kind)
    }
}
