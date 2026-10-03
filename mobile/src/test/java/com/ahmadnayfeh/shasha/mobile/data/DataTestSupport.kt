package com.ahmadnayfeh.shasha.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ahmadnayfeh.shasha.mobile.data.db.ShashaDatabase

const val HOUR_MS = 60 * 60 * 1000L
const val DAY_MS = 24 * HOUR_MS

/** A fixed "now" for the tests that care about the clock. */
const val NOW_MS = 1_790_596_800_000L

/** A real Room database in memory (Robolectric): the DAOs are exercised as they are in the app. */
fun inMemoryDatabase(): ShashaDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShashaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
