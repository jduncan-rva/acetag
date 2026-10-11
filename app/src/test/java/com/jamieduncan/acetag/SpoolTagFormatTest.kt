package com.jamieduncan.acetag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes a sticker gets are the one thing in this app that can't be fixed after the fact: a
 * wrong byte is discovered when the ACE misreads a spool, by which point the sticker is on it.
 *
 * The expected blobs below were produced by `scripts/spool_tag.py` — a separate implementation of
 * the same reverse-engineered layout — not by [SpoolTag] itself, so a drift in the Kotlin encoder
 * fails here instead of being snapshotted. Neither is a genuine Anycubic dump; a change to the
 * layout itself still has to be checked against a real spool on a real printer.
 */
class SpoolTagFormatTest {

    private val pla = SpoolTag.Spec(
        type = "PLA", manufacturer = "AC", color = "#89a84f",
        nozzleMin = 190, nozzleMax = 220, bedMin = 45, bedMax = 60, speedMin = 0, speedMax = 0,
        diameterMm = 1.75, lengthM = 330, weightG = 1000,
    )

    private val petgQuarter = SpoolTag.Spec(
        type = "PETG", manufacturer = "Sunlu", color = "#0a1b2c",
        nozzleMin = 230, nozzleMax = 250, bedMin = 70, bedMax = 90, speedMin = 50, speedMax = 300,
        diameterMm = 1.75, lengthM = 83, weightG = 250,
    )

    private fun hex(t: SpoolTag.Pages) =
        t.pages.joinToString("") { page -> page.joinToString("") { "%02x".format(it) } }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `a PLA spool encodes byte-for-byte as the reference encoder does`() {
        assertEquals(
            "7b0065004148504c424b2d31303100000000000000000000414300000000000000000000" +
                "0000000000000000504c410000000000000000000000000000000000ff4fa88900000000" +
                "0000000000000000be00dc00000000000000000000000000000000002d003c00af004a01" +
                "e8030000000000000000000000000000000000000000000000000000000000000000004d",
            hex(SpoolTag.buildTag(pla)),
        )
    }

    @Test
    fun `speeds, a long manufacturer and a scaled length land where the reference puts them`() {
        assertEquals(
            "7b006500485045424b2d313033000000000000000000000053756e6c7500000000000000" +
                "00000000000000005045544700000000000000000000000000000000ff2c1b0a00000000" +
                "0000000032002c01e600fa000000000000000000000000000000000046005a00af005300" +
                "fa000000000000000000000000000000000000000000000000000000000000000000004d",
            hex(SpoolTag.buildTag(petgQuarter)),
        )
    }

    @Test
    fun `every tag we write carries the custom marker`() {
        assertTrue(SpoolTag.isCustomWritten(SpoolTag.buildTag(pla)))
    }

    @Test
    fun `a tag without the marker reads as genuine, and still decodes`() {
        // Same bytes with page 0x27 byte 3 cleared — the shape of a factory Anycubic tag. Scan
        // routing depends on this: the marker check runs first, and only then the decode.
        val bytes = unhex(hex(SpoolTag.buildTag(pla)))
        bytes[bytes.size - 1] = 0
        val genuine = SpoolTag.Pages.fromBytes(bytes)
        assertFalse(SpoolTag.isCustomWritten(genuine))
        assertEquals(pla, SpoolTag.decode(genuine))
    }

    @Test
    fun `the group id goes on page 0x20 and nowhere else`() {
        val id = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val withId = SpoolTag.buildTag(pla, id)
        val without = SpoolTag.buildTag(pla)
        assertArrayEquals(id, withId.pages[SpoolTag.GROUP_ID_PAGE - SpoolTag.START_PAGE])
        for (i in 0 until SpoolTag.PAGE_COUNT) {
            if (i == SpoolTag.GROUP_ID_PAGE - SpoolTag.START_PAGE) continue
            assertArrayEquals("page 0x${(i + SpoolTag.START_PAGE).toString(16)}", without.pages[i], withId.pages[i])
        }
    }

    @Test
    fun `a tag with no format marker is not a spool`() {
        assertNull(SpoolTag.decode(SpoolTag.Pages()))
    }

    @Test
    fun `the encoder never reaches outside pages 0x04 to 0x27`() {
        // Pages 0x00-0x03 and 0x28 onward are chip and lock data; writing them can brick a sticker.
        assertEquals(0x04, SpoolTag.START_PAGE)
        assertEquals(0x27, SpoolTag.END_PAGE)
        assertEquals(144, SpoolTag.buildTag(pla).pages.sumOf { it.size })
    }
}
