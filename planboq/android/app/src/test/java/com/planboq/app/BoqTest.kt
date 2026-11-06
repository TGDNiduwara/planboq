package com.planboq.app

import com.planboq.app.boq.BoqItem
import com.planboq.app.boq.BoqSettings
import com.planboq.app.boq.MeasurementStandard
import com.planboq.app.boq.QuantityEngine
import com.planboq.app.boq.QuantitySource
import com.planboq.app.boq.WallCenterlines
import com.planboq.app.boq.XlsxWriter
import com.planboq.app.data.geometry.Layer
import com.planboq.app.data.geometry.LayerKind
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Room
import com.planboq.app.data.geometry.ScaleState
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class BoqTest {

    private val extLayer = Layer(id = "ext", name = "Walls", kind = LayerKind.EXTERNAL_WALL)
    private val intLayer = Layer(id = "int", name = "Partitions", kind = LayerKind.INTERNAL_WALL)
    private val doorLayer = Layer(id = "door", name = "Doors", kind = LayerKind.DOOR)
    private val windowLayer = Layer(id = "win", name = "Windows", kind = LayerKind.WINDOW)

    private val settings = BoqSettings(
        storeyHeightM = 3.0,
        slabThicknessM = 0.125,
        externalWallThicknessMm = 230.0,
        internalWallThicknessMm = 115.0,
        wastagePercent = 5.0,
    )

    @Test
    fun `double line walls pair into four exterior centre lines`() {
        val layers = listOf(extLayer, intLayer)
        val segments = mutableListOf<Segment>()
        // top and bottom wall faces (10 m long, 230 mm thick)
        segments += Segment(a = Vec2(0.0, 0.0), b = Vec2(10000.0, 0.0), layerId = "ext")
        segments += Segment(a = Vec2(0.0, 230.0), b = Vec2(10000.0, 230.0), layerId = "ext")
        segments += Segment(a = Vec2(0.0, 8000.0), b = Vec2(10000.0, 8000.0), layerId = "ext")
        segments += Segment(a = Vec2(0.0, 8230.0), b = Vec2(10000.0, 8230.0), layerId = "ext")
        // left and right wall faces (8 m long)
        segments += Segment(a = Vec2(0.0, 0.0), b = Vec2(0.0, 8000.0), layerId = "ext")
        segments += Segment(a = Vec2(230.0, 0.0), b = Vec2(230.0, 8000.0), layerId = "ext")
        segments += Segment(a = Vec2(9770.0, 0.0), b = Vec2(9770.0, 8000.0), layerId = "ext")
        segments += Segment(a = Vec2(10000.0, 0.0), b = Vec2(10000.0, 8000.0), layerId = "ext")
        // internal partition faces (115 mm)
        segments += Segment(a = Vec2(5000.0, 230.0), b = Vec2(5000.0, 8000.0), layerId = "int")
        segments += Segment(a = Vec2(5115.0, 230.0), b = Vec2(5115.0, 8000.0), layerId = "int")

        val lines = WallCenterlines.extract(
            segments, layers, ScaleState(calibrated = true, realMmPerPlanUnit = 1.0), settings,
        )
        val ext = lines.filter { it.kind == LayerKind.EXTERNAL_WALL }
        val int = lines.filter { it.kind == LayerKind.INTERNAL_WALL }
        assertEquals(4, ext.size)
        assertEquals(1, int.size)
        assertTrue(ext.all { it.thicknessMeasured })
        assertEquals(230.0, ext.first().thicknessMm, 1e-6)
        assertEquals(115.0, int.first().thicknessMm, 1e-6)
        // total centre-line length = 2 x 10 m + 2 x 8 m + 7.77 m partition
        val totalMm = lines.sumOf { it.lengthPlanUnits() }
        assertEquals(20000.0 + 16000.0 + 7770.0, totalMm, 1e-3)
    }

    @Test
    fun `quantity engine computes walls openings rooms and sources`() {
        val layers = listOf(extLayer, doorLayer, windowLayer)
        val segments = listOf(
            // 10 x 8 m wall rectangle as single lines (centre lines already)
            Segment(a = Vec2(0.0, 0.0), b = Vec2(10000.0, 0.0), layerId = "ext"),
            Segment(a = Vec2(10000.0, 0.0), b = Vec2(10000.0, 8000.0), layerId = "ext"),
            Segment(a = Vec2(10000.0, 8000.0), b = Vec2(0.0, 8000.0), layerId = "ext"),
            Segment(a = Vec2(0.0, 8000.0), b = Vec2(0.0, 0.0), layerId = "ext"),
            // one door leaf
            Segment(a = Vec2(2000.0, 0.0), b = Vec2(2900.0, 0.0), layerId = "door"),
            // one window drawn with two glazing lines
            Segment(a = Vec2(6000.0, 0.0), b = Vec2(7200.0, 0.0), layerId = "win"),
            Segment(a = Vec2(6000.0, 80.0), b = Vec2(7200.0, 80.0), layerId = "win"),
        )
        val room = Room(name = "Room 1", points = listOf(
            Vec2(500.0, 500.0), Vec2(5500.0, 500.0), Vec2(5500.0, 4500.0), Vec2(500.0, 4500.0),
        ))
        val doc = PlanDocument(
            layers = layers,
            segments = segments,
            rooms = listOf(room),
            scale = ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
        )

        val result = QuantityEngine.compute(doc, settings)
        fun item(code: String) = result.items.first { it.code == code }

        // walls: 36 m length x 2.875 m height = 103.5 m2 gross
        // openings: 1 door (0.9 x 2.1 = 1.89) + 1 window (1.2 x 1.2 = 1.44) = 3.33
        assertEquals(2.0 * (103.5 - 3.33), item("W02").quantity, 1e-6)
        assertEquals(QuantitySource.MEASURED, item("W02").source)

        // brick volume before wastage: 36 x 0.23 x 2.875 - 3.33 x 0.23
        val expectedBrick = (36 * 0.23 * 2.875 - 3.33 * 0.23) * 1.05
        assertEquals(expectedBrick, item("W01").quantity, 1e-6)

        // room 5 x 4 m = 20 m2; flooring with 5% wastage
        assertEquals(20.0 * 1.05, item("F01").quantity, 1e-6)
        assertEquals(20.0, item("F03").quantity, 1e-6)
        // skirting 18 m - 0.9 door = 17.1, with wastage
        assertEquals(17.1 * 1.05, item("F02").quantity, 1e-6)

        // structural items are manual with zero quantity
        assertEquals(QuantitySource.MANUAL, item("S01").source)
        assertEquals(0.0, item("S01").quantity, 0.0)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `uncalibrated plans warn and mark quantities assumed`() {
        val layers = listOf(extLayer)
        val doc = PlanDocument(
            layers = layers,
            segments = listOf(
                Segment(a = Vec2(0.0, 0.0), b = Vec2(1000.0, 0.0), layerId = "ext"),
            ),
            scale = ScaleState(calibrated = false, realMmPerPlanUnit = 1.0),
        )
        val result = QuantityEngine.compute(doc, settings)
        assertTrue(result.warnings.any { it.contains("not calibrated") })
        assertTrue(result.warnings.any { it.contains("No rooms") })
        assertEquals(QuantitySource.ASSUMED, result.items.first { it.code == "W02" }.source)
    }

    @Test
    fun `typed openings use their family sizes`() {
        val doorType = com.planboq.app.boq.OpeningType(
            id = "d2", name = "D2", kind = LayerKind.DOOR, widthM = 1.2, heightM = 2.1,
        )
        val windowType = com.planboq.app.boq.OpeningType(
            id = "w2", name = "W2", kind = LayerKind.WINDOW, widthM = 1.5, heightM = 1.2,
        )
        val custom = settings.copy(openingTypes = listOf(doorType, windowType))
        val layers = listOf(extLayer, doorLayer, windowLayer)
        val doc = PlanDocument(
            layers = layers,
            segments = listOf(
                Segment(a = Vec2(0.0, 0.0), b = Vec2(10000.0, 0.0), layerId = "ext"),
                Segment(a = Vec2(10000.0, 0.0), b = Vec2(10000.0, 8000.0), layerId = "ext"),
                Segment(a = Vec2(10000.0, 8000.0), b = Vec2(0.0, 8000.0), layerId = "ext"),
                Segment(a = Vec2(0.0, 8000.0), b = Vec2(0.0, 0.0), layerId = "ext"),
                Segment(a = Vec2(1000.0, 0.0), b = Vec2(2200.0, 0.0), layerId = "door", openingTypeId = "d2"),
                Segment(a = Vec2(5000.0, 0.0), b = Vec2(6500.0, 0.0), layerId = "win", openingTypeId = "w2"),
            ),
            scale = ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
        )
        val result = QuantityEngine.compute(doc, custom)
        val openings = 1.2 * 2.1 + 1.5 * 1.2
        val gross = 36.0 * 2.875
        assertEquals(2.0 * (gross - openings), result.items.first { it.code == "W02" }.quantity, 1e-6)
    }

    @Test
    fun `layer thickness override applies to unpaired walls`() {
        val thickLayer = Layer(id = "ext2", name = "Walls", kind = LayerKind.EXTERNAL_WALL, thicknessMm = 150.0)
        val lines = WallCenterlines.extract(
            listOf(Segment(a = Vec2(0.0, 0.0), b = Vec2(5000.0, 0.0), layerId = "ext2")),
            listOf(thickLayer),
            ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
            settings,
        )
        assertEquals(1, lines.size)
        assertEquals(150.0, lines[0].thicknessMm, 1e-9)
    }

    @Test
    fun `excluded rooms drop out of flooring`() {
        val room = Room(
            name = "Ignored", excluded = true,
            points = listOf(Vec2(0.0, 0.0), Vec2(1000.0, 0.0), Vec2(1000.0, 1000.0), Vec2(0.0, 1000.0)),
        )
        val doc = PlanDocument(
            layers = listOf(extLayer),
            segments = listOf(Segment(a = Vec2(0.0, 0.0), b = Vec2(1000.0, 0.0), layerId = "ext")),
            rooms = listOf(room),
            scale = ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
        )
        val result = QuantityEngine.compute(doc, settings)
        assertEquals(0.0, result.items.first { it.code == "F01" }.quantity, 1e-9)
    }

    @Test
    fun `combined boq sums quantities per code`() {
        val a = BoqItem(code = "W01", description = "Brickwork", unit = "m3", quantity = 10.0, rate = 100.0)
        val b = BoqItem(code = "W01", description = "Brickwork", unit = "m3", quantity = 5.0, rate = 0.0)
        val c = BoqItem(code = "F01", description = "Flooring", unit = "m2", quantity = 20.0, rate = 40.0)
        val combined = com.planboq.app.boq.CombinedBoq.combine(
            listOf(
                com.planboq.app.boq.CombinedBoq.Entry("Ground", listOf(a, c)),
                com.planboq.app.boq.CombinedBoq.Entry("First", listOf(b)),
            ),
        )
        val brick = combined.first { it.code == "W01" }
        assertEquals(15.0, brick.quantity, 1e-9)
        assertEquals(100.0, brick.rate, 1e-9)
        assertEquals(20.0, combined.first { it.code == "F01" }.quantity, 1e-9)
    }

    @Test
    fun `rate library builds up and applies to zero rates`() {
        val entry = com.planboq.app.boq.RateEntry(
            code = "W01", description = "Brickwork", unit = "m3",
            materialCost = 100.0, labourCost = 50.0, overheadPercent = 10.0,
        )
        assertEquals(165.0, entry.effectiveRate(), 1e-9)
        val direct = entry.copy(directRate = 200.0)
        assertEquals(200.0, direct.effectiveRate(), 1e-9)

        val items = listOf(
            BoqItem(code = "W01", description = "Brickwork", unit = "m3", quantity = 2.0, rate = 0.0),
            BoqItem(code = "W01", description = "Brickwork", unit = "m3", quantity = 2.0, rate = 7.0),
        )
        val applied = com.planboq.app.boq.BoqRates.apply(
            items, com.planboq.app.boq.RateLibrary(listOf(entry)),
        )
        assertEquals(165.0, applied[0].rate, 1e-9)
        assertEquals(7.0, applied[1].rate, 1e-9)
    }

    @Test
    fun `contingency adds to the grand total`() {
        val doc = com.planboq.app.boq.BoqDocument(
            projectId = "p",
            settings = settings.copy(contingencyPercent = 10.0),
            items = listOf(BoqItem(code = "W01", description = "x", unit = "m3", quantity = 1.0, rate = 100.0)),
        )
        assertEquals(100.0, doc.total(), 1e-9)
        assertEquals(10.0, doc.contingencyAmount(), 1e-9)
        assertEquals(110.0, doc.grandTotal(), 1e-9)
    }

    @Test
    fun `readiness reports blockers and accepts reviewed assumptions`() {
        val layers = listOf(extLayer, doorLayer, windowLayer)
        val baseSegments = listOf(
            Segment(a = Vec2(0.0, 0.0), b = Vec2(10000.0, 0.0), layerId = "ext"),
            Segment(a = Vec2(10000.0, 0.0), b = Vec2(10000.0, 8000.0), layerId = "ext"),
            Segment(a = Vec2(10000.0, 8000.0), b = Vec2(0.0, 8000.0), layerId = "ext"),
            Segment(a = Vec2(0.0, 8000.0), b = Vec2(0.0, 0.0), layerId = "ext"),
            Segment(a = Vec2(1000.0, 0.0), b = Vec2(1900.0, 0.0), layerId = "door"),
        )
        val items = listOf(BoqItem(code = "W01", description = "x", unit = "m3", quantity = 1.0, rate = 0.0))

        val uncalibrated = PlanDocument(layers = layers, segments = baseSegments, scale = ScaleState())
        val r1 = com.planboq.app.boq.BoqReadiness.evaluate(uncalibrated, settings, items, emptySet())
        assertTrue(r1.blocking >= 2)
        assertTrue(!r1.isExportable)
        assertTrue(r1.has(com.planboq.app.boq.GapKey.SCALE))

        val calibrated = uncalibrated.copy(
            rooms = listOf(Room(name = "R", points = listOf(Vec2(0.0, 0.0), Vec2(5000.0, 0.0), Vec2(5000.0, 4000.0), Vec2(0.0, 4000.0)))),
            scale = ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
        )
        val r2 = com.planboq.app.boq.BoqReadiness.evaluate(calibrated, settings, items, emptySet())
        assertTrue(r2.isExportable)
        assertTrue(r2.has(com.planboq.app.boq.GapKey.STOREY))
        assertTrue(r2.has(com.planboq.app.boq.GapKey.RATES))

        val acceptedAll = r2.gaps.filter { it.severity != com.planboq.app.boq.GapSeverity.BLOCKING }
            .map { it.key.id }.toSet()
        val r3 = com.planboq.app.boq.BoqReadiness.evaluate(calibrated, settings, items, acceptedAll)
        assertTrue(r3.gaps.isEmpty())
        assertEquals(100, r3.score)
    }

    @Test
    fun `structural data produces assumed concrete and steel quantities`() {
        val structural = com.planboq.app.boq.StructuralData(
            enabled = true,
            columnCount = 8,
            columnWidthMm = 300.0,
            columnDepthMm = 300.0,
            footingLengthM = 1.2,
            footingWidthM = 1.2,
            footingDepthM = 0.3,
            beamWidthMm = 230.0,
            beamDepthMm = 450.0,
            beamLengthM = 20.0,
            steelKgPerM3 = 90.0,
        )
        val s = settings.copy(structural = structural)
        val doc = PlanDocument(
            layers = listOf(extLayer),
            segments = listOf(
                Segment(a = Vec2(0.0, 0.0), b = Vec2(10000.0, 0.0), layerId = "ext"),
                Segment(a = Vec2(10000.0, 0.0), b = Vec2(10000.0, 8000.0), layerId = "ext"),
                Segment(a = Vec2(10000.0, 8000.0), b = Vec2(0.0, 8000.0), layerId = "ext"),
                Segment(a = Vec2(0.0, 8000.0), b = Vec2(0.0, 0.0), layerId = "ext"),
            ),
            rooms = listOf(Room(name = "R", points = listOf(Vec2(0.0, 0.0), Vec2(5000.0, 0.0), Vec2(5000.0, 4000.0), Vec2(0.0, 4000.0)))),
            scale = ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
        )
        val result = QuantityEngine.compute(doc, s)
        fun item(code: String) = result.items.first { it.code == code }

        assertEquals(8 * 1.2 * 1.2 * 0.3, item("S01").quantity, 1e-6)
        assertEquals(8 * 0.3 * 0.3 * 2.875, item("S02").quantity, 1e-6)
        assertEquals(20.0 * 0.23 * 0.45 + 20.0 * 0.125, item("S03").quantity, 1e-6)
        val steelKg = (8 * 1.2 * 1.2 * 0.3 + 8 * 0.3 * 0.3 * 2.875 + 20 * 0.23 * 0.45 + 20 * 0.125) * 90.0
        assertEquals(steelKg, item("S04").quantity, 1e-6)
        assertEquals(QuantitySource.ASSUMED, item("S01").source)
    }

    @Test
    fun `standards change units and brickwork basis`() {
        val doc = PlanDocument(
            layers = listOf(extLayer),
            segments = listOf(
                Segment(a = Vec2(0.0, 0.0), b = Vec2(10000.0, 0.0), layerId = "ext"),
                Segment(a = Vec2(10000.0, 0.0), b = Vec2(10000.0, 8000.0), layerId = "ext"),
                Segment(a = Vec2(10000.0, 8000.0), b = Vec2(0.0, 8000.0), layerId = "ext"),
                Segment(a = Vec2(0.0, 8000.0), b = Vec2(0.0, 0.0), layerId = "ext"),
            ),
            rooms = listOf(Room(name = "R", points = listOf(Vec2(0.0, 0.0), Vec2(5000.0, 0.0), Vec2(5000.0, 4000.0), Vec2(0.0, 4000.0)))),
            scale = ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
        )
        val metric = QuantityEngine.compute(doc, settings.copy(standard = MeasurementStandard.GENERIC_METRIC))
        assertEquals("m3", metric.items.first { it.code == "W01" }.unit)

        val uk = QuantityEngine.compute(doc, settings.copy(standard = MeasurementStandard.UK_BS))
        assertEquals("m2", uk.items.first { it.code == "W01" }.unit)

        val us = QuantityEngine.compute(doc, settings.copy(standard = MeasurementStandard.US_IMPERIAL))
        assertEquals("sf", us.items.first { it.code == "W01" }.unit)
        assertEquals("ft", us.items.first { it.code == "F02" }.unit)
        assertEquals("ton", us.items.first { it.code == "S04" }.unit)
    }

    @Test
    fun `boq exporter writes cover schedule boq and assumptions sheets`() {
        val room = Room(
            name = "Living", points = listOf(Vec2(0.0, 0.0), Vec2(5000.0, 0.0), Vec2(5000.0, 4000.0), Vec2(0.0, 4000.0)),
        )
        val plan = PlanDocument(
            name = "Flat", client = "Client A", location = "Site B", floorName = "Ground",
            rooms = listOf(room), scale = ScaleState(calibrated = true, realMmPerPlanUnit = 1.0),
        )
        val doc = com.planboq.app.boq.BoqDocument(
            projectId = "p",
            settings = settings.copy(contingencyPercent = 10.0),
            items = listOf(BoqItem(code = "W01", description = "Brickwork in walls", unit = "m3", quantity = 2.0, rate = 100.0)),
        )
        val readiness = com.planboq.app.boq.BoqReadiness.evaluate(plan, doc.settings, doc.items, emptySet())
        val file = java.io.File.createTempFile("planboq_boq4", ".xlsx")
        com.planboq.app.boq.BoqExporter().exportXlsx(doc, plan, "Flat BOQ", file, readiness)

        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(file.readBytes())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        assertTrue(contents.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(contents.containsKey("xl/worksheets/sheet2.xml"))
        assertTrue(contents.containsKey("xl/worksheets/sheet3.xml"))
        assertTrue(contents.containsKey("xl/worksheets/sheet4.xml"))
        assertTrue(contents["xl/workbook.xml"]!!.contains("Room Schedule"))
        assertTrue(contents["xl/workbook.xml"]!!.contains("Assumptions"))
        assertTrue(contents["xl/worksheets/sheet2.xml"]!!.contains("ROOM SCHEDULE"))
        assertTrue(contents["xl/worksheets/sheet3.xml"]!!.contains("GRAND TOTAL"))
        assertTrue(contents["xl/worksheets/sheet4.xml"]!!.contains("ASSUMPTIONS"))
        assertTrue(contents["xl/worksheets/sheet1.xml"]!!.contains("Client A"))
        file.delete()
    }

    @Test
    fun `merge lets computed structural estimates replace placeholders but keeps edits`() {
        val placeholder = BoqItem(code = "S01", description = "x", unit = "m3", quantity = 0.0, rate = 0.0, source = QuantitySource.MANUAL)
        val computed = BoqItem(code = "S01", description = "Concrete in footings", unit = "m3", quantity = 3.456, rate = 0.0, source = QuantitySource.ASSUMED)
        val merged = com.planboq.app.boq.BoqMerge.merge(listOf(placeholder), listOf(computed))
        assertEquals(3.456, merged.first().quantity, 1e-9)
        assertEquals(QuantitySource.ASSUMED, merged.first().source)

        val edited = placeholder.copy(quantity = 5.0, rate = 100.0)
        val merged2 = com.planboq.app.boq.BoqMerge.merge(listOf(edited), listOf(computed))
        assertEquals(5.0, merged2.first().quantity, 1e-9)
        assertEquals(100.0, merged2.first().rate, 1e-9)
        assertEquals(QuantitySource.MANUAL, merged2.first().source)
    }

    @Test
    fun `boq exporter writes a workbook with items total and disclaimer`() {
        val doc = com.planboq.app.boq.BoqDocument(
            projectId = "p",
            items = listOf(
                com.planboq.app.boq.BoqItem(
                    code = "W01", description = "Brickwork in walls", unit = "m3",
                    quantity = 2.0, rate = 100.0,
                ),
            ),
        )
        val file = java.io.File.createTempFile("planboq_boq", ".xlsx")
        com.planboq.app.boq.BoqExporter().exportXlsx(doc, null, "Project X", file)
        assertTrue(file.length() > 0)

        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(file.readBytes())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        val allSheets = contents.filterKeys { it.startsWith("xl/worksheets/") }
            .values.joinToString("\n")
        assertTrue(allSheets.contains("Brickwork in walls"))
        assertTrue(allSheets.contains("GRAND TOTAL"))
        assertTrue(allSheets.contains("200")) // 2.0 x 100.0
        assertTrue(allSheets.contains("estimates"))
        file.delete()
    }

    @Test
    fun `xlsx writer produces a valid zip with expected parts`() {
        val writer = XlsxWriter("BOQ")
        writer.addRow(XlsxWriter.Cell.Text("BILL OF QUANTITIES", style = 3))
        writer.addRow(
            XlsxWriter.Cell.Text("Description", style = 1),
            XlsxWriter.Cell.Number(12.5),
        )
        val bytes = writer.toBytes()

        val names = mutableListOf<String>()
        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val text = zip.readBytes().toString(Charsets.UTF_8)
                names += entry.name
                contents[entry.name] = text
                entry = zip.nextEntry
            }
        }
        assertTrue(names.contains("[Content_Types].xml"))
        assertTrue(names.contains("xl/workbook.xml"))
        assertTrue(names.contains("xl/worksheets/sheet1.xml"))
        val sheet = contents["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("inlineStr"))
        assertTrue(sheet.contains("12.5"))
        assertTrue(sheet.contains("BILL OF QUANTITIES"))
    }
}
