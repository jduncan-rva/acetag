package com.jamieduncan.acetag.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON shape for the inventory, versioned so a future web ingest endpoint can evolve the schema
 * without breaking older exports. Field names spell out units (Mm, C, MmS, G, Ms) so a consumer
 * never has to guess. Export-only for now — there is no import side yet, but every field here is
 * what a "POST /spools" endpoint would want.
 *
 * v5 is a clean break. `spools` is current inventory only; the history lives in `events`, since a
 * spool that has been used up is deleted from the inventory and survives only as a CONSUMED event.
 * Anything reading v4 or earlier (tagUid / tagUids / tagUidA+tagUidB, usedUpAtMs) is reading a
 * different model and should reject rather than reinterpret it.
 *
 * v6 adds `finish` and `materialName` to both spools and events. `type` keeps its old meaning —
 * exactly what the NFC tag says — so a v5 reader still gets a correct, if less specific, answer.
 * The two are separate on purpose: wood-filled PETG has no Anycubic SKU, so its tag says "PETG"
 * and only `finish` records the rest. Consumers that want to show a filament to a human want
 * `materialName`; consumers reasoning about what's physically encoded on the sticker want `type`.
 *
 * v7 makes `tagUid` optional and adds `hasTags`. Tags became movable between spools — filament
 * under 1 kg rides on an adapter and refills ride on a reused spool, so one pair of stickers
 * serves whichever spool is mounted. A spool with no tags is ordinary inventory, not a broken
 * record, and a consumer must not treat a missing `tagUid` as a malformed row. `hasTags` is
 * derived rather than stored, and is there so a reader doesn't have to know that an Anycubic spool
 * needs one UID while a custom one needs two.
 */
const val SPOOL_SCHEMA_VERSION = 7

fun SpoolEntity.toJson(): JSONObject = JSONObject().apply {
    put("spoolKey", spoolKey)
    put("source", source.name)
    put("tagUid", tagUid)
    put("tagUid2", tagUid2)
    put("hasTags", hasTags)
    put("groupId", groupId)
    put("type", type)
    put("finish", finish)
    put("materialName", materialName)
    put("abrasive", isAbrasive)
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
    put("tagsStale", tagsStale)
    put("addedAtMs", addedAt)
}

fun SpoolEventEntity.toJson(): JSONObject = JSONObject().apply {
    put("spoolKey", spoolKey)
    put("kind", kind.name)
    put("occurredAtMs", occurredAt)
    put("source", source.name)
    put("type", type)
    put("finish", finish)
    put("manufacturer", manufacturer)
    put("colorHex", color)
    put("diameterMm", diameterMm)
    put("lengthM", lengthM)
    put("weightG", weightG)
    put("addedAtMs", addedAt)
}

fun buildExportJson(
    spools: List<SpoolEntity>,
    events: List<SpoolEventEntity>,
    exportedAtMs: Long,
): JSONObject = JSONObject().apply {
    put("schemaVersion", SPOOL_SCHEMA_VERSION)
    put("exportedAtMs", exportedAtMs)
    put("spools", JSONArray().also { arr -> spools.forEach { arr.put(it.toJson()) } })
    put("events", JSONArray().also { arr -> events.forEach { arr.put(it.toJson()) } })
}
