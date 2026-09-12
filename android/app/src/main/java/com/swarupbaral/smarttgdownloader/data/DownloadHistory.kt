package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per finished download attempt. Persisted with Room so completed
 * downloads survive app restarts; shown on the History tab.
 */
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    /** Telegram message id — stable key for de-duplication. */
    @PrimaryKey val messageId: Long,
    /** Source chat's internal id; lets us re-download from History later. */
    val chatId: Long,
    val fileName: String,
    val category: String,
    val sourceTitle: String,
    val sizeBytes: Long,
    val completedAtEpochMs: Long,
    /** Directory the file was written to, or null when the attempt failed. */
    val localPath: String?,
    /** true = finished, false = failed attempt (kept for the record). */
    val succeeded: Boolean
)

@Dao
interface DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY completedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT * FROM download_history WHERE messageId = :messageId")
    suspend fun byMessageId(messageId: Long): DownloadHistoryEntity?

    @Query("SELECT * FROM download_history WHERE succeeded = 1 ORDER BY completedAtEpochMs DESC")
    suspend fun completedDownloads(): List<DownloadHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DownloadHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DownloadHistoryEntity>)

    @Query("DELETE FROM download_history WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: Long)

    @Query("DELETE FROM download_history")
    suspend fun clearAll()
}

@Database(
    entities = [DownloadHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadHistoryDao(): DownloadHistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smarttg.db"
                ).build().also { instance = it }
            }
    }
}
