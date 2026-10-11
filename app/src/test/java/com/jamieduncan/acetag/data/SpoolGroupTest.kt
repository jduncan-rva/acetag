package com.jamieduncan.acetag.data

import com.jamieduncan.acetag.FilamentMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolGroupTest {
    private fun spool(
        manufacturer: String = "AC",
        type: String = "PLA",
        color: String = "#222222",
        finish: FilamentMaterial.Finish = FilamentMaterial.Finish.NONE,
        addedAt: Long,
    ): SpoolEntity = SpoolEntity(
        source = SpoolSource.CUSTOM,
        type = type,
        finish = finish.name,
        manufacturer = manufacturer,
        color = color,
        nozzleMin = 190,
        nozzleMax = 220,
        bedMin = 45,
        bedMax = 60,
        speedMin = 0,
        speedMax = 0,
        diameterMm = 1.75,
        lengthM = 330,
        weightG = 1000,
        addedAt = addedAt,
    )

    @Test
    fun `groups and membership both include finish and consume oldest matching spool`() {
        val plainOld = spool(manufacturer = "Example", color = "#112233", addedAt = 1)
        val wood = spool(manufacturer = "Example", color = "#112233", finish = FilamentMaterial.Finish.WOOD, addedAt = 2)
        val plainNew = spool(manufacturer = "Example", color = "#112233", addedAt = 3)
        val otherBrand = spool(manufacturer = "Other", color = "#112233", addedAt = 4)
        val otherType = spool(manufacturer = "Example", type = "PETG", color = "#112233", addedAt = 5)
        val otherColor = spool(manufacturer = "Example", color = "#ffffff", addedAt = 6)
        val inventory = listOf(otherColor, otherType, otherBrand, plainNew, wood, plainOld)

        val groups = inventory.groupBySpec()
        assertEquals(5, groups.size)
        val woodGroup = groups.single { it.manufacturer == "Example" && it.type == "PLA" && it.finish == FilamentMaterial.Finish.WOOD.name && it.color == "#112233" }
        val plainGroup = groups.single { it.manufacturer == "Example" && it.type == "PLA" && it.finish == FilamentMaterial.Finish.NONE.name && it.color == "#112233" }
        assertEquals(listOf(plainOld, plainNew), inventory.spoolsInGroup("Example", "PLA", "NONE", "#112233"))
        assertEquals(listOf(wood), inventory.spoolsInGroup("Example", "PLA", "WOOD", "#112233"))
        assertEquals(woodGroup.spools, inventory.spoolsInGroup(woodGroup.manufacturer, woodGroup.type, woodGroup.finish, woodGroup.color))
        assertEquals(plainGroup.spools, inventory.spoolsInGroup(plainGroup.manufacturer, plainGroup.type, plainGroup.finish, plainGroup.color))
        assertEquals(plainOld, plainGroup.spools.first())
        assertTrue(inventory.spoolsInGroup("Example", "PLA", "NONE", "#112233").none { it === wood || it === otherBrand || it === otherType || it === otherColor })
        groups.forEach { group ->
            assertEquals(group.spools, inventory.spoolsInGroup(group.manufacturer, group.type, group.finish, group.color))
        }
    }
}
