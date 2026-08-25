package com.example.personalmemoryai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.personalmemoryai.reverseimage.ClassicalVisualFingerprintDao
import com.example.personalmemoryai.reverseimage.ClassicalVisualFingerprintEntity
import com.example.personalmemoryai.reverseimage.HaarFingerprintDao
import com.example.personalmemoryai.reverseimage.HaarFingerprintEntity
import com.example.personalmemoryai.reverseimage.ReverseImageItemDao
import com.example.personalmemoryai.reverseimage.ReverseImageItemEntity

@Database(
    entities = [ImageEntity::class, FaceEntity::class, PersonEntity::class, EmbeddingEntity::class, ObjectEntity::class, HaarFingerprintEntity::class, ReverseImageItemEntity::class, ClassicalVisualFingerprintEntity::class],
    version = 9,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun imageDao(): ImageDao
    abstract fun faceDao(): FaceDao
    abstract fun personDao(): PersonDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun objectDao(): ObjectDao
    abstract fun haarFingerprintDao(): HaarFingerprintDao
    abstract fun reverseImageItemDao(): ReverseImageItemDao
    abstract fun classicalVisualFingerprintDao(): ClassicalVisualFingerprintDao

    companion object {
        private const val DATABASE_NAME = "personal_memory.db"
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(database: SupportSQLiteDatabase) {} }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS persons (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, displayName TEXT, description TEXT, faceCount INTEGER NOT NULL DEFAULT 0, bestQualityScore REAL NOT NULL DEFAULT 0.0, hasRepresentativeEmbedding INTEGER NOT NULL DEFAULT 0, representativeFaceId INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, modelVersion TEXT NOT NULL DEFAULT '1.0', isFavorite INTEGER NOT NULL DEFAULT 0, isArchived INTEGER NOT NULL DEFAULT 0)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_persons_displayName ON persons(displayName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_persons_createdAt ON persons(createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_persons_updatedAt ON persons(updatedAt)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS faces (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, imageId INTEGER NOT NULL, personId INTEGER, boundingLeft REAL NOT NULL, boundingTop REAL NOT NULL, boundingRight REAL NOT NULL, boundingBottom REAL NOT NULL, detectionConfidence REAL NOT NULL, qualityScore REAL NOT NULL, rotationX REAL, rotationY REAL, rotationZ REAL, hasEmbedding INTEGER NOT NULL DEFAULT 0, hasLandmarks INTEGER NOT NULL DEFAULT 0, landmarkCount INTEGER NOT NULL DEFAULT 0, isOccluded INTEGER NOT NULL DEFAULT 0, usableForMatching INTEGER NOT NULL DEFAULT 0, analyzedAt INTEGER NOT NULL, analyzerVersion TEXT NOT NULL DEFAULT '1.0', FOREIGN KEY(imageId) REFERENCES images(id) ON DELETE CASCADE, FOREIGN KEY(personId) REFERENCES persons(id) ON DELETE SET NULL)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_faces_imageId ON faces(imageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_faces_personId ON faces(personId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_faces_qualityScore ON faces(qualityScore)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS embeddings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ownerType TEXT NOT NULL, ownerId INTEGER NOT NULL, vector BLOB NOT NULL, dimension INTEGER NOT NULL, modelName TEXT NOT NULL, modelVersion TEXT NOT NULL, normalized INTEGER NOT NULL DEFAULT 1, createdAt INTEGER NOT NULL)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_ownerType_ownerId ON embeddings(ownerType, ownerId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_modelName_modelVersion ON embeddings(modelName, modelVersion)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_dimension ON embeddings(dimension)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(database: SupportSQLiteDatabase) { database.execSQL("ALTER TABLE images ADD COLUMN detectedObjects TEXT NOT NULL DEFAULT ''") } }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS object_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, imageId INTEGER NOT NULL, classId INTEGER NOT NULL, label TEXT NOT NULL, arabicLabel TEXT NOT NULL, confidence REAL NOT NULL, left REAL NOT NULL, top REAL NOT NULL, right REAL NOT NULL, bottom REAL NOT NULL, detectorName TEXT NOT NULL, detectorVersion TEXT NOT NULL, inferenceTimeMs INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(imageId) REFERENCES images(id) ON DELETE CASCADE)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_object_observations_imageId ON object_observations(imageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_object_observations_classId ON object_observations(classId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_object_observations_label ON object_observations(label)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_object_observations_confidence ON object_observations(confidence)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE images ADD COLUMN ocrQualityScore REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE images ADD COLUMN ocrPassCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE images ADD COLUMN ocrSuccessfulPasses INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE images ADD COLUMN ocrLatinCharacters INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE images ADD COLUMN ocrArabicCharacters INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS image_fingerprints (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, imageId INTEGER NOT NULL, engineVersion TEXT NOT NULL, sourceModifiedAt INTEGER, width INTEGER NOT NULL, height INTEGER NOT NULL, channels INTEGER NOT NULL, signature BLOB NOT NULL, createdAt INTEGER NOT NULL)""")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_image_fingerprints_imageId ON image_fingerprints(imageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_image_fingerprints_engineVersion ON image_fingerprints(engineVersion)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_image_fingerprints_sourceModifiedAt ON image_fingerprints(sourceModifiedAt)")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS image_fingerprints")
                database.execSQL("""CREATE TABLE IF NOT EXISTS reverse_image_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uri TEXT NOT NULL, displayName TEXT NOT NULL, filePath TEXT, fileSize INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, mimeType TEXT, sourceModifiedAt INTEGER, addedAt INTEGER NOT NULL)""")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reverse_image_items_uri ON reverse_image_items(uri)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS image_fingerprints (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, engineVersion TEXT NOT NULL, sourceModifiedAt INTEGER, width INTEGER NOT NULL, height INTEGER NOT NULL, channels INTEGER NOT NULL, signature BLOB NOT NULL, createdAt INTEGER NOT NULL)""")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_image_fingerprints_itemId ON image_fingerprints(itemId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_image_fingerprints_engineVersion ON image_fingerprints(engineVersion)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_image_fingerprints_sourceModifiedAt ON image_fingerprints(sourceModifiedAt)")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS classical_visual_fingerprints (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, engineVersion TEXT NOT NULL, phash INTEGER NOT NULL, dhash INTEGER NOT NULL, colorHistogram BLOB NOT NULL, edgeHistogram BLOB NOT NULL, localKeypoints BLOB, localDescriptors BLOB, localDescriptorRows INTEGER NOT NULL, localDescriptorCols INTEGER NOT NULL, localDescriptorType INTEGER NOT NULL, createdAt INTEGER NOT NULL)""")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_classical_visual_fingerprints_itemId ON classical_visual_fingerprints(itemId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_classical_visual_fingerprints_engineVersion ON classical_visual_fingerprints(engineVersion)")
            }
        }

        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build().also { INSTANCE = it }
        }

        fun closeDatabase() { synchronized(this) { INSTANCE?.close(); INSTANCE = null } }
    }
}
