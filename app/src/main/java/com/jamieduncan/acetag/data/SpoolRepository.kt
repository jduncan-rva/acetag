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

    /**
     * Which spool is currently wearing each recorded sticker. Loaded before the write flow arms,
     * so a tap can be answered without a database round trip while the tag is still in the field —
     * and so the app can *name* the spool it's about to take the stickers from.
     */
    suspend fun tagOwners(): Map<String, SpoolEntity> =
        dao.getAll().flatMap { spool -> spool.tagUids.map { it to spool } }.toMap()

    /** Spools sitting in the inventory with no stickers on them, newest first. */
    suspend fun untagged(): List<SpoolEntity> = dao.getAll().filterNot { it.hasTags }

    /**
     * Frees [uids] from whoever holds them, along with the rest of those spools' stickers.
     *
     * Taking one sticker of a pair takes both on purpose. The two stickers are one label spread
     * over two sides, so a spool left holding half of one is a spool the printer can't read but
     * the app still thinks is tagged. Releasing the pair leaves the truth: that spool has no tags,
     * and the sticker still stuck to its adapter reads as unclaimed next time it's scanned.
     */
    private suspend fun releaseFor(uids: List<String>) {
        val displaced = uids.mapNotNull { dao.findByTagUid(it) }.distinctBy { it.id }
        dao.releaseTags((displaced.flatMap { it.tagUids } + uids).distinct())
    }

    /** Adds a spool to the inventory and records that it was added. */
    suspend fun addSpool(spool: SpoolEntity): Long = db.withTransaction {
        // A new spool may be claiming stickers that were on an older one; see [moveTagsTo].
        releaseFor(spool.tagUids)
        val id = dao.insert(spool)
        dao.insertEvent(spool.toEvent(SpoolEventKind.ADDED, spool.addedAt))
        id
    }

    /** Saves an edited spec. Marks a custom spool's stickers as out of date if the spec moved. */
    suspend fun updateSpool(spool: SpoolEntity) = dao.update(spool)

    /**
     * Puts a freshly written pair of stickers on [spool], taking them off whatever was wearing
     * them. One transaction, release first: the unique indices would reject the claim otherwise,
     * so there is no window in which two spools own one sticker.
     *
     * Covers all three writes that end up here — a first set of tags for a spool added without
     * any, a rewrite onto its own stickers, and taking an adapter's stickers off another spool.
     * They differ only in what the release finds.
     */
    suspend fun moveTagsTo(
        spool: SpoolEntity,
        tagUid: String,
        tagUid2: String,
        groupId: String,
    ) = db.withTransaction {
        releaseFor(listOf(tagUid, tagUid2))
        dao.update(
            spool.copy(tagUid = tagUid, tagUid2 = tagUid2, groupId = groupId, tagsStale = false),
        )
    }

    /**
     * The spool ran out. Records the consumption, then removes it from the inventory — an empty
     * spool is not a spool, it's history.
     *
     * Any stickers it was wearing go with the row, which is right: they're on an adapter or a
     * reused spool that's now free for the next filament. No separate release step to forget.
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
