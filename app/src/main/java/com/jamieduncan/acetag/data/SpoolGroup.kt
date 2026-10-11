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
    val finish: String,
    val color: String,
    /** Oldest first, so "use one up" takes the spool that's been on the shelf longest. */
    val spools: List<SpoolEntity>,
) {
    val count: Int get() = spools.size
    val newest: SpoolEntity get() = spools.last()

    /** Any spool in the group whose stickers no longer match its details. */
    val hasStaleTags: Boolean get() = spools.any { it.tagsStale }

    /** How many of these spools the printer could read right now. */
    val taggedCount: Int get() = spools.count { it.hasTags }

    /** True when this filament chews through a brass nozzle — wood- or carbon-filled. */
    val isAbrasive: Boolean get() = newest.isAbrasive

    fun spec() = newest.toSpec()
}

/** Everything that has to match for two spools to be "the same filament" on screen. */
data class GroupKey(
    val manufacturer: String,
    val type: String,
    val finish: String,
    val color: String,
)

/** The exact fields that define membership in an inventory display group. */
fun SpoolEntity.groupKey(): GroupKey = GroupKey(manufacturer, type, finish, color)

/** Returns the members of one display group, oldest first for FIFO consumption. */
fun List<SpoolEntity>.spoolsInGroup(
    manufacturer: String,
    type: String,
    finish: String,
    color: String,
): List<SpoolEntity> =
    filter { it.groupKey() == GroupKey(manufacturer, type, finish, color) }
        .sortedBy { it.addedAt }

/**
 * Collapses an inventory list into groups by brand, material, finish and colour, keeping the
 * incoming newest-first order of the groups themselves.
 *
 * Finish is part of the key because it has to be: a wood-filled PLA and a plain PLA both write
 * "PLA" to their tags, so without it they'd share a line and you'd reach for the wrong spool.
 */
fun List<SpoolEntity>.groupBySpec(): List<SpoolGroup> =
    groupBy { it.groupKey() }
        .map { (key, spools) ->
            SpoolGroup(
                manufacturer = key.manufacturer,
                type = key.type,
                finish = key.finish,
                color = key.color,
                spools = spools.sortedBy { it.addedAt },
            )
        }
        .sortedByDescending { it.newest.addedAt }
