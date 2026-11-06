package com.planboq.app.boq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class RateComponentKind { MATERIAL, LABOUR }

/**
 * One line of a rate build-up: a material consumed or labour spent per unit of
 * the item (referencing the regional price book by id).
 */
@Serializable
data class RateComponent(
    val kind: RateComponentKind,
    val refId: String,
    val consumption: Double,
)

@Serializable
data class RateEntry(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val description: String,
    /** metric base unit of the build-up: m2, m3, m, kg (or the item unit for direct rates) */
    val unit: String,
    val materialCost: Double = 0.0,
    val labourCost: Double = 0.0,
    val overheadPercent: Double = 0.0,
    /** When set, used directly and the build-up is ignored. */
    val directRate: Double? = null,
    val components: List<RateComponent> = emptyList(),
    val note: String = "",
) {
    fun effectiveRate(): Double =
        directRate ?: (materialCost + labourCost) * (1.0 + overheadPercent / 100.0)
}

@Serializable
data class RateLibrary(val entries: List<RateEntry> = emptyList()) {
    fun byCode(code: String): RateEntry? = entries.firstOrNull { it.code == code }
}

/** Computes item rates from the regional price book. */
object RateCalculator {

    data class Line(
        val kind: RateComponentKind,
        val label: String,
        val unit: String,
        val quantity: Double,
        val price: Double,
    ) {
        val amount: Double get() = quantity * price
    }

    data class Breakdown(
        val lines: List<Line>,
        val materialTotal: Double,
        val labourTotal: Double,
        val overheadPercent: Double,
        val overheadAmount: Double,
        val rate: Double,
    )

    /** Null when the entry is a direct rate or has no components. */
    fun breakdown(entry: RateEntry, book: PriceBook): Breakdown? {
        if (entry.directRate != null || entry.components.isEmpty()) return null
        val lines = entry.components.mapNotNull { component ->
            when (component.kind) {
                RateComponentKind.MATERIAL -> book.material(component.refId)?.let { material ->
                    Line(RateComponentKind.MATERIAL, material.name, material.unit, component.consumption, material.price)
                }
                RateComponentKind.LABOUR -> book.labour(component.refId)?.let { labour ->
                    Line(RateComponentKind.LABOUR, labour.trade, labour.unit, component.consumption, labour.rate)
                }
            }
        }
        if (lines.isEmpty()) return null
        val materialTotal = lines.filter { it.kind == RateComponentKind.MATERIAL }.sumOf { it.amount }
        val labourTotal = lines.filter { it.kind == RateComponentKind.LABOUR }.sumOf { it.amount }
        val base = materialTotal + labourTotal
        val overheadAmount = base * entry.overheadPercent / 100.0
        return Breakdown(lines, materialTotal, labourTotal, entry.overheadPercent, overheadAmount, base + overheadAmount)
    }

    /** Rate per the entry's base unit (no unit conversion). */
    fun rate(entry: RateEntry, book: PriceBook?): Double {
        entry.directRate?.let { return it }
        if (book != null) {
            breakdown(entry, book)?.let { return it.rate }
        }
        return entry.effectiveRate()
    }
}

/** Seeded build-ups so a regional book prices the standard items out of the box. */
object RateDefaults {

    private fun component(kind: RateComponentKind, refId: String, consumption: Double) =
        RateComponent(kind, refId, consumption)

    private fun entry(code: String, unit: String, components: List<RateComponent>, overhead: Double = 10.0) =
        RateEntry(
            id = "seed-$code",
            code = code,
            description = "Regional build-up for $code",
            unit = unit,
            components = components,
            overheadPercent = overhead,
        )

