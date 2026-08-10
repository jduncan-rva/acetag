package com.jamieduncan.acetag.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SpoolEntity::class], version = 2, exportSchema = false)
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
                    "acetag.db",
                )
                    // Pre-release, no installed base to preserve yet — real migrations can
                    // replace this once the app has users with data worth keeping.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
