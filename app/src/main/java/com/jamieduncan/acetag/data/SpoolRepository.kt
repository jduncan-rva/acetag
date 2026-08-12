package com.jamieduncan.acetag.data

import android.content.Context
import androidx.room.withTransaction
import com.jamieduncan.acetag.SpoolTag

/**
 * The only place inventory changes. Every mutation that has a historical meaning is paired with
 * its event in one transaction, so `spools` and `spool_events` can never disagree.
 */
class SpoolRepository(private val db: AppDatabase) {

    private val dao = db.spoolDao()

    fun observeAll() = dao.observeAll()

    fun observeGroup(manufacturer: String, type: String, color: String) =
        dao.observeGroup(manufacturer, type, color)

    suspend fun getById(id: Long) = dao.getById(id)

    suspend fun getAll() = dao.getAll()

    suspend fun getAllEvents() = dao.getAllEvents()

    suspend fun findByTagUid(uid: String) = dao.findByTagUid(uid)

    suspend fun allTagUids() = dao.allTagUids()

    suspend fun countOfSameColor(spec: SpoolTag.Spec) =
        dao.countOfSameColor(spec.type, spec.manufacturer, spec.color)

    /**
     * Room can enforce uniqueness per column but not across the tagUid/tagUid2 *pair*, so a UID
     * already sitting in the other column would slip through. Check both before inserting.
     * Do not "fix" this with a single index — there isn't one that spans two columns.
     */
    suspend fun tagIsKnown(uid: String): Boolean = dao.findByTagUid(uid) != null

    /** Adds a spool to the inventory and records that it was added. */
    suspend fun addSpool(spool: SpoolEntity): Long = db.withTransaction {
        val id = dao.insert(spool)
        dao.insertEvent(spool.toEvent(SpoolEventKind.ADDED, spool.addedAt))
        id
    }

    /** Saves an edited spec. Marks a custom spool's stickers as out of date if the spec moved. */
    suspend fun updateSpool(spool: SpoolEntity) = dao.update(spool)

    /** The stickers now match the row again. */
    suspend fun markTagsFresh(spool: SpoolEntity, tagUid: String, tagUid2: String, groupId: String) =
        dao.update(spool.copy(tagUid = tagUid, tagUid2 = tagUid2, groupId = groupId, tagsStale = false))

    /**
     * The spool ran out. Records the consumption, then removes it from the inventory — an empty
     * spool is not a spool, it's history.
     */
    suspend fun markEmpty(spool: SpoolEntity) = db.withTransaction {
        dao.insertEvent(spool.toEvent(SpoolEventKind.CONSUMED))
        dao.delete(spool.id)
    }

    /**
     * The spool was entered by mistake. Removes it *and* its history, because it never existed —
     * leaving the ADDED event behind would show a purchase that never happened.
     */
    suspend fun deleteMistake(spool: SpoolEntity) = db.withTransaction {
        dao.deleteEventsFor(spool.spoolKey)
        dao.delete(spool.id)
    }

    companion object {
        fun get(context: Context) = SpoolRepository(AppDatabase.get(context))
    }
}
