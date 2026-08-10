package com.jamieduncan.acetag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilamentColorMatchTest {

    @Test
    fun `buildUrl includes mapped material`() {
        val url = FilamentColorMatch.buildUrl("#89a84f", "PLA+")
        assertEquals(
            "https://filamentcolors.xyz/api/swatch/bulk_colormatch/?colors=89a84f&materials=PLA",
            url,
        )
    }

    @Test
    fun `buildUrl omits materials param for unmapped type`() {
        val url = FilamentColorMatch.buildUrl("#89A84F", "Some Future Material")
        assertEquals(
            "https://filamentcolors.xyz/api/swatch/bulk_colormatch/?colors=89a84f",
            url,
        )
    }

    @Test
    fun `buildUrl omits materials param for null type`() {
        val url = FilamentColorMatch.buildUrl("89a84f", null)
        assertEquals(
            "https://filamentcolors.xyz/api/swatch/bulk_colormatch/?colors=89a84f",
            url,
        )
    }

    @Test
    fun `parseResponse extracts a full match`() {
        val body = """
            {
              "89a84f": {
                "color_name": "Jade White",
                "hex": "e8ede9",
                "manufacturer": { "name": "Bambu Lab" },
                "filament_type": { "name": "PLA Basic" }
              }
            }
        """.trimIndent()
        val match = FilamentColorMatch.parseResponse(body, "89a84f")
        assertEquals(
            FilamentColorMatch.BrandMatch(
                manufacturer = "Bambu Lab",
                colorName = "Jade White",
                filamentType = "PLA Basic",
                matchedHex = "e8ede9",
            ),
            match,
        )
    }

    @Test
    fun `parseResponse returns null for empty object`() {
        assertNull(FilamentColorMatch.parseResponse("{}", "89a84f"))
    }

    @Test
    fun `parseResponse returns null when manufacturer is missing`() {
        val body = """
            {
              "89a84f": {
                "color_name": "Jade White",
                "hex": "e8ede9",
                "filament_type": { "name": "PLA Basic" }
              }
            }
        """.trimIndent()
        assertNull(FilamentColorMatch.parseResponse(body, "89a84f"))
    }

    @Test
    fun `parseResponse returns null for malformed json`() {
        assertNull(FilamentColorMatch.parseResponse("not json", "89a84f"))
    }
}
