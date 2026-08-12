package com.jamieduncan.acetag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Length has to follow weight, because the ACE counts remaining filament down from what the tag
 * claims. A 250 g spool written with a full spool's length reports four times what it has, and
 * that's wrong from the first gram — the printer never learns otherwise.
 *
 * This only became reachable when spools smaller than 1 kg entered the inventory: they can't reach
 * the reader on their own, so they ride on a tagged adapter, and the tag has to describe them
 * honestly.
 */
class SpoolTagLengthTest {

    @Test
    fun `a full spool keeps the material's published length`() {
        for ((type, defaults) in SpoolTag.MATERIAL_DEFAULTS) {
            assertEquals(
                "$type at its own default weight should not be rescaled",
                defaults.lengthM,
                SpoolTag.lengthForWeight(type, defaults.weightG),
            )
        }
    }

    @Test
    fun `a quarter spool carries a quarter of the filament`() {
        // 330 m per 1000 g, so 250 g is 82.5 m and rounds to 83.
        assertEquals(83, SpoolTag.lengthForWeight("PLA", 250))
        assertEquals(165, SpoolTag.lengthForWeight("PLA", 500))
        assertEquals(248, SpoolTag.lengthForWeight("PLA", 750))
    }

    @Test
    fun `every base material can be scaled`() {
        // The form offers these, so none of them may fall through to "leave the field alone".
        for (base in FilamentMaterial.BASES) {
            assertEquals(
                "$base should scale to a usable length",
                true,
                SpoolTag.lengthForWeight(base, 500) > 0,
            )
        }
    }

    @Test
    fun `nonsense weights leave the field alone rather than inventing a number`() {
        assertEquals(0, SpoolTag.lengthForWeight("PLA", 0))
        assertEquals(0, SpoolTag.lengthForWeight("PLA", -1))
        assertEquals(0, SpoolTag.lengthForWeight("Unobtainium", 500))
    }
}
