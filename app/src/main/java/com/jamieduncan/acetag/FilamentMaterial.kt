package com.jamieduncan.acetag

/**
 * What the filament *is*, split the way a person thinks about it: a base material and a finish.
 *
 * The tag can't hold that split. The ACE Pro validates the SKU field and only understands SKUs for
 * filament Anycubic actually sells, so a spool of PETG carbon fibre has no legal way to say so.
 * The rule here is one line long: **if Anycubic sells the combination, the tag says it; otherwise
 * the tag says the base material and AceTag remembers the rest.** That keeps every byte we write
 * to a sticker a byte we've seen on a genuine tag, and keeps the printer loading the spool
 * correctly, at the cost of the ACE screen showing "PLA" for a wood-filled PLA. [needsFallback]
 * is what the form uses to say so out loud before you write anything.
 *
 * SKU structure is `H<material><colour>-<version>` (see the README's tag format section). We write
 * one fixed SKU per material rather than varying the colour half — the colour the ACE displays
 * comes from the colour bytes at page 0x14, not from the SKU.
 */
object FilamentMaterial {

    /**
     * Base materials, each with a SKU seen on a real Anycubic spool. PLA+ and PLA High Speed are
     * bases rather than finishes: they're different formulations with their own SKUs and their own
     * temperatures, not a look applied to plain PLA.
     */
    val BASES: List<String> = listOf(
        "PLA",
        "PLA+",
        "PLA High Speed",
        "PETG",
        "ASA",
        "ABS",
        "TPU",
    )

    private val BASE_SKUS: Map<String, String> = mapOf(
        "PLA" to "AHPLBK-101",
        "PLA+" to "AHPLPBK-102",
        "PLA High Speed" to "AHHSBK-102",
        "PETG" to "HPEBK-103",
        "ASA" to "HASBK-101",
        "ABS" to "HABBK-102",
        "TPU" to "HTPBK-101",
    )

    /**
     * [abrasive] is the one thing worth knowing at a glance when you pick a spool off the shelf:
     * these chew through a brass nozzle. It's derived from the finish rather than stored, so it
     * can never drift out of step with the material.
     */
    enum class Finish(val label: String, val abrasive: Boolean = false) {
        NONE("Plain"),
        MATTE("Matte"),
        SILK("Silk"),
        MARBLE("Marble"),
        GALAXY("Galaxy"),
        METALLIC("Metallic"),
        GLOW("Glow in the Dark"),
        WOOD("Wood", abrasive = true),
        CARBON_FIBRE("Carbon Fibre", abrasive = true),
        ;

        companion object {
            fun fromLabel(label: String): Finish =
                entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: NONE
        }
    }

    val FINISH_LABELS: List<String> = Finish.entries.map { it.label }

    /**
     * The (base, finish) pairs Anycubic sells, and the exact type string and SKU their own tags
     * carry. Only pairs verified against a real spool dump belong here — everything absent falls
     * back to the base material, which is always safe.
     *
     * Deliberately absent despite being real products: PLA Marble, PLA Galaxy and PLA Metal. Their
     * material codes are documented (LS, XK, JS) but no published source gives the full SKU, and
     * an invented SKU is exactly the kind of byte the ACE is known to validate. They fall back to
     * plain PLA until someone dumps a genuine tag.
     */
    private data class Native(val tagType: String, val sku: String)

    private val NATIVE: Map<Pair<String, Finish>, Native> = mapOf(
        ("PLA" to Finish.MATTE) to Native("PLA Matte", "HYGBK-101"),
        ("PLA" to Finish.SILK) to Native("PLA Silk", "HSCWH-101"),
        ("PLA" to Finish.GLOW) to Native("PLA Luminous", "HFGBL-101"),
    )

    /** The material type string written to page 0x0F. */
    fun tagType(base: String, finish: Finish): String =
        NATIVE[base to finish]?.tagType ?: base

    /** The SKU written to page 0x05. Always one Anycubic actually issued. */
    fun sku(base: String, finish: Finish): String =
        NATIVE[base to finish]?.sku ?: BASE_SKUS[base] ?: BASE_SKUS.getValue("PLA")

    /**
     * The SKU for an already-encoded type string. [SpoolTag.buildTag] works from a [SpoolTag.Spec],
     * which carries only the tag's type string — the finish never reaches it, by design — so the
     * pair is recovered from the type first.
     */
    fun skuForTagType(tagType: String): String {
        val (base, finish) = fromTagType(tagType)
        return sku(base, finish)
    }

    /**
     * True when the tag can't carry this combination, so the ACE will show the base material. The
     * form shows this as a plain sentence rather than a warning — it's a fact about the printer,
     * not a mistake the user made.
     */
    fun needsFallback(base: String, finish: Finish): Boolean =
        finish != Finish.NONE && !NATIVE.containsKey(base to finish)

    fun isAbrasive(finish: Finish): Boolean = finish.abrasive

    /** e.g. "PLA Carbon Fibre", or just "PLA" when there's no finish. */
    fun displayName(base: String, finish: Finish): String =
        if (finish == Finish.NONE) base else "$base ${finish.label}"

    /**
     * Recovers (base, finish) from a stored tag type string — for spools written before the finish
     * was tracked separately, and for the confirmation screen after scanning a genuine Anycubic
     * tag, where the type string is all we have.
     */
    fun fromTagType(type: String): Pair<String, Finish> {
        NATIVE.entries.firstOrNull { it.value.tagType.equals(type, ignoreCase = true) }
            ?.let { return it.key }
        val base = BASES.firstOrNull { it.equals(type, ignoreCase = true) }
            ?: BASES.filter { type.startsWith(it, ignoreCase = true) }.maxByOrNull { it.length }
            ?: return "PLA" to Finish.NONE
        return base to Finish.NONE
    }
}
