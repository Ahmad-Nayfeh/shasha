package com.ahmadnayfeh.shasha.feed

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.ahmadnayfeh.shasha.data.FakeVideoSource
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.subscription
import com.ahmadnayfeh.shasha.data.video
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FeedSyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ShashaDatabase
    private lateinit var workManager: WorkManager
    private lateinit var feed: FeedRepository
    private val source = FakeVideoSource()
    private val playing = MutableStateFlow(false)

    private val channels = (1..20).map(::channelId)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        db = inMemoryDatabase()
        feed = FeedRepository(db, source)
        runBlocking { db.subscriptions().insertAll(channels.map { subscription(it) }) }
        val now = System.currentTimeMillis()
        channels.forEach { id -> source.uploads[id] = listOf(video("$id-1", id, now - 60_000), video("$id-2", id, now - 7_200_000)) }
    }

    @After
    fun tearDown() {
        db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null) // later test classes start without WorkManager, as Robolectric does
    }

    private fun worker(): FeedSyncWorker = TestListenableWorkerBuilder<FeedSyncWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    FeedSyncWorker(appContext, workerParameters, feed, FeedSyncScheduler(workManager), playing)
            },
        )
        .build()

    private fun pendingCatchUps(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(FeedSyncScheduler.CATCH_UP_WORK).get().filter { it.state == WorkInfo.State.ENQUEUED }

    private fun ListenableWorker.Result.output() = (this as ListenableWorker.Result.Success).outputData

    @Test
    fun syncsEveryChannel_atMostThreeAtATime() = runBlocking<Unit> {
        source.fetchDelayMs = 80

        val output = worker().doWork().output()

        assertThat(output.getInt(FeedSyncWorker.KEY_SYNCED, -1)).isEqualTo(20)
        assertThat(output.getBoolean(FeedSyncWorker.KEY_STOPPED_FOR_PLAYBACK, true)).isFalse()
        assertThat(source.maxConcurrent.get()).isAtMost(3)
        assertThat(source.maxConcurrent.get()).isAtLeast(2) // it does run in parallel
        assertThat(feed.observeHome().first()).hasSize(40)
        assertThat(pendingCatchUps()).isEmpty()
    }

    @Test
    fun aVideoAlreadyPlaying_fetchesNothing_andSchedulesACatchUpIn15Minutes() = runBlocking<Unit> {
        playing.value = true

        val output = worker().doWork().output()

        assertThat(output.getBoolean(FeedSyncWorker.KEY_STOPPED_FOR_PLAYBACK, false)).isTrue()
        assertThat(source.fetched).isEmpty()
        val catchUp = pendingCatchUps().single()
        assertThat(catchUp.initialDelayMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(catchUp.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun playbackStartingMidRun_letsTheFetchesInFlightFinish_startsNoMore_andSchedulesACatchUp() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        source.onFetch = { gate.await(10, TimeUnit.SECONDS) }

        val run = async(Dispatchers.Default) { worker().doWork() }
        withTimeout(10_000) { while (source.inFlight < 3) delay(5) }
        playing.value = true // the user starts a video while three channels are loading
        gate.countDown()
        val output = run.await().output()

        // Stalest first: the three never-synced channels with the smallest ids were in flight, and only they ran.
        val first = channels.take(3)
        assertThat(source.fetched).containsExactlyElementsIn(first)
        assertThat(output.getBoolean(FeedSyncWorker.KEY_STOPPED_FOR_PLAYBACK, false)).isTrue()
        assertThat(output.getInt(FeedSyncWorker.KEY_SYNCED, -1)).isEqualTo(3)
        assertThat(output.getInt(FeedSyncWorker.KEY_REMAINING, -1)).isEqualTo(17)
        // Progress is recorded: the in-flight channels were saved and marked synced.
        val synced = channels.filter { db.subscriptions().get(it)!!.lastSyncAtMs != null }
        assertThat(synced).containsExactlyElementsIn(first)
        assertThat(pendingCatchUps().single().initialDelayMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))

        // The catch-up, after playback: it skips what was just synced and does the rest.
        playing.value = false
        source.fetched.clear()
        source.onFetch = {}
        val catchUp = worker().doWork().output()
        assertThat(catchUp.getInt(FeedSyncWorker.KEY_SYNCED, -1)).isEqualTo(17)
        assertThat(catchUp.getInt(FeedSyncWorker.KEY_SKIPPED, -1)).isEqualTo(3)
        assertThat(source.fetched).containsNoneIn(first)
    }

    @Test
    fun aFailingChannel_doesNotFailTheRun() = runBlocking<Unit> {
        source.failures[channels[4]] = SourceException(SourceErrorKind.BOT_CHECK, "SignInConfirmNotBotException", null, null)

        val result = worker().doWork()

        val output = result.output()
        assertThat(output.getInt(FeedSyncWorker.KEY_SYNCED, -1)).isEqualTo(19)
        assertThat(output.getInt(FeedSyncWorker.KEY_FAILED, -1)).isEqualTo(1)
        assertThat(db.subscriptions().get(channels[4])!!.lastSyncError).isEqualTo("BOT_CHECK: SignInConfirmNotBotException")
    }

    @Test
    fun workManagersDefaultFactory_canCreateTheWorker() {
        // DefaultWorkerFactory looks this constructor up by reflection (R8 keeps it via WorkManager's rules).
        assertThat(FeedSyncWorker::class.java.getConstructor(Context::class.java, WorkerParameters::class.java)).isNotNull()
    }
}