    fun entries(): List<RateEntry> = listOf(
        entry(
            "W01", "m3", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.BRICKS, 500.0),
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 1.5),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.35),
                component(RateComponentKind.LABOUR, PriceBooks.MASON, 1.2),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 2.0),
            ),
        ),
        entry(
            "W02", "m2", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 0.12),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.015),
                component(RateComponentKind.LABOUR, PriceBooks.MASON, 0.08),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 0.10),
            ),
        ),
        entry(
            "W03", "m2", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.PAINT, 0.15),
                component(RateComponentKind.LABOUR, PriceBooks.PAINTER, 0.05),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 0.02),
            ),
        ),
        entry(
            "F01", "m2", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.TILES, 1.05),
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 0.3),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.03),
                component(RateComponentKind.LABOUR, PriceBooks.TILER, 0.15),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 0.10),
            ),
        ),
        entry(
            "F02", "m", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.TILES, 0.1),
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 0.02),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.003),
                component(RateComponentKind.LABOUR, PriceBooks.MASON, 0.05),
            ),
        ),
        entry(
            "F03", "m2", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 0.1),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.012),
                component(RateComponentKind.LABOUR, PriceBooks.MASON, 0.07),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 0.08),
            ),
        ),
        entry(
            "S01", "m3", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 6.0),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.45),
                component(RateComponentKind.MATERIAL, PriceBooks.AGGREGATE, 0.9),
                component(RateComponentKind.LABOUR, PriceBooks.MASON, 0.8),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 1.5),
            ),
        ),
        entry(
            "S02", "m3", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 6.0),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.45),
                component(RateComponentKind.MATERIAL, PriceBooks.AGGREGATE, 0.9),
                component(RateComponentKind.LABOUR, PriceBooks.MASON, 0.8),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 1.5),
            ),
        ),
        entry(
            "S03", "m3", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.CEMENT, 6.0),
                component(RateComponentKind.MATERIAL, PriceBooks.SAND, 0.45),
                component(RateComponentKind.MATERIAL, PriceBooks.AGGREGATE, 0.9),
                component(RateComponentKind.LABOUR, PriceBooks.MASON, 0.8),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 1.5),
            ),
        ),
        entry(
            "S04", "kg", listOf(
                component(RateComponentKind.MATERIAL, PriceBooks.STEEL, 1.0),
                component(RateComponentKind.LABOUR, PriceBooks.BAR_BENDER, 0.008),
                component(RateComponentKind.LABOUR, PriceBooks.LABOURER, 0.006),
            ),
        ),
    )

    /** Adds any missing seeded codes; existing user entries are preserved. */
    fun seed(library: RateLibrary): RateLibrary {
        val existing = library.entries.map { it.code }.toSet()
        val additions = entries().filterNot { it.code in existing }
        return if (additions.isEmpty()) library else RateLibrary(library.entries + additions)
    }
}

/** Applies rates from the library/price book to items that have no rate yet. */
object BoqRates {

    fun apply(items: List<BoqItem>, library: RateLibrary, book: PriceBook? = null): List<BoqItem> =
        items.map { item ->
            if (item.rate != 0.0) {
                item
            } else {
                val entry = library.byCode(item.code) ?: library.byCode(baseCode(item.code))
                if (entry == null) {
                    item
                } else {
                    val baseRate = RateCalculator.rate(entry, book)
                    val converted = convertRate(baseRate, entry.unit, item.unit)
                    if (converted == 0.0) item else item.copy(rate = converted)
                }
            }
        }

    /** W01-230 falls back to a generic W01 build-up. */
    fun baseCode(code: String): String = if (code.startsWith("W01-")) "W01" else code

    /** Converts a rate between metric base units and imperial output units. */
    fun convertRate(rate: Double, fromUnit: String, toUnit: String): Double = when {
        fromUnit == toUnit -> rate
        fromUnit == "m2" && toUnit == "sf" -> rate / 10.7639
        fromUnit == "m3" && toUnit == "cy" -> rate * 0.7645549
        fromUnit == "m" && toUnit == "ft" -> rate * 0.3048
        fromUnit == "kg" && toUnit == "ton" -> rate * 907.18474
        else -> rate
    }
}

class RateRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(): File = File(context.filesDir, "rates.json")

    suspend fun load(): RateLibrary = withContext(Dispatchers.IO) {
        val f = file()
        if (!f.exists()) RateLibrary() else try {
            json.decodeFromString(RateLibrary.serializer(), f.readText())
        } catch (t: Throwable) {
            RateLibrary()
        }
    }

    suspend fun save(library: RateLibrary) = withContext(Dispatchers.IO) {
        file().writeText(json.encodeToString(RateLibrary.serializer(), library))
    }
}
