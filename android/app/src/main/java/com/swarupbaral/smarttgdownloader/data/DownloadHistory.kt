package com.swarupbaral.smarttgdownloader.data

import android.content.Context
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val succeeded: Boolean,
    /**
     * What kind of entry this is — one of [DownloadEntryKind], or null on a row
     * written before archives existed.
     *
     * Nullable rather than `NOT NULL DEFAULT` on purpose: a column added by
     * `ALTER TABLE` has to match what Room expects for the field, and a plain
     * nullable TEXT column is the one shape both sides agree on without relying
     * on how Room normalises a default value. Null and [DownloadEntryKind.FILE]
     * both mean a file, so an old row is never mistaken for an archive.
     */
    @ColumnInfo(name = "entryKind")
    val entryKind: String? = null
)

/**
 * The kinds of entry History can hold.
 *
 * Stored as text rather than an ordinal: the values are read in SQL (`WHERE
 * entryKind = 'ARCHIVE'`) and in the database file itself, and a number there
 * would tell a future reader nothing.
 */
object DownloadEntryKind {
    /** One file fetched from Telegram — also every row written before 2.2.0. */
    const val FILE = "FILE"

    /**
     * One `.zip` the app built out of a selection.
     *
     * It has no Telegram message behind it, so it cannot be fetched again —
     * which is why History offers Open and Delete for one of these and no
     * Re-download.
     */
    const val ARCHIVE = "ARCHIVE"
}

/** True when this row is a `.zip` the app built rather than a file it fetched. */
val DownloadHistoryEntity.isArchive: Boolean
    get() = entryKind == DownloadEntryKind.ARCHIVE

/**
 * The type filter an archive belongs to.
 *
 * An archive of one type is filed under that type, so the History filter still
 * finds it — a zip of audio is a way of having downloaded audio. An archive that
 * mixed types (an "All" selection) belongs to no single type and is filed under
 * All, rather than being mislabelled as whichever type happened to come first.
 */
fun archiveCategory(categories: List<String>): String {
    val distinct = categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return distinct.singleOrNull() ?: AppPreferences.CATEGORY_ALL
}

/**
 * The id for an archive's history row.
 *
 * Telegram message ids are positive, so archives are numbered downwards from
 * zero: [existing] is reused when this archive already has a row (re-downloading
 * the same selection into the same place updates one row instead of piling up
 * duplicates), and a new archive takes one below the lowest archive id in use.
 * The allocation can only ever go further from the message-id space, never into
 * it — a collision would overwrite a real download's history row.
 */
fun archiveHistoryId(existing: Long?, lowestArchiveId: Long?): Long =
    existing ?: ((lowestArchiveId ?: 0L) - 1L)

/**
 * How many files have been downloaded from each channel, keyed by the channel's
 * folder in Downloads.
 *
 * Keyed by *folder* rather than by title because that is the number's subject:
 * it is what "12 files from this channel" means when the user walks over to
 * Downloads and looks. Two titles that sanitise to the same folder are counted
 * together, which is correct — their files really are in one folder.
 *
 * Failed attempts are excluded: they left no file behind, and counting them
 * would make a channel look complete when nothing arrived.
 */
fun downloadedCountsByChannelFolder(
    entries: List<DownloadHistoryEntity>
): Map<String, Int> = channelDownloadSummaries(entries).associate { it.folder to it.files }

/** What History knows about one channel: how much of it has been downloaded. */
data class ChannelDownloadSummary(
    /** The channel's folder in Downloads — the key, and what the button opens. */
    val folder: String,
    /** The channel's own name, taken from its most recent download. */
    val title: String,
    val files: Int,
    /** Sum of the sizes Telegram reported; a floor when [unknownSizes] is not 0. */
    val bytes: Long,
    /**
     * Files Telegram reported no size for.
     *
     * Carried so the total can be read as "2.4 GB or more" rather than as a
     * number that would turn out wrong — the same rule the download panel
     * follows when it confirms a selection.
     */
    val unknownSizes: Int,
    /** When the channel was last downloaded from, which is how these are ordered. */
    val lastDownloadedAt: Long
)

/**
 * One summary per channel with at least one downloaded file, newest first.
 *
 * Same rules as [downloadedCountsByChannelFolder], which is built from this: keyed
 * by folder rather than title, counted from the whole history rather than the
 * filtered list, and successful files only. A channel whose downloads all failed
 * has nothing to summarise and no folder to open, so it is absent rather than
 * shown as an empty card.
 *
 * The input order is not trusted: rows are ordered explicitly by when the channel
 * was last downloaded from, so a change to the query's ordering cannot silently
 * reshuffle the cards.
 *
 * Archives are excluded, and deliberately not replaced by a counter of their
 * own: an archive holds the same files this summary counts, so adding its size
 * to the total would count the same bytes twice over, and adding its file count
 * would double-count the files. A channel known only through an archive
 * therefore has no card — its archive still appears in the list below, with its
 * own Open action and the folder it lives in.
 */
fun channelDownloadSummaries(
    entries: List<DownloadHistoryEntity>
): List<ChannelDownloadSummary> =
    entries.asSequence()
        .filter { it.succeeded && !it.isArchive }
        .groupBy { DownloadsStore.channelFolder(it.sourceTitle) }
        .map { (folder, rows) ->
            val newest = rows.maxBy { it.completedAtEpochMs }
            ChannelDownloadSummary(
                folder = folder,
                // The newest row's title, so a channel that has been renamed since
                // shows the name it has now.
                title = newest.sourceTitle,
                files = rows.size,
                bytes = rows.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                unknownSizes = rows.count { it.sizeBytes <= 0 },
                lastDownloadedAt = newest.completedAtEpochMs
            )
        }
        .sortedByDescending { it.lastDownloadedAt }

@Dao
interface DownloadHistoryDao {

    /** The row for an archive file already on disk, if it has one. */
    @Query(
        "SELECT * FROM download_history WHERE entryKind = 'ARCHIVE' " +
            "AND localPath = :path LIMIT 1"
    )
    suspend fun archiveByLocalPath(path: String): DownloadHistoryEntity?

    /**
     * The smallest id in use by an archive row, or null when there are none.
     *
     * Archive ids are allocated downwards from this, so a new one can never land
     * on an existing row or inside the positive message-id range.
     */
    @Query("SELECT MIN(messageId) FROM download_history WHERE entryKind = 'ARCHIVE'")
    suspend fun lowestArchiveId(): Long?

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

    /**
     * Repoints a history row at the file's new location.
     *
     * Used by the one-time folder migration: this stored path is what the Open
     * action follows, so a moved file would simply look missing without it.
     * Matched on the exact old path, so two files with the same name in
     * different channels can never be confused for one another.
     */
    @Query("UPDATE download_history SET localPath = :newPath WHERE localPath = :oldPath")
    suspend fun updateLocalPath(oldPath: String, newPath: String)
}

@Database(
    entities = [DownloadHistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadHistoryDao(): DownloadHistoryDao

    companion object {
        /**
         * Adds the entry-kind column to an existing database.
         *
         * Additive and lossless on purpose: the caller's download history is
         * user data that would otherwise have to be re-earned, so nothing is
         * dropped and no table is rebuilt. The column is added nullable with no
         * default — the shape Room expects for a field with no
         * `@ColumnInfo(defaultValue)`, so the schema check at open time passes
         * on a device — and existing rows are then labelled as what they are.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_history ADD COLUMN entryKind TEXT")
                db.execSQL(
                    "UPDATE download_history SET entryKind = '" +
                        DownloadEntryKind.FILE + "' WHERE entryKind IS NULL"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smarttg.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
