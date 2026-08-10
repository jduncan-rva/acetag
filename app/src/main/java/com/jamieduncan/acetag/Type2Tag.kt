package com.jamieduncan.acetag

import android.nfc.tech.NfcA

/**
 * Raw ISO14443-3A Type 2 Tag (NFC Forum) commands, sent via NfcA.transceive.
 *
 * We used to rely on android.nfc.tech.MifareUltralight, but Android only exposes that API when
 * it recognizes the tag's ATQA/SAK as "Mifare Ultralight family." A genuine Anycubic spool tag
 * (confirmed on a Pixel 9 Pro XL) shows up with tech list [NfcA] only — no MifareUltralight —
 * so that whole path silently never matches. Type 2 Tag READ/WRITE are part of the open NFC
 * Forum spec that any such chip implements regardless of how Android buckets it, and NfcA is
 * present on every one of these tags, so this works unconditionally.
 */
object Type2Tag {
    private const val CMD_READ = 0x30
    private const val CMD_WRITE = 0xA2

    /** Reads 4 pages (16 bytes) starting at [page]. */
    fun readFourPages(nfcA: NfcA, page: Int): ByteArray {
        val response = nfcA.transceive(byteArrayOf(CMD_READ.toByte(), page.toByte()))
        require(response.size >= 4) { "short read response (${response.size} bytes) for page $page" }
        return response
    }

    /** Writes one 4-byte page. Throws if the tag NACKs. */
    fun writePage(nfcA: NfcA, page: Int, data: ByteArray) {
        require(data.size == 4) { "page data must be exactly 4 bytes" }
        val command = byteArrayOf(CMD_WRITE.toByte(), page.toByte(), data[0], data[1], data[2], data[3])
        val response = nfcA.transceive(command)
        // ACK is a single byte 0x0A; anything else (including a NACK nibble) is a failure.
        if (response.isEmpty() || response[0] != 0x0A.toByte()) {
            throw IllegalStateException("tag NACKed write to page $page")
        }
    }
}
