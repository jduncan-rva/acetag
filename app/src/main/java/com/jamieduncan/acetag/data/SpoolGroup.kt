package com.jamieduncan.acetag.data

/**
 * Spools that are the same filament, shown as one line in the inventory.
 *
 * This is a *display* grouping and nothing more. Each spool is still its own row in the database
 * with its own tags and its own history — three black PLAs are three spools, they just don't need
 * three identical lines on screen. Never collapse them in storage.
 */
data class SpoolGroup(
    val manufacturer: String,
    val type: String,
    val color: String,
    /** Oldest first, so "use one up" takes the spool that's been on the shelf longest. */
    val spools: List<SpoolEntity>,
) {
    val count: Int get() = spools.size
    val newest: SpoolEntity get() = spools.last()
    val oldest: SpoolEntity get() = spools.first()

    /** Any spool in the group whose stickers no longer match its details. */
    val hasStaleTags: Boolean get() = spools.any { it.tagsStale }

    fun spec() = newest.toSpec()
}

/**
 * Collapses an inventory list into groups by brand, material and colour, keeping the incoming
 * newest-first order of the groups themselves.
 */
fun List<SpoolEntity>.groupBySpec(): List<SpoolGroup> =
    groupBy { Triple(it.manufacturer, it.type, it.color) }
        .map { (key, spools) ->
            SpoolGroup(
                manufacturer = key.first,
                type = key.second,
                color = key.third,
                spools = spools.sortedBy { it.addedAt },
            )
        }
        .sortedByDescending { it.newest.addedAt }
