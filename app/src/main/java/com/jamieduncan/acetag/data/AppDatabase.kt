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
    version = 2,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "acetag2.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
