package com.example.personalmemoryai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ImageEntity::class,
        FaceEntity::class,
        PersonEntity::class,
        EmbeddingEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun faceDao(): FaceDao
    abstract fun personDao(): PersonDao
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
        private const val DATABASE_NAME = "personal_memory.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Existing schema is preserved.
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS persons (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT,
                        description TEXT,
                        faceCount INTEGER NOT NULL DEFAULT 0,
                        bestQualityScore REAL NOT NULL DEFAULT 0.0,
                        hasRepresentativeEmbedding INTEGER NOT NULL DEFAULT 0,
                        representativeFaceId INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        modelVersion TEXT NOT NULL DEFAULT '1.0',
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        isArchived INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_persons_displayName ON persons(displayName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_persons_createdAt ON persons(createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_persons_updatedAt ON persons(updatedAt)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS faces (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        imageId INTEGER NOT NULL,
                        personId INTEGER,
                        boundingLeft REAL NOT NULL,
                        boundingTop REAL NOT NULL,
                        boundingRight REAL NOT NULL,
                        boundingBottom REAL NOT NULL,
                        detectionConfidence REAL NOT NULL,
                        qualityScore REAL NOT NULL,
                        rotationX REAL,
                        rotationY REAL,
                        rotationZ REAL,
                        hasEmbedding INTEGER NOT NULL DEFAULT 0,
                        hasLandmarks INTEGER NOT NULL DEFAULT 0,
                        landmarkCount INTEGER NOT NULL DEFAULT 0,
                        isOccluded INTEGER NOT NULL DEFAULT 0,
                        usableForMatching INTEGER NOT NULL DEFAULT 0,
                        analyzedAt INTEGER NOT NULL,
                        analyzerVersion TEXT NOT NULL DEFAULT '1.0',
                        FOREIGN KEY(imageId) REFERENCES images(id) ON DELETE CASCADE,
                        FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_faces_imageId ON faces(imageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_faces_personId ON faces(personId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_faces_qualityScore ON faces(qualityScore)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS embeddings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ownerType TEXT NOT NULL,
                        ownerId INTEGER NOT NULL,
                        vector BLOB NOT NULL,
                        dimension INTEGER NOT NULL,
                        modelName TEXT NOT NULL,
                        modelVersion TEXT NOT NULL,
                        normalized INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_ownerType_ownerId ON embeddings(ownerType, ownerId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_modelName_modelVersion ON embeddings(modelName, modelVersion)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_dimension ON embeddings(dimension)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE images ADD COLUMN detectedObjects TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
