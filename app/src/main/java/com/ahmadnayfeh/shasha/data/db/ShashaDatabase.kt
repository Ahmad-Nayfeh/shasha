package com.ahmadnayfeh.shasha.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ahmadnayfeh.shasha.backup.BackupDao

/**
 * The app's one database (SPEC: everything stays on the TV). Every schema change bumps the version
 * with a migration, and app/schemas/ keeps each exported schema: a TV that has an older build installed
 * must open its database, never fail Room's identity check.
 *
 * - 1: subscriptions, feed, progress, history, watch later (phase 2).
 * - 2: profiles, usage records, surrender events (phase 3): new tables only, so Room migrates by itself.
 */
@Database(
    entities = [
        SubscriptionEntity::class,
        FeedItemEntity::class,
        WatchProgressEntity::class,
        HistoryEntity::class,
        WatchLaterEntity::class,
        ProfileEntity::class,
        UsageRecordEntity::class,
        SurrenderEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class ShashaDatabase : RoomDatabase() {
    abstract fun subscriptions(): SubscriptionDao

    abstract fun feed(): FeedDao

    abstract fun progress(): WatchProgressDao

    abstract fun history(): HistoryDao

    abstract fun watchLater(): WatchLaterDao

    abstract fun profiles(): ProfileDao

    abstract fun usage(): UsageDao

    abstract fun surrenders(): SurrenderDao

    /** The manual backup's whole-table reads and writes (queries only: no schema change). */
    abstract fun backup(): BackupDao

    companion object {
        const val FILE_NAME = "shasha.db"

        fun create(context: Context): ShashaDatabase =
            Room.databaseBuilder(context.applicationContext, ShashaDatabase::class.java, FILE_NAME).build()
    }
}
