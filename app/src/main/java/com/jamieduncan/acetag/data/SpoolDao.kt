package com.jamieduncan.acetag.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpoolDao {
    @Insert
    suspend fun insert(spool: SpoolEntity): Long

    @Update
    suspend fun update(spool: SpoolEntity)

    @Query("DELETE FROM spools WHERE id = :id")
    suspend fun delete(id: Long)

    /** The whole inventory. Newest first; there is no other list — empty spools aren't spools. */
    @Query("SELECT * FROM spools ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<SpoolEntity>>

    @Query("SELECT * FROM spools ORDER BY addedAt DESC")
    suspend fun getAll(): List<SpoolEntity>

    @Query("SELECT * FROM spools WHERE id = :id")
    suspend fun getById(id: Long): SpoolEntity?

    /**
     * The spool wearing this tag, by exact UID on either sticker. Null means we have never
     * recorded this tag, so it belongs to a spool that isn't in the inventory yet.
     *
     * This is a key lookup, not a guess — the only kind of tag-to-spool resolution allowed here.
     */
    @Query("SELECT * FROM spools WHERE tagUid = :uid OR tagUid2 = :uid LIMIT 1")
    suspend fun findByTagUid(uid: String): SpoolEntity?

    /** The spools that make up one inventory group, oldest first. */
    @Query(
        """
        SELECT * FROM spools
        WHERE manufacturer = :manufacturer AND type = :type AND color = :color
        ORDER BY addedAt ASC
        """,
    )
    fun observeGroup(manufacturer: String, type: String, color: String): Flow<List<SpoolEntity>>

    /**
     * Every UID currently spoken for, across both sticker columns. Loaded up front by the write
     * flow so it can reject an already-claimed sticker without a database round trip while the
     * tag is still in the phone's field.
     */
    @Query("SELECT tagUid FROM spools UNION SELECT tagUid2 FROM spools WHERE tagUid2 IS NOT NULL")
    suspend fun allTagUids(): List<String>

    /** How many spools of this exact product and colour are on hand. */
    @Query(
        """
        SELECT COUNT(*) FROM spools
        WHERE type = :type AND manufacturer = :manufacturer AND color = :color
        """,
    )
    suspend fun countOfSameColor(type: String, manufacturer: String, color: String): Int

    @Insert
    suspend fun insertEvent(event: SpoolEventEntity)

    @Query("SELECT * FROM spool_events ORDER BY occurredAt ASC")
    suspend fun getAllEvents(): List<SpoolEventEntity>

    @Query("DELETE FROM spool_events WHERE spoolKey = :spoolKey")
    suspend fun deleteEventsFor(spoolKey: String)
}
