package io.github.satyanvm.reader.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reading_positions")
data class PositionEntity(
    @PrimaryKey val documentId: String,
    val pageIndex: Int,
    val pageOffset: Float,
    val horizontalOffset: Float,
    val zoom: Float,
    val pageCount: Int,
    val hlc: Long,
    val deviceId: String,
    val schemaVersion: Int,
    /** Local change not yet acknowledged by the server. */
    val pendingSync: Boolean,
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val localPath: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val lastOpenedAt: Long,
    /** Last mode used for this document on this device: "PAGES" or "TEXT". New books open as flowing text. */
    @ColumnInfo(defaultValue = "PAGES") val readingMode: String = "TEXT",
    /** "PDF" or "EPUB". */
    @ColumnInfo(defaultValue = "PDF") val format: String = "PDF",
    /** On the reader's "Currently reading" list. */
    @ColumnInfo(defaultValue = "0") val currentlyReading: Boolean = false,
)

/** A book the reader wants to read; just a name, it needn't be in the library. */
@Entity(tableName = "wishlist")
data class WishlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val addedAt: Long,
)

data class LibraryRow(
    val id: String,
    val title: String,
    val pageCount: Int,
    val lastOpenedAt: Long,
    val pageIndex: Int?,
    val pageOffset: Float?,
    val format: String,
    val currentlyReading: Boolean,
)

@Dao
interface PositionDao {
    @Query("SELECT * FROM reading_positions WHERE documentId = :documentId")
    suspend fun get(documentId: String): PositionEntity?

    @Query("SELECT * FROM reading_positions WHERE documentId = :documentId")
    fun observe(documentId: String): Flow<PositionEntity?>

    @Upsert
    suspend fun put(entity: PositionEntity)

    @Query("SELECT * FROM reading_positions WHERE pendingSync = 1")
    suspend fun pending(): List<PositionEntity>

    @Query("SELECT COALESCE(MAX(hlc), 0) FROM reading_positions")
    suspend fun maxHlc(): Long
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentEntity>

    @Query("UPDATE documents SET lastOpenedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE documents SET readingMode = :mode WHERE id = :id")
    suspend fun setReadingMode(id: String, mode: String)

    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE documents SET currentlyReading = :reading WHERE id = :id")
    suspend fun setCurrentlyReading(id: String, reading: Boolean)

    @Query(
        """
        SELECT d.id, d.title, d.pageCount, d.lastOpenedAt, p.pageIndex, p.pageOffset, d.format, d.currentlyReading
        FROM documents d LEFT JOIN reading_positions p ON p.documentId = d.id
        ORDER BY d.lastOpenedAt DESC
        """
    )
    fun library(): Flow<List<LibraryRow>>
}

@Dao
interface WishlistDao {
    @Query("SELECT * FROM wishlist ORDER BY addedAt DESC")
    fun all(): Flow<List<WishlistEntity>>

    @Insert
    suspend fun add(entity: WishlistEntity)

    @Query("DELETE FROM wishlist WHERE id = :id")
    suspend fun remove(id: Long)
}

@Database(
    entities = [PositionEntity::class, DocumentEntity::class, WishlistEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun positions(): PositionDao
    abstract fun documents(): DocumentDao
    abstract fun wishlist(): WishlistDao

    companion object {
        /** Adds the reading lists, and turns every PDF to flowing text, now the default. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN currentlyReading INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS wishlist (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, author TEXT NOT NULL, addedAt INTEGER NOT NULL)"
                )
                db.execSQL("UPDATE documents SET readingMode = 'TEXT'")
            }
        }

        fun open(context: Context, name: String? = "reader.db"): AppDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, name)
            }
            // A committed WAL write survives process death. synchronous=FULL also fsyncs each
            // commit, so it survives sudden power loss. Position writes are rare enough
            // (debounced) that the extra fsync costs nothing noticeable.
            return builder
                .addMigrations(MIGRATION_3_4)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.query("PRAGMA synchronous = FULL").close()
                    }
                })
                .build()
        }
    }
}
