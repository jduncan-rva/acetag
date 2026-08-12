package com.jamieduncan.acetag.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jamieduncan.acetag.FilamentMaterial
import com.jamieduncan.acetag.SpoolTag
import java.util.UUID

/** Where a spool's tag data came from. Determines which workflow produced the row. */
enum class SpoolSource { ANYCUBIC, CUSTOM }

/**
 * One physical spool of filament = one row. The spool is the countable object: three black PLAs
 * are three rows, and "how much do I have" is a row count.
 *
 * This table holds *current inventory only*. An emptied spool is deleted from here and recorded
 * in [SpoolEventEntity]; there is no "used up" flag to filter on.
 *
 * ## Tags
 * An ANYCUBIC spool is added by scanning its factory tag: we record that one UID and stop caring.
 * The spool may physically carry other tags — irrelevant, we never look for them.
 *
 * A CUSTOM spool is one we wrote ourselves, and the ACE reads whichever side faces it, so it needs
 * a sticker on each side: [tagUid] and [tagUid2], both written in the same session with identical
 * payloads. Both UIDs are recorded at write time, so scanning either one is an *exact key lookup*
 * back to this row.
 *
 * That distinction is the whole ballgame: looking up a UID we recorded is fine, but never *infer*
 * which spool a tag belongs to by matching specs, group ID, or "open slots". That was tried; it
 * silently merged separate spools into one row and undercounted the inventory.
 *
 * ## Tags are movable, so a spool may have none
 * Both UID columns are nullable, and that is a normal state, not a broken one. A spool under 1 kg
 * doesn't reach the ACE's reader and has to sit on an adapter; a refill has no spool of its own.
 * In both cases the stickers live on reusable hardware that other filament will use later, so the
 * pair of stickers moves from spool to spool while the spools themselves stay put in the
 * inventory. A spool with no tags is on the shelf and counted — the printer just can't see it yet.
 *
 * Moving a pair means clearing it off the previous owner in the same transaction that sets it on
 * the new one; see [SpoolRepository.moveTagsTo]. The unique indices make that ordering mandatory
 * rather than optional, which is the point — two rows can never claim one sticker.
 */
