package com.jamieduncan.acetag.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON shape for spool records, versioned so a future web ingest endpoint can evolve the
 * schema without breaking older exports. Field names spell out units (Mm, C, MmS, Ms) so a
 * consumer never has to guess. This is export-only for now — there is no import/ingest side
 * yet, but every field here is exactly what a "POST /spools" endpoint would want.
 */
const val SPOOL_SCHEMA_VERSION = 1

fun SpoolEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("type", type)
    put("manufacturer", manufacturer)
    put("colorHex", color)
    put("nozzleMinC", nozzleMin)
    put("nozzleMaxC", nozzleMax)
    put("bedMinC", bedMin)
    put("bedMaxC", bedMax)
    put("speedMinMmS", speedMin)
    put("speedMaxMmS", speedMax)
    put("diameterMm", diameterMm)
    put("lengthM", lengthM)
    put("weightG", weightG)
    put("tagUidA", tagUidA)
    put("tagUidB", tagUidB)
    put("createdAtMs", createdAt)
    put("usedUpAtMs", usedUpAt)
}

fun List<SpoolEntity>.toExportJson(exportedAtMs: Long): JSONObject {
    val spools = JSONArray()
    forEach { spools.put(it.toJson()) }
    return JSONObject().apply {
        put("schemaVersion", SPOOL_SCHEMA_VERSION)
        put("exportedAtMs", exportedAtMs)
        put("spools", spools)
    }
}
