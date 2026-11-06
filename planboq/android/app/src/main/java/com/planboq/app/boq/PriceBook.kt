package com.planboq.app.boq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class PriceRegion(val label: String, val currency: String) {
    GENERIC("Generic (your market)", "$"),
    INDIA("India", "Rs."),
    UK("United Kingdom", "GBP"),
    US("United States", "$"),
}

@Serializable
data class MaterialPrice(
    val id: String,
    val name: String,
    val unit: String,
    val price: Double,
    val note: String = "",
)

@Serializable
data class LabourPrice(
    val id: String,
    val trade: String,
    val unit: String = "day",
    val rate: Double,
    val note: String = "",
)

@Serializable
data class PriceBook(
    val region: PriceRegion = PriceRegion.GENERIC,
    val currency: String = "$",
    val materials: List<MaterialPrice> = emptyList(),
    val labour: List<LabourPrice> = emptyList(),
) {
    fun material(id: String): MaterialPrice? = materials.firstOrNull { it.id == id }
    fun labour(id: String): LabourPrice? = labour.firstOrNull { it.id == id }
}

/** Starter regional books: every price is editable in the app. */
object PriceBooks {

    const val BRICKS = "brick"
    const val CEMENT = "cement"
    const val SAND = "sand"
    const val AGGREGATE = "aggregate"
    const val STEEL = "steel"
    const val TILES = "tile"
    const val PAINT = "paint"

    const val MASON = "mason"
    const val LABOURER = "labourer"
    const val CARPENTER = "carpenter"
    const val PAINTER = "painter"
    const val BAR_BENDER = "bar_bender"
    const val TILER = "tiler"

    private fun materials(
        brick: Double, cement: Double, sand: Double, aggregate: Double,
        steel: Double, tile: Double, paint: Double,
    ) = listOf(
        MaterialPrice(BRICKS, "Bricks / blocks", "1000 nos", brick),
        MaterialPrice(CEMENT, "Cement", "50 kg bag", cement),
        MaterialPrice(SAND, "Sand", "m3", sand),
        MaterialPrice(AGGREGATE, "Aggregate", "m3", aggregate),
        MaterialPrice(STEEL, "Reinforcement steel", "kg", steel),
        MaterialPrice(TILES, "Floor / wall tiles", "m2", tile),
        MaterialPrice(PAINT, "Paint", "litre", paint),
    )

    private fun labour(
        mason: Double, labourer: Double, carpenter: Double,
        painter: Double, barBender: Double, tiler: Double,
    ) = listOf(
        LabourPrice(MASON, "Mason / bricklayer", "day", mason),
        LabourPrice(LABOURER, "Labourer", "day", labourer),
        LabourPrice(CARPENTER, "Carpenter", "day", carpenter),
        LabourPrice(PAINTER, "Painter", "day", painter),
        LabourPrice(BAR_BENDER, "Bar bender", "day", barBender),
        LabourPrice(TILER, "Tiler", "day", tiler),
    )

    fun defaults(region: PriceRegion): PriceBook = when (region) {
        PriceRegion.GENERIC -> PriceBook(
            region, "$",
            materials(550.0, 8.0, 45.0, 40.0, 1.2, 25.0, 30.0),
            labour(250.0, 150.0, 250.0, 200.0, 250.0, 220.0),
        )
        PriceRegion.INDIA -> PriceBook(
            region, "Rs.",
            materials(9000.0, 420.0, 1800.0, 1600.0, 75.0, 700.0, 350.0),
            labour(900.0, 650.0, 950.0, 850.0, 900.0, 900.0),
        )
        PriceRegion.UK -> PriceBook(
            region, "GBP",
            materials(550.0, 9.0, 55.0, 45.0, 1.1, 25.0, 6.0),
            labour(250.0, 150.0, 260.0, 210.0, 240.0, 240.0),
        )
        PriceRegion.US -> PriceBook(
            region, "$",
            materials(650.0, 12.0, 35.0, 40.0, 1.3, 30.0, 8.0),
            labour(400.0, 250.0, 380.0, 300.0, 380.0, 350.0),
        )
    }
}

/** One price book per region, plus the active region. */
class PriceBookRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(): File = File(context.filesDir, "pricebooks").apply { mkdirs() }

    private fun fileFor(region: PriceRegion) = File(dir(), "${region.name.lowercase()}.json")

    private fun activeFile() = File(dir(), "active.txt")

    suspend fun load(region: PriceRegion): PriceBook = withContext(Dispatchers.IO) {
        val f = fileFor(region)
        if (!f.exists()) return@withContext PriceBooks.defaults(region)
        try {
            json.decodeFromString(PriceBook.serializer(), f.readText())
        } catch (t: Throwable) {
            PriceBooks.defaults(region)
        }
    }

    suspend fun save(book: PriceBook) = withContext(Dispatchers.IO) {
        fileFor(book.region).writeText(json.encodeToString(PriceBook.serializer(), book))
    }

    suspend fun activeRegion(): PriceRegion = withContext(Dispatchers.IO) {
        val f = activeFile()
        if (!f.exists()) return@withContext PriceRegion.GENERIC
        try {
            PriceRegion.valueOf(f.readText().trim())
        } catch (t: Throwable) {
            PriceRegion.GENERIC
        }
    }

    suspend fun setActiveRegion(region: PriceRegion) = withContext(Dispatchers.IO) {
        activeFile().writeText(region.name)
    }

    suspend fun loadActive(): PriceBook = load(activeRegion())
}
