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

    // Page 0x20 is always zero in every known Anycubic dump (both v1 and v2), same as the
    // 0x27-byte-3 "custom spool marker" this format already repurposes. We use it to write a
    // random 4-byte group ID, shared by both tags of one spool, so a future read can pair them
    // deterministically instead of guessing by matching specs (which breaks for two identical
    // -color spools). Absent/zero on genuine Anycubic tags and tags written before this existed.
    const val GROUP_ID_PAGE = 0x20

    // Byte 3 of the last page is 0x4D on every tag this app writes and 0x00 on every genuine
    // Anycubic dump. It's what lets a scan tell "a spool I've never seen" from "a sticker I wrote
    // that isn't on a spool any more" — the second is offered to an existing spool rather than
    // added as a new one, which is the whole point of tags being movable.
    const val CUSTOM_MARKER_PAGE = 0x27
    private const val CUSTOM_MARKER_INDEX = 3
    private const val CUSTOM_MARKER = 0x4D

    fun randomGroupId(): ByteArray = ByteArray(4).also { kotlin.random.Random.nextBytes(it) }

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

    // Typical published ranges per material; a starting point, always user-editable. Keyed by tag
    // type string, so the form falls back to the base material's numbers for any finish that
    // doesn't have its own entry.
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

        fun writeGroupId(bytes: ByteArray) {
            require(bytes.size == 4) { "group id must be exactly 4 bytes" }
            for (i in 0..3) writeByte(GROUP_ID_PAGE, i, bytes[i].toInt() and 0xFF)
        }

        /** Null if the tag has no group ID written (genuine Anycubic tags, or pre-group-ID writes). */
        fun readGroupIdHex(): String? {
            val bytes = ByteArray(4) { readByte(GROUP_ID_PAGE, it).toByte() }
            if (bytes.all { it == 0.toByte() }) return null
            return bytes.joinToString("") { "%02x".format(it) }
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

    /** True if this app wrote the tag, rather than it coming off a factory Anycubic spool. */
    fun isCustomWritten(t: Pages): Boolean =
        t.readByte(CUSTOM_MARKER_PAGE, CUSTOM_MARKER_INDEX) == CUSTOM_MARKER

    /**
     * How much filament a spool of [weightG] holds, scaled from the material's own full-spool
     * figures. A 250 g spool written with a 1 kg length tells the ACE it has four times the
     * filament it does, so the remaining-length estimate is wrong from the first gram.
     *
     * Zero when the material has no published defaults to scale from — the caller leaves the
     * field alone rather than inventing a number, same as [MATERIAL_DEFAULTS] lookups elsewhere.
     */
    fun lengthForWeight(type: String, weightG: Int): Int {
        val d = MATERIAL_DEFAULTS[type] ?: return 0
        if (weightG <= 0 || d.weightG <= 0) return 0
        return Math.round(d.lengthM.toDouble() * weightG / d.weightG).toInt()
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

    fun buildTag(spec: Spec, groupId: ByteArray? = null): Pages {
        val t = Pages()

        // Static markers
        t.writeByte(0x04, 0, 0x7B)
        t.writeByte(0x04, 2, 0x65) // format version 2
        t.writeByte(CUSTOM_MARKER_PAGE, CUSTOM_MARKER_INDEX, CUSTOM_MARKER)
        groupId?.let { t.writeGroupId(it) }

        t.writeString(0x05, FilamentMaterial.skuForTagType(spec.type))
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
