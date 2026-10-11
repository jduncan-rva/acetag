package com.jamieduncan.acetag.data

import com.jamieduncan.acetag.FilamentMaterial
import com.jamieduncan.acetag.SpoolTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolGroupTest {
    private fun spool(
        manufacturer: String = "AC",
        color: String = "#222222",
        finish: FilamentMaterial.Finish = FilamentMaterial.Finish.NONE,
        addedAt: Long,
    ): SpoolEntity = SpoolTag.Spec(
        type = "PLA",
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
    ).toSpool(SpoolSource.CUSTOM, finish = finish, addedAt = addedAt)

    @Test
    fun `groups and membership both include finish and consume oldest matching spool`() {
        val plain = spool(addedAt = 1)
        val woodOld = spool(finish = FilamentMaterial.Finish.WOOD, addedAt = 2)
        val woodNew = spool(finish = FilamentMaterial.Finish.WOOD, addedAt = 3)
        val otherBrand = spool(manufacturer = "Other", addedAt = 4)
        val otherColor = spool(color = "#ffffff", addedAt = 5)
        val inventory = listOf(otherColor, otherBrand, woodNew, woodOld, plain)

        val groups = inventory.groupBySpec()
        assertEquals(4, groups.size)
        val wood = groups.single { it.manufacturer == "AC" && it.finish == FilamentMaterial.Finish.WOOD.name && it.color == "#222222" }
        val plainGroup = groups.single { it.manufacturer == "AC" && it.finish == FilamentMaterial.Finish.NONE.name && it.color == "#222222" }
        assertEquals(listOf(woodOld, woodNew), inventory.membersOf("AC", "PLA", "WOOD", "#222222"))
        assertEquals(wood.spools, inventory.membersOf(wood.manufacturer, wood.type, wood.finish, wood.color))
        assertEquals(listOf(plain), inventory.membersOf("AC", "PLA", "NONE", "#222222"))
        assertEquals(plainGroup.spools, inventory.membersOf(plainGroup.manufacturer, plainGroup.type, plainGroup.finish, plainGroup.color))
        assertEquals(woodOld, wood.spools.first())
        assertTrue(inventory.membersOf("AC", "PLA", "WOOD", "#222222").none { it === otherBrand || it === otherColor })
        groups.forEach { group ->
            assertEquals(group.spools, inventory.membersOf(group.manufacturer, group.type, group.finish, group.color))
        }
    }
}