@Entity(
    tableName = "spools",
    indices = [
        Index(value = ["tagUid"], unique = true),
        Index(value = ["tagUid2"], unique = true),
        Index(value = ["spoolKey"], unique = true),
    ],
)
data class SpoolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Stable identity that outlives the row. Row ids are recycled by SQLite after a delete, so
     * events reference this instead — otherwise a new spool could inherit a deleted one's history.
     */
    val spoolKey: String = UUID.randomUUID().toString(),

    val source: SpoolSource,

    /**
     * The scanned factory tag (ANYCUBIC) or the first sticker we wrote (CUSTOM).
     * Null when this spool has no tags on it right now — see the class note.
     */
    val tagUid: String? = null,

    /** The second sticker on a CUSTOM spool. Always null for ANYCUBIC, and null when untagged. */
    val tagUid2: String? = null,

    /**
     * Hex of the 4 bytes at [SpoolTag.GROUP_ID_PAGE], written into both stickers of a CUSTOM
     * spool. Part of the tag byte format only — never matched on. Null for ANYCUBIC.
     */
    val groupId: String? = null,

    /** What the tag says — the Anycubic type string, e.g. "PLA" or "PLA Silk". */
    val type: String,

    /**
     * The part of the material the tag can't carry, as a [FilamentMaterial.Finish] name.
     *
     * Wood-filled PETG has no Anycubic SKU, so its tag says plain "PETG" and this column is what
     * remembers the truth. Deliberately *not* part of [SpoolTag.Spec]: the spec is "what's encoded
     * on the sticker", and staleness is judged by comparing specs. Putting the finish in there
     * would nag you to rewrite two stickers over a field they never held.
     *
     * For combinations Anycubic does sell (PLA Silk, PLA Matte, PLA Luminous) this duplicates what
     * [type] already spells out, and [FilamentMaterial.fromTagType] keeps the two in step.
     */
    val finish: String = FilamentMaterial.Finish.NONE.name,

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

    /** CUSTOM only: the spec was edited after the stickers were written, so they disagree. */
    val tagsStale: Boolean = false,

    val addedAt: Long,
) {
    fun toSpec(): SpoolTag.Spec = SpoolTag.Spec(
        type = type,
        manufacturer = manufacturer,
        color = color,
        nozzleMin = nozzleMin,
        nozzleMax = nozzleMax,
        bedMin = bedMin,
        bedMax = bedMax,
        speedMin = speedMin,
        speedMax = speedMax,
        diameterMm = diameterMm,
        lengthM = lengthM,
        weightG = weightG,
    )

    fun withSpec(spec: SpoolTag.Spec): SpoolEntity = copy(
        type = spec.type,
        manufacturer = spec.manufacturer,
        color = spec.color,
        nozzleMin = spec.nozzleMin,
        nozzleMax = spec.nozzleMax,
        bedMin = spec.bedMin,
        bedMax = spec.bedMax,
        speedMin = spec.speedMin,
        speedMax = spec.speedMax,
        diameterMm = spec.diameterMm,
        lengthM = spec.lengthM,
        weightG = spec.weightG,
    )

    /** True if [spec] differs in any field the NFC tag encodes. */
    fun specDiffersFrom(spec: SpoolTag.Spec): Boolean = toSpec() != spec

    val finishEnum: FilamentMaterial.Finish
        get() = FilamentMaterial.Finish.entries.firstOrNull { it.name == finish }
            ?: FilamentMaterial.Finish.NONE

    /** The base material, recovered from the tag's type string. */
    val baseMaterial: String get() = FilamentMaterial.fromTagType(type).first

    /** What to call this filament on screen, e.g. "PETG Carbon Fibre". */
    val materialName: String get() = FilamentMaterial.displayName(baseMaterial, finishEnum)

    /** Wood- and carbon-filled filament eats brass nozzles; worth flagging in the list. */
    val isAbrasive: Boolean get() = finishEnum.abrasive

    /**
     * The stickers are on this spool right now, so the printer can see it. A CUSTOM spool needs
     * both — one sticker only works with the spool one way up, which is why the write is
     * all-or-nothing in the first place.
     */
    val hasTags: Boolean
        get() = when (source) {
            SpoolSource.ANYCUBIC -> tagUid != null
            SpoolSource.CUSTOM -> tagUid != null && tagUid2 != null
        }

    /** The UIDs this spool currently holds, for releasing them to another spool. */
    val tagUids: List<String> get() = listOfNotNull(tagUid, tagUid2)
}

/** Builds an inventory row from a decoded or user-entered spec. */
fun SpoolTag.Spec.toSpool(
    source: SpoolSource,
    /** Null for a spool added without writing anything — it gets tags when it goes in the printer. */
    tagUid: String? = null,
    tagUid2: String? = null,
    groupId: String? = null,
    /** Defaults to whatever the type string implies — right for scanned Anycubic tags, which is
     *  all we know about them; the custom-spool form passes the finish it was given. */
    finish: FilamentMaterial.Finish = FilamentMaterial.fromTagType(type).second,
    addedAt: Long = System.currentTimeMillis(),
) = SpoolEntity(
    source = source,
    tagUid = tagUid,
    tagUid2 = tagUid2,
    groupId = groupId,
    type = type,
    finish = finish.name,
    manufacturer = manufacturer,
    color = color,
    nozzleMin = nozzleMin,
    nozzleMax = nozzleMax,
    bedMin = bedMin,
    bedMax = bedMax,
    speedMin = speedMin,
    speedMax = speedMax,
    diameterMm = diameterMm,
    lengthM = lengthM,
    weightG = weightG,
    addedAt = addedAt,
)
