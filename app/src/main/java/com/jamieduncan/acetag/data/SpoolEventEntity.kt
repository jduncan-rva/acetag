package com.jamieduncan.acetag.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jamieduncan.acetag.FilamentMaterial

enum class SpoolEventKind {
    /** A spool entered the inventory. */
    ADDED,

    /** A spool was used up. The row in `spools` is deleted at the same moment. */
    CONSUMED,
}

/**
 * Append-only history of spools coming in and going out, so a time series can be built later.
 * Rows are written once and never updated.
 *
 * Every event carries a **full snapshot** of the spool rather than a foreign key, because by the
 * time a CONSUMED event matters its spool row is gone. A reference would leave the history blank
 * exactly where it's most interesting.
 *
 * Consumption is all-or-nothing: [weightG] is the spool's full weight, not a measured remainder.
 *
 * A spool deleted as a *mistake* (typo, wrong entry) has its ADDED event deleted too — see
 * [SpoolRepository.deleteMistake]. It never happened, so it must not show up as a purchase.
 */
@Entity(tableName = "spool_events", indices = [Index(value = ["spoolKey"])])
data class SpoolEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** [SpoolEntity.spoolKey] of the spool this happened to; survives the row's deletion. */
    val spoolKey: String,

    val kind: SpoolEventKind,
    val occurredAt: Long,

    val source: SpoolSource,
    val type: String,

    /** [SpoolEntity.finish] at the time of the event — part of the snapshot, so a used-up wood PLA
     *  still reads as wood in the history rather than collapsing to plain PLA. */
    val finish: String = FilamentMaterial.Finish.NONE.name,

    val manufacturer: String,
    val color: String,
    val diameterMm: Double,
    val lengthM: Int,
    val weightG: Int,

    /** When the spool was added. On a CONSUMED event, `occurredAt - addedAt` is its lifespan. */
    val addedAt: Long,
)

fun SpoolEntity.toEvent(kind: SpoolEventKind, occurredAt: Long = System.currentTimeMillis()) =
    SpoolEventEntity(
        spoolKey = spoolKey,
        kind = kind,
        occurredAt = occurredAt,
        source = source,
        type = type,
        finish = finish,
        manufacturer = manufacturer,
        color = color,
        diameterMm = diameterMm,
        lengthM = lengthM,
        weightG = weightG,
        addedAt = addedAt,
    )
