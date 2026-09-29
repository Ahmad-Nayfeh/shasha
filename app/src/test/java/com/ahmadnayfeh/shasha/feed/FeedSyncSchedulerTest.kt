package com.ahmadnayfeh.shasha.feed

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FeedSyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: FeedSyncScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        scheduler = FeedSyncScheduler(workManager)
    }

    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun pending(name: String) = workManager.getWorkInfosForUniqueWork(name).get().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun periodicSync_everyTwoHours_withANetwork_keptAcrossAppStarts() {
        scheduler.ensurePeriodic()
        val first = pending(FeedSyncScheduler.PERIODIC_WORK).single()
        scheduler.ensurePeriodic() // the next app start

        val info = pending(FeedSyncScheduler.PERIODIC_WORK).single()
        assertThat(info.id).isEqualTo(first.id)
        assertThat(info.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(2))
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(info.tags).contains(FeedSyncScheduler.TAG)
    }

    @Test
    fun syncNow_replacesAPendingRequest() {
        scheduler.syncNow()
        val first = pending(FeedSyncScheduler.NOW_WORK).single()
        scheduler.syncNow()

        val info = pending(FeedSyncScheduler.NOW_WORK).single()
        assertThat(info.id).isNotEqualTo(first.id)
        assertThat(info.initialDelayMillis).isEqualTo(0)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun catchUp_waits15Minutes_andALaterInterruptionReplacesIt() {
        scheduler.scheduleCatchUp()
        val first = pending(FeedSyncScheduler.CATCH_UP_WORK).single()
        scheduler.scheduleCatchUp()

        val info = pending(FeedSyncScheduler.CATCH_UP_WORK).single()
        assertThat(info.id).isNotEqualTo(first.id)
        assertThat(info.initialDelayMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }
}
