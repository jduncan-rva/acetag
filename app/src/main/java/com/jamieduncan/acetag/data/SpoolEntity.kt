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
    val createdAt: Long,
    val usedUpAt: Long? = null,
)
