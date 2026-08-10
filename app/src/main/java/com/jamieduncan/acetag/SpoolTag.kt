package com.jamieduncan.acetag

/**
 * Encodes filament spool specs into the Anycubic ACE Pro NFC tag format.
 * Format reverse-engineered by https://github.com/Molodos/anycubic-nfc-filament
 * (see format.md). Layout is NTAG213: pages 0x00-0x03 and 0x28-0x2c are
 * chip/management data and must never be written. Pages 0x04-0x27 (36 pages,
 * 4 bytes each) hold the filament spec produced here.
 */
object SpoolTag {
    const val START_PAGE = 0x04
    const val END_PAGE = 0x27 // inclusive
    const val PAGE_COUNT = END_PAGE - START_PAGE + 1

    val SKUS: LinkedHashMap<String, String> = linkedMapOf(
        "PLA" to "AHPLBK-101",
        "PLA+" to "AHPLPBK-102",
        "PLA High Speed" to "AHHSBK-102",
        "PLA Matte" to "HYGBK-101",
        "PLA Silk" to "HSCWH-101",
        "PETG" to "HPEBK-103",
        "ASA" to "HASBK-101",
        "ABS" to "HABBK-102",
        "TPU" to "HTPBK-101",
        "PLA Luminous" to "HFGBL-101",
    )

    data class Defaults(
        val nozzleMin: Int,
        val nozzleMax: Int,
        val bedMin: Int,
        val bedMax: Int,
        val speedMin: Int = 0,
        val speedMax: Int = 0,
        val diameterMm: Double = 1.75,
        val lengthM: Int = 330,
        val weightG: Int = 1000,
    )

    // Typical published ranges per material; a starting point, always user-editable.
    val MATERIAL_DEFAULTS: Map<String, Defaults> = mapOf(
        "PLA" to Defaults(nozzleMin = 190, nozzleMax = 220, bedMin = 45, bedMax = 60),
        "PLA+" to Defaults(nozzleMin = 200, nozzleMax = 225, bedMin = 45, bedMax = 60),
        "PLA High Speed" to Defaults(nozzleMin = 190, nozzleMax = 240, bedMin = 45, bedMax = 60, speedMin = 50, speedMax = 300),
        "PLA Matte" to Defaults(nozzleMin = 190, nozzleMax = 220, bedMin = 45, bedMax = 60),
        "PLA Silk" to Defaults(nozzleMin = 200, nozzleMax = 220, bedMin = 45, bedMax = 60),
        "PETG" to Defaults(nozzleMin = 230, nozzleMax = 250, bedMin = 70, bedMax = 90),
        "ASA" to Defaults(nozzleMin = 240, nozzleMax = 260, bedMin = 90, bedMax = 100),
        "ABS" to Defaults(nozzleMin = 230, nozzleMax = 250, bedMin = 90, bedMax = 100),
        "TPU" to Defaults(nozzleMin = 210, nozzleMax = 230, bedMin = 30, bedMax = 60),
        "PLA Luminous" to Defaults(nozzleMin = 190, nozzleMax = 220, bedMin = 45, bedMax = 60),
    )

    data class Spec(
        val type: String,
        val manufacturer: String,
        val color: String,
        val nozzleMin: Int,
        val nozzleMax: Int,
        val bedMin: Int,
        val bedMax: Int,
        val speedMin: Int,
        val speedMax: Int,
        val diameterMm: Double,
        val lengthM: Int,
        val weightG: Int,
    )

    /** 36 pages of 4 bytes each, indices correspond to pages START_PAGE..END_PAGE. */
    class Pages {
        val pages: Array<ByteArray> = Array(PAGE_COUNT) { ByteArray(4) }

        private fun rel(page: Int): Int {
            val idx = page - START_PAGE
            require(idx in 0 until PAGE_COUNT) { "page 0x${page.toString(16)} outside writable range" }
            return idx
        }

        fun writeByte(page: Int, index: Int, value: Int) {
            pages[rel(page)][index] = (value and 0xFF).toByte()
        }

        fun writeU16(page: Int, index: Int, value: Int) {
            val low = value % 256
            val high = value / 256
            writeByte(page, index, low)
            if (index == 3) {
                writeByte(page + 1, 0, high)
            } else {
                writeByte(page, index + 1, high)
            }
        }

