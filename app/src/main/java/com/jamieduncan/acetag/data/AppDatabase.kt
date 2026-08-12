package com.jamieduncan.acetag.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun sourceToString(v: SpoolSource): String = v.name

    @TypeConverter fun stringToSource(v: String): SpoolSource = SpoolSource.valueOf(v)

    @TypeConverter fun kindToString(v: SpoolEventKind): String = v.name

    @TypeConverter fun stringToKind(v: String): SpoolEventKind = SpoolEventKind.valueOf(v)
}

/**
 * Version 1, no migrations. This is a deliberately fresh database in a new file: the old
 * `acetag.db` was built around a tag-counting model that never matched the physical world, so
 * there is nothing in it worth carrying forward. It is left on disk, unopened and unused.
 */
@Database(
    entities = [SpoolEntity::class, SpoolEventEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spoolDao(): SpoolDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "acetag2.db",
                ).build().also { instance = it }
            }
    }
}
