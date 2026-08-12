package com.jamieduncan.acetag

import android.nfc.Tag
import android.nfc.tech.NfcA

/**
 * Reading and writing a spool tag as a whole. Wraps the page-at-a-time [Type2Tag] primitives so
 * the screens deal in specs and UIDs rather than byte offsets.
 */
object TagIo {

    class TagException(message: String) : Exception(message)

    data class ReadResult(
        val uid: String,
        /** Null when the tag isn't in Anycubic spool format — blank sticker, or something else. */
        val spec: SpoolTag.Spec?,
        val groupIdHex: String?,
    )

    fun uidOf(tag: Tag): String = tag.id.joinToString("") { "%02x".format(it) }

    fun read(tag: Tag): ReadResult {
        val nfcA = NfcA.get(tag) ?: throw TagException("This tag doesn't support NfcA — can't read it.")
        return nfcA.use {
            val raw = ByteArray(SpoolTag.PAGE_COUNT * 4)
            var page = SpoolTag.START_PAGE
            var written = 0
            while (written < raw.size) {
                val chunk = Type2Tag.readFourPages(it, page)
                val toCopy = minOf(chunk.size, raw.size - written)
                System.arraycopy(chunk, 0, raw, written, toCopy)
                written += toCopy
                page += 4
            }
            val pages = SpoolTag.Pages.fromBytes(raw)
            ReadResult(uidOf(tag), SpoolTag.decode(pages), pages.readGroupIdHex())
        }
    }

    /** Writes [spec] across pages 0x04–0x27 and returns the tag's UID. */
    fun write(tag: Tag, spec: SpoolTag.Spec, groupId: ByteArray): String {
        val nfcA = NfcA.get(tag) ?: throw TagException("This tag doesn't support NfcA — can't write to it.")
        return nfcA.use {
            val built = SpoolTag.buildTag(spec, groupId)
            for (i in 0 until SpoolTag.PAGE_COUNT) {
                Type2Tag.writePage(it, SpoolTag.START_PAGE + i, built.pages[i])
            }
            uidOf(tag)
        }
    }

    private inline fun <T> NfcA.use(block: (NfcA) -> T): T {
        connect()
        try {
            return block(this)
        } finally {
            try { close() } catch (_: Exception) {}
        }
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
