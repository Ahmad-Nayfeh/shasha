package com.ahmadnayfeh.shasha.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's one database (SPEC: everything stays on the TV). Nothing has been released yet, so the
 * schema is still version 1; from the first release on, every change bumps the version with a
 * migration, and app/schemas/ keeps each exported schema.
 */
@Database(
    entities = [
        SubscriptionEntity::class,
        FeedItemEntity::class,
        WatchProgressEntity::class,
        HistoryEntity::class,
        WatchLaterEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ShashaDatabase : RoomDatabase() {
    abstract fun subscriptions(): SubscriptionDao

    abstract fun feed(): FeedDao

    abstract fun progress(): WatchProgressDao

    abstract fun history(): HistoryDao

    abstract fun watchLater(): WatchLaterDao

    companion object {
        const val FILE_NAME = "shasha.db"

        fun create(context: Context): ShashaDatabase =
            Room.databaseBuilder(context.applicationContext, ShashaDatabase::class.java, FILE_NAME).build()
    }
}
