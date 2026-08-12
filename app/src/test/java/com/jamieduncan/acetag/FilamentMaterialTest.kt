package com.jamieduncan.acetag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule these tests exist to protect: **only a SKU Anycubic actually issued ever reaches a
 * sticker.** The ACE Pro validates that field, so a plausible-looking invention is worse than a
 * fallback — it produces a spool the printer may refuse, and you don't find out until the tag is
 * already on the spool.
 */
class FilamentMaterialTest {

    private val issuedSkus = setOf(
        "AHPLBK-101", "AHPLPBK-102", "AHHSBK-102", "HPEBK-103",
        "HASBK-101", "HABBK-102", "HTPBK-101",
        "HYGBK-101", "HSCWH-101", "HFGBL-101",
    )

    @Test
    fun `every base and finish pair writes a SKU Anycubic issued`() {
        for (base in FilamentMaterial.BASES) {
            for (finish in FilamentMaterial.Finish.entries) {
                val sku = FilamentMaterial.sku(base, finish)
                assertTrue(
                    "$base + ${finish.label} produced unissued SKU $sku",
                    sku in issuedSkus,
                )
            }
        }
    }

    @Test
    fun `finishes Anycubic sells reach the tag`() {
        assertEquals("PLA Silk", FilamentMaterial.tagType("PLA", FilamentMaterial.Finish.SILK))
        assertEquals("HSCWH-101", FilamentMaterial.sku("PLA", FilamentMaterial.Finish.SILK))
        assertEquals("PLA Matte", FilamentMaterial.tagType("PLA", FilamentMaterial.Finish.MATTE))
        assertEquals("PLA Luminous", FilamentMaterial.tagType("PLA", FilamentMaterial.Finish.GLOW))
        assertFalse(FilamentMaterial.needsFallback("PLA", FilamentMaterial.Finish.SILK))
    }

    @Test
    fun `finishes Anycubic doesn't sell fall back to the base material`() {
        val cases = listOf(
            "PETG" to FilamentMaterial.Finish.CARBON_FIBRE,
            "PLA" to FilamentMaterial.Finish.WOOD,
            "PLA" to FilamentMaterial.Finish.METALLIC,
            "ABS" to FilamentMaterial.Finish.CARBON_FIBRE,
            // Real Anycubic products, but with no published SKU — fallback until one is dumped.
            "PLA" to FilamentMaterial.Finish.MARBLE,
            "PLA" to FilamentMaterial.Finish.GALAXY,
            // A finish Anycubic sells, on a base they don't sell it on.
            "PETG" to FilamentMaterial.Finish.SILK,
        )
        for ((base, finish) in cases) {
            assertEquals(base, FilamentMaterial.tagType(base, finish))
            assertTrue(
                "$base + ${finish.label} should warn the user",
                FilamentMaterial.needsFallback(base, finish),
            )
        }
    }

    @Test
    fun `plain filament never warns`() {
        for (base in FilamentMaterial.BASES) {
            assertFalse(FilamentMaterial.needsFallback(base, FilamentMaterial.Finish.NONE))
            assertEquals(base, FilamentMaterial.tagType(base, FilamentMaterial.Finish.NONE))
        }
    }

    @Test
    fun `only wood and carbon fibre are abrasive`() {
        val abrasive = FilamentMaterial.Finish.entries.filter { it.abrasive }.toSet()
        assertEquals(
            setOf(FilamentMaterial.Finish.WOOD, FilamentMaterial.Finish.CARBON_FIBRE),
            abrasive,
        )
    }

