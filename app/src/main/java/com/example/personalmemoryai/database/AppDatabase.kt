package com.example.personalmemoryai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ImageEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from the original database (version 1)
         * to the current database (version 2).
         *
         * Version 1 already contains the images table, therefore
         * we deliberately keep it untouched.
         *
         * Additional intelligence tables will be introduced
         * through the following schema expansion while preserving
         * all existing OCR and image data.
         */
        private val MIGRATION_1_2 = object :
            androidx.room.migration.Migration(1, 2) {

            override fun migrate(
                database: androidx.sqlite.db.SupportSQLiteDatabase
            ) {
                /*
                 * Version 1 already contains:
                 *
                 * images
                 *
                 * No destructive operation is performed here.
                 *
                 * Keeping this migration explicit gives us a safe
                 * foundation for future migrations and prevents
                 * Room from deleting the user's existing database.
                 */
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {

                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also {
                        INSTANCE = it
                    }
            }
        }

        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private const val DATABASE_NAME = "personal_memory.db"
    }
}
