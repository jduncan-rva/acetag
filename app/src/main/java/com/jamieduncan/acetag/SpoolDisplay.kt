package com.jamieduncan.acetag

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.jamieduncan.acetag.data.SpoolEntity

/**
 * Turning spec fields into the words shown on screen. Kept in one place so the inventory list,
 * the add-spool confirmation and the detail screen all describe a spool the same way.
 */
object SpoolDisplay {

    /**
     * The tag stores a short manufacturer code, not a name. "AC PLA" means nothing to a person,
     * so known codes get spelled out; anything else is shown as typed.
     */
    private val BRAND_NAMES = mapOf("AC" to "Anycubic")

    fun brand(manufacturer: String): String =
        BRAND_NAMES[manufacturer.uppercase()] ?: manufacturer.ifBlank { "Unbranded" }

    /**
     * Anycubic's own name for a material isn't always the name people use for it. "PLA Luminous"
     * is glow-in-the-dark filament, and nobody calls it luminous. Spelled out here, the same way
     * brand codes are, so the tag bytes stay untouched.
     */
    private val MATERIAL_NAMES = mapOf("PLA Luminous" to "PLA Glow in the Dark")

    fun material(type: String): String = MATERIAL_NAMES[type] ?: type

    /** e.g. "Anycubic PLA" — from a tag spec alone, which can't know about an app-side finish. */
    fun title(spec: SpoolTag.Spec): String =
        "${brand(spec.manufacturer)} ${material(spec.type)}".trim()

    /**
     * e.g. "Polymaker PETG Carbon Fibre". Preferred wherever a whole spool is in hand: the finish
     * lives in the database, not on the tag, so a spec-only title would call this one "PETG".
     */
    fun title(spool: SpoolEntity): String =
        "${brand(spool.manufacturer)} ${material(spool.materialName)}".trim()

    /** Shown wherever an abrasive spool is listed — the one fact worth catching at a glance. */
    const val ABRASIVE_LABEL = "Abrasive · hardened nozzle"

    /** e.g. "1000 g · 1.75 mm" */
    fun summary(spec: SpoolTag.Spec): String = buildString {
        if (spec.weightG > 0) append("${spec.weightG} g")
        if (spec.diameterMm > 0) {
            if (isNotEmpty()) append(" · ")
            append("${spec.diameterMm} mm")
        }
    }

    /** The full spec block, one labelled line each, skipping anything the tag left empty. */
    fun details(spec: SpoolTag.Spec): String = buildList {
        add("Colour: ${spec.color}")
        add("Nozzle: ${spec.nozzleMin}–${spec.nozzleMax} °C")
        if (spec.bedMax > 0) add("Bed: ${spec.bedMin}–${spec.bedMax} °C")
        if (spec.speedMax > 0) add("Print speed: ${spec.speedMin}–${spec.speedMax} mm/s")
        add("Diameter: ${spec.diameterMm} mm")
        if (spec.lengthM > 0) add("Length: ${spec.lengthM} m")
        if (spec.weightG > 0) add("Weight: ${spec.weightG} g")
    }.joinToString("\n")

    /** "3 spools" / "1 spool" — the inventory is counted in spools, so say so. */
    fun spoolCount(n: Int): String = if (n == 1) "1 spool" else "$n spools"
}

/** Paints a swatch view with a "#rrggbb" colour, leaving it alone if the hex is unusable. */
fun View.setSwatchColor(hex: String) {
    val drawable = background as? GradientDrawable ?: return
    try {
        val bg = drawable.mutate() as GradientDrawable
        bg.setColor(Color.parseColor(hex))
        background = bg
    } catch (_: IllegalArgumentException) {
    }
}
