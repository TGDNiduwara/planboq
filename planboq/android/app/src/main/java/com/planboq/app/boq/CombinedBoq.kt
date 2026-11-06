package com.planboq.app.boq

import java.util.UUID

/**
 * Merges the BOQs of several projects (e.g. Ground, First, Second floors)
 * into one schedule by summing quantities per item code.
 * Rates are taken from the first project that has a rate for the code.
 */
object CombinedBoq {

    data class Entry(val projectName: String, val items: List<BoqItem>)

    fun combine(entries: List<Entry>): List<BoqItem> {
        val merged = LinkedHashMap<String, BoqItem>()
        for (entry in entries) {
            entry.items.forEach { item ->
                val existing = merged[item.code]
                if (existing == null) {
                    merged[item.code] = item.copy(
                        id = UUID.randomUUID().toString(),
                        description = item.description,
                        rate = item.rate,
                        detail = "From ${entry.projectName}: ${item.detail}".trim(),
                    )
                } else {
                    merged[item.code] = existing.copy(
                        quantity = existing.quantity + item.quantity,
                        rate = if (existing.rate != 0.0) existing.rate else item.rate,
                        note = if (existing.note.isBlank()) item.note else existing.note,
                        detail = listOf(existing.detail, "From ${entry.projectName}: ${item.detail}")
                            .filter { it.isNotBlank() }
                            .joinToString("\n"),
                    )
                }
            }
        }
        return merged.values.toList()
    }
}
