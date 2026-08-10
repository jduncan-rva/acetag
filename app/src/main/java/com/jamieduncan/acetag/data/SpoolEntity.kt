package com.jamieduncan.acetag.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spools")
data class SpoolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val tagUidA: String? = null,
    val tagUidB: String? = null,
    /** Hex of the 4-byte group ID written into both tags of this spool (page 0x20). Null for
     *  spools imported from a genuine/third-party tag, or written before this field existed. */
    val groupId: String? = null,
    val createdAt: Long,
    val usedUpAt: Long? = null,
)
