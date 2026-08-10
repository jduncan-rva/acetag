package com.jamieduncan.acetag

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Suggests a real, known filament matching a sampled color, via filamentcolors.xyz's public
 * bulk_colormatch API (LAB-distance match against a spectrophotometer-measured swatch database).
 * Community-run, no formal API contract — every failure mode collapses to null rather than
 * throwing, so a broken/slow/rate-limited endpoint never blocks the write flow.
 */
object FilamentColorMatch {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    data class BrandMatch(
        val manufacturer: String,
        val colorName: String,
        val filamentType: String,
        val matchedHex: String,
    )

    /** AceTag material type -> filamentcolors.xyz parent_type name. Unmapped types omit the filter. */
    val MATERIAL_MAP: Map<String, String> = mapOf(
        "PLA" to "PLA",
        "PLA+" to "PLA",
        "PLA High Speed" to "PLA",
        "PLA Matte" to "PLA",
        "PLA Silk" to "PLA",
        "PLA Luminous" to "PLA",
        "PETG" to "PETG",
        "ASA" to "ASA",
        "ABS" to "ABS",
        "TPU" to "TPU",
    )

    fun buildUrl(hex: String, aceTagMaterial: String?): String {
        val bareHex = hex.removePrefix("#").lowercase()
        val material = aceTagMaterial?.let { MATERIAL_MAP[it] }
        return buildString {
            append("https://filamentcolors.xyz/api/swatch/bulk_colormatch/?colors=")
            append(bareHex)
            if (material != null) {
                append("&materials=")
                append(material)
            }
        }
    }

    /** Parses a bulk_colormatch response body for the given (bare, lowercase) hex key. */
    fun parseResponse(body: String, bareHex: String): BrandMatch? {
        return try {
            val root = JSONObject(body)
            val swatch = root.optJSONObject(bareHex) ?: return null
            val manufacturer = swatch.optJSONObject("manufacturer")?.optString("name").orEmpty()
            val colorName = swatch.optString("color_name")
            val filamentType = swatch.optJSONObject("filament_type")?.optString("name").orEmpty()
            val matchedHex = swatch.optString("hex")
            if (manufacturer.isBlank() || colorName.isBlank() || matchedHex.isBlank()) {
                null
            } else {
                BrandMatch(manufacturer, colorName, filamentType, matchedHex)
            }
        } catch (e: JSONException) {
            null
        }
    }

    /** Suspends on Dispatchers.IO; never throws. Returns null on any failure. */
    suspend fun fetchBestMatch(hex: String, aceTagMaterial: String?): BrandMatch? =
        withContext(Dispatchers.IO) { fetchBestMatchBlocking(hex, aceTagMaterial) }

    private fun fetchBestMatchBlocking(hex: String, aceTagMaterial: String?): BrandMatch? {
        val bareHex = hex.removePrefix("#").lowercase()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(buildUrl(hex, aceTagMaterial)).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseResponse(body, bareHex)
        } catch (e: IOException) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