    @Test
    fun `fromTagType recovers what tagType encoded`() {
        for (base in FilamentMaterial.BASES) {
            for (finish in FilamentMaterial.Finish.entries) {
                val encoded = FilamentMaterial.tagType(base, finish)
                val (gotBase, gotFinish) = FilamentMaterial.fromTagType(encoded)
                assertEquals("base lost for $base + ${finish.label}", base, gotBase)
                // A fallback deliberately loses the finish — that's the whole point of the note in
                // the form. What must survive is the base material.
                val expected =
                    if (FilamentMaterial.needsFallback(base, finish)) {
                        FilamentMaterial.Finish.NONE
                    } else {
                        finish
                    }
                assertEquals("finish wrong for $base + ${finish.label}", expected, gotFinish)
            }
        }
    }

    @Test
    fun `PLA+ is not mistaken for PLA`() {
        assertEquals("PLA+", FilamentMaterial.fromTagType("PLA+").first)
        assertEquals("PLA High Speed", FilamentMaterial.fromTagType("PLA High Speed").first)
        assertEquals("AHPLPBK-102", FilamentMaterial.skuForTagType("PLA+"))
        assertEquals("AHHSBK-102", FilamentMaterial.skuForTagType("PLA High Speed"))
    }

    @Test
    fun `unknown type strings degrade to PLA rather than throwing`() {
        assertEquals("PLA", FilamentMaterial.fromTagType("").first)
        assertEquals("PLA", FilamentMaterial.fromTagType("Nonsense").first)
        assertTrue(FilamentMaterial.skuForTagType("Nonsense") in issuedSkus)
    }

    @Test
    fun `display name reads the way a person would say it`() {
        assertEquals("PLA", FilamentMaterial.displayName("PLA", FilamentMaterial.Finish.NONE))
        assertEquals(
            "PETG Carbon Fibre",
            FilamentMaterial.displayName("PETG", FilamentMaterial.Finish.CARBON_FIBRE),
        )
    }

    // ------------------------------------------------------------ tag round-trip

    @Test
    fun `a written tag decodes back to the same spec`() {
        val spec = SpoolTag.Spec(
            type = FilamentMaterial.tagType("PETG", FilamentMaterial.Finish.CARBON_FIBRE),
            manufacturer = "Polymaker",
            color = "#89a84f",
            nozzleMin = 240,
            nozzleMax = 260,
            bedMin = 70,
            bedMax = 90,
            speedMin = 0,
            speedMax = 0,
            diameterMm = 1.75,
            lengthM = 330,
            weightG = 1000,
        )
        val decoded = SpoolTag.decode(SpoolTag.buildTag(spec))
        assertEquals(spec, decoded)
        assertEquals("PETG", decoded!!.type)
    }

    @Test
    fun `the SKU page carries the material's own code, not a stale PLA default`() {
        fun skuOn(type: String): String {
            val spec = SpoolTag.Spec(
                type = type, manufacturer = "AC", color = "#000000",
                nozzleMin = 200, nozzleMax = 220, bedMin = 50, bedMax = 60,
                speedMin = 0, speedMax = 0, diameterMm = 1.75, lengthM = 330, weightG = 1000,
            )
            return SpoolTag.buildTag(spec).readString(0x05)
        }
        assertEquals("HPEBK-103", skuOn("PETG"))
        assertEquals("HSCWH-101", skuOn("PLA Silk"))
        assertEquals("HTPBK-101", skuOn("TPU"))
        assertEquals("AHPLBK-101", skuOn("PLA"))
    }

    @Test
    fun `group id survives a round trip and is absent when not written`() {
        val spec = SpoolTag.Spec(
            type = "PLA", manufacturer = "AC", color = "#ffffff",
            nozzleMin = 190, nozzleMax = 220, bedMin = 45, bedMax = 60,
            speedMin = 0, speedMax = 0, diameterMm = 1.75, lengthM = 330, weightG = 1000,
        )
        val id = byteArrayOf(0x1a, 0x2b, 0x3c, 0x4d)
        assertEquals("1a2b3c4d", SpoolTag.buildTag(spec, id).readGroupIdHex())
        assertEquals(null, SpoolTag.buildTag(spec).readGroupIdHex())
    }
}
