package com.jamieduncan.acetag.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jamieduncan.acetag.FilamentMaterial

class Converters {
    @TypeConverter fun sourceToString(v: SpoolSource): String = v.name

    @TypeConverter fun stringToSource(v: String): SpoolSource = SpoolSource.valueOf(v)

    @TypeConverter fun kindToString(v: SpoolEventKind): String = v.name

    @TypeConverter fun stringToKind(v: String): SpoolEventKind = SpoolEventKind.valueOf(v)
}

/**
 * This is a deliberately fresh database in a new file: the old `acetag.db` was built around a
 * tag-counting model that never matched the physical world, so there is nothing in it worth
 * carrying forward. It is left on disk, unopened and unused.
 *
 * Migrations are real, not destructive — the inventory in here is a record of filament someone
 * actually bought, and losing it to a schema change would be losing the point of the app.
 */
@Database(
    entities = [SpoolEntity::class, SpoolEventEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spoolDao(): SpoolDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Adds the material finish, which the tag can't carry (see [FilamentMaterial]).
         *
         * Existing rows are backfilled from their type string: the three finishes Anycubic sells
         * are already spelled out there, so a PLA Silk spool added before this change keeps
         * reading as silk. Everything else was plain by definition — there was no way to record a
         * finish yet — so NONE is correct, not a guess.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("spools", "spool_events")) {
                    db.execSQL(
                        "ALTER TABLE $table ADD COLUMN finish TEXT NOT NULL DEFAULT 'NONE'",
                    )
                    db.execSQL(
                        """
                        UPDATE $table SET finish = CASE
                            WHEN type = 'PLA Matte'    THEN 'MATTE'
                            WHEN type = 'PLA Silk'     THEN 'SILK'
                            WHEN type = 'PLA Luminous' THEN 'GLOW'
                            ELSE 'NONE'
                        END
                        """.trimIndent(),
                    )
                }
            }
        }

        /**
         * Makes `tagUid` nullable, so a spool can exist without stickers on it.
         *
         * Filament under 1 kg rides on an adapter and refills ride on a reused spool, and in both
         * cases one pair of stickers serves whichever filament is currently mounted. So the tags
         * had to stop being a permanent property of a spool and become something a spool holds for
         * now. Existing rows all have tags and keep them; nothing is lost or rewritten.
         *
         * SQLite can't drop a NOT NULL in place, hence the rebuild. The unique indices are
         * recreated exactly as before — SQLite treats NULLs as distinct, so any number of untagged
         * spools coexist while two spools still can't claim one sticker.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            private val COLUMNS =
                "id, spoolKey, source, tagUid, tagUid2, groupId, type, finish, manufacturer, " +
                    "color, nozzleMin, nozzleMax, bedMin, bedMax, speedMin, speedMax, " +
                    "diameterMm, lengthM, weightG, tagsStale, addedAt"

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE spools_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        spoolKey TEXT NOT NULL,
                        source TEXT NOT NULL,
                        tagUid TEXT,
                        tagUid2 TEXT,
                        groupId TEXT,
                        type TEXT NOT NULL,
                        finish TEXT NOT NULL,
                        manufacturer TEXT NOT NULL,
                        color TEXT NOT NULL,
                        nozzleMin INTEGER NOT NULL,
                        nozzleMax INTEGER NOT NULL,
                        bedMin INTEGER NOT NULL,
                        bedMax INTEGER NOT NULL,
                        speedMin INTEGER NOT NULL,
                        speedMax INTEGER NOT NULL,
                        diameterMm REAL NOT NULL,
                        lengthM INTEGER NOT NULL,
                        weightG INTEGER NOT NULL,
                        tagsStale INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO spools_new ($COLUMNS) SELECT $COLUMNS FROM spools")
                db.execSQL("DROP TABLE spools")
                db.execSQL("ALTER TABLE spools_new RENAME TO spools")
                db.execSQL("CREATE UNIQUE INDEX index_spools_tagUid ON spools (tagUid)")
                db.execSQL("CREATE UNIQUE INDEX index_spools_tagUid2 ON spools (tagUid2)")
                db.execSQL("CREATE UNIQUE INDEX index_spools_spoolKey ON spools (spoolKey)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "acetag2.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
