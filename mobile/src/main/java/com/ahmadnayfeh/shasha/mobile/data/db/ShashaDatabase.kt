package com.ahmadnayfeh.shasha.mobile.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The phone's data: subscriptions and the feed cache, history, watch later, the viewer's own playlists and the YouTube playlists
 * saved to the Library, resume positions, downloads, SponsorBlock's cache and recent searches. File `shasha-mobile.db`; schemas
 * are exported to `mobile/schemas/`.
 *
 * Every schema change bumps [version] and ships a migration: a phone with an older build installed would otherwise fail
 * Room's identity check when it opens the file.
 *
 * - 1: everything above except the playlists.
 * - 2: `user_playlists` and `user_playlist_items`: new tables only, so Room migrates by itself (`MigrationTest` opens a
 *   version 1 file and checks that its rows are kept).
 * - 3: `saved_playlists` (YouTube playlists saved to the Library): a new table only, migrated by Room the same way (`MigrationTest`
 *   opens version 1 and version 2 files).
 */
@Database(
    entities = [
        SubscriptionEntity::class,
        FeedItemEntity::class,
        WatchProgressEntity::class,
        HistoryEntity::class,
        WatchLaterEntity::class,
        DownloadEntity::class,
        SponsorSegmentsEntity::class,
        SearchHistoryEntity::class,
        UserPlaylistEntity::class,
        UserPlaylistItemEntity::class,
        SavedPlaylistEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class ShashaDatabase : RoomDatabase() {
    abstract fun subscriptions(): SubscriptionDao
    abstract fun feed(): FeedDao
    abstract fun progress(): ProgressDao
    abstract fun history(): HistoryDao
    abstract fun watchLater(): WatchLaterDao
    abstract fun downloads(): DownloadDao
    abstract fun sponsorSegments(): SponsorSegmentDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun userPlaylists(): UserPlaylistDao
    abstract fun savedPlaylists(): SavedPlaylistDao

    companion object {
        const val FILE_NAME = "shasha-mobile.db"

        fun create(context: Context): ShashaDatabase =
            Room.databaseBuilder(context.applicationContext, ShashaDatabase::class.java, FILE_NAME).build()
    }
}