        fun writeString(page: Int, text: String, maxLen: Int = 20) {
            val n = minOf(text.length, maxLen)
            for (i in 0 until n) {
                writeByte(page + i / 4, i % 4, text[i].code)
            }
        }

        fun writeColor(page: Int, hexColor: String) {
            val h = hexColor.removePrefix("#")
            val r = h.substring(0, 2).toInt(16)
            val g = h.substring(2, 4).toInt(16)
            val b = h.substring(4, 6).toInt(16)
            writeByte(page, 0, 0xFF) // alpha/opacity
            writeByte(page, 1, b)
            writeByte(page, 2, g)
            writeByte(page, 3, r)
        }

        fun readByte(page: Int, index: Int): Int {
            return pages[rel(page)][index].toInt() and 0xFF
        }

        fun readU16(page: Int, index: Int): Int {
            val low = readByte(page, index)
            val high = if (index == 3) readByte(page + 1, 0) else readByte(page, index + 1)
            return high * 256 + low
        }

        fun readString(page: Int, maxLen: Int = 20): String {
            val sb = StringBuilder()
            var p = page
            var i = 0
            var b = readByte(p, i)
            while (b > 0) {
                sb.append(b.toChar())
                if (sb.length >= maxLen) break
                i++
                if (i == 4) {
                    i = 0
                    p++
                }
                b = readByte(p, i)
            }
            return sb.toString()
        }

        fun readColor(page: Int): String {
            val a = readByte(page, 0)
            val b = readByte(page, 1)
            val g = readByte(page, 2)
            val r = readByte(page, 3)
            if (a == 0 && r == 0 && g == 0 && b == 0) return ""
            return String.format("#%02x%02x%02x", r, g, b)
        }

        companion object {
            /** Builds Pages from 144 raw bytes read starting at START_PAGE (as returned by MifareUltralight.readPages). */
            fun fromBytes(bytes: ByteArray): Pages {
                require(bytes.size == PAGE_COUNT * 4) { "expected ${PAGE_COUNT * 4} bytes, got ${bytes.size}" }
                val t = Pages()
                for (i in 0 until PAGE_COUNT) {
                    for (j in 0..3) {
                        t.pages[i][j] = bytes[i * 4 + j]
                    }
                }
                return t
            }
        }
    }

    /** Returns null if this doesn't look like an Anycubic-format tag (missing the format marker). */
    fun decode(t: Pages): Spec? {
        if (t.readByte(0x04, 0) != 0x7B) return null
        val type = t.readString(0x0F).ifBlank { t.readString(0x05) }
        return Spec(
            type = type,
            manufacturer = t.readString(0x0A),
            color = t.readColor(0x14),
            speedMin = t.readU16(0x17, 0),
            speedMax = t.readU16(0x17, 2),
            nozzleMin = t.readU16(0x18, 0),
            nozzleMax = t.readU16(0x18, 2),
            bedMin = t.readU16(0x1D, 0),
            bedMax = t.readU16(0x1D, 2),
            diameterMm = t.readU16(0x1E, 0) / 100.0,
            lengthM = t.readU16(0x1E, 2),
            weightG = t.readU16(0x1F, 0),
        )
    }

    fun buildTag(spec: Spec): Pages {
        val t = Pages()

        // Static markers
        t.writeByte(0x04, 0, 0x7B)
        t.writeByte(0x04, 2, 0x65) // format version 2
        t.writeByte(0x27, 3, 0x4D) // custom spool marker

        t.writeString(0x05, SKUS[spec.type] ?: "AHPLBK-101")
        t.writeString(0x0A, spec.manufacturer.ifBlank { "AC" })
        t.writeString(0x0F, spec.type)

        t.writeColor(0x14, spec.color)

        t.writeU16(0x17, 0, spec.speedMin)
        t.writeU16(0x17, 2, spec.speedMax)
        t.writeU16(0x18, 0, spec.nozzleMin)
        t.writeU16(0x18, 2, spec.nozzleMax)

        t.writeU16(0x1D, 0, spec.bedMin)
        t.writeU16(0x1D, 2, spec.bedMax)

        t.writeU16(0x1E, 0, Math.round(spec.diameterMm * 100).toInt())
        t.writeU16(0x1E, 2, spec.lengthM)
        t.writeU16(0x1F, 0, spec.weightG)

        return t
    }
}
