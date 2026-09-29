package com.ahmadnayfeh.shasha.data.db

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.DAY_MS
import com.ahmadnayfeh.shasha.data.NOW_MS
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.subscription
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DaoTest {
    private lateinit var db: ShashaDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun item(id: String, channel: String, publishedAtMs: Long) = FeedItemEntity(
        videoId = id, channelId = channel, channelName = "n", title = "t $id", publishedAtMs = publishedAtMs,
        thumbnailUrl = null, durationSeconds = null, isShort = false, isLive = false,
        url = "https://www.youtube.com/watch?v=$id", fetchedAtMs = NOW_MS,
    )

    @Test
    fun subscriptions_insertKeepsExistingRows_andListsByNameIgnoringCase() = runTest {
        val dao = db.subscriptions()
        val a = channelId(1)
        val b = channelId(2)
        assertThat(dao.insertAll(listOf(subscription(b, "beta"), subscription(a, "Alpha")))).doesNotContain(-1L)

        assertThat(dao.insertAll(listOf(subscription(a, "renamed"), subscription(channelId(3), "Gamma")))).containsExactly(-1L, 3L).inOrder()

        assertThat(dao.observeAll().first().map { it.name }).containsExactly("Alpha", "beta", "Gamma").inOrder()
        assertThat(dao.observeIsSubscribed(a).first()).isTrue()
        assertThat(dao.observeIsSubscribed(channelId(9)).first()).isFalse()
    }

    @Test
    fun subscriptions_stalestFirst_putsNeverSyncedFirst() = runTest {
        val dao = db.subscriptions()
        dao.insertAll(
            listOf(
                subscription(channelId(1), lastSyncAtMs = NOW_MS - 1_000),
                subscription(channelId(2), lastSyncAtMs = null),
                subscription(channelId(3), lastSyncAtMs = NOW_MS - 9_000),
            ),
        )
        assertThat(dao.stalestFirst().map { it.channelId }).containsExactly(channelId(2), channelId(3), channelId(1)).inOrder()
    }

    @Test
    fun subscriptions_lastSuccessfulSync_ignoresFailedChannels() = runTest {
        val dao = db.subscriptions()
        assertThat(dao.observeLastSuccessfulSync().first()).isNull()
        dao.insertAll(listOf(subscription(channelId(1)), subscription(channelId(2))))
        dao.setSyncResult(channelId(1), NOW_MS - 5_000, null)
        dao.setSyncResult(channelId(2), NOW_MS, "NETWORK: SocketTimeoutException")
        assertThat(dao.observeLastSuccessfulSync().first()).isEqualTo(NOW_MS - 5_000)
    }

    @Test
    fun subscriptions_updateDetails_keepsTheKnownAvatarWhenNoneIsGiven() = runTest {
        val dao = db.subscriptions()
        val id = channelId(1)
        dao.insertAll(listOf(subscription(id).copy(avatarUrl = "https://yt3/a.jpg")))
        dao.updateDetails(id, "New name", avatarUrl = null)
        assertThat(dao.get(id)!!.avatarUrl).isEqualTo("https://yt3/a.jpg")
        dao.updateDetails(id, "New name", avatarUrl = "https://yt3/b.jpg")
        assertThat(dao.get(id)).isEqualTo(subscription(id, "New name").copy(avatarUrl = "https://yt3/b.jpg"))
    }

    @Test
    fun home_showsOnlySubscribedChannels_newestFirst_upToTheLimit() = runTest {
        val a = channelId(1)
        val gone = channelId(2)
        db.subscriptions().insertAll(listOf(subscription(a)))
        db.feed().upsert(
            listOf(
                item("old", a, NOW_MS - 3 * DAY_MS),
                item("new", a, NOW_MS - DAY_MS),
                item("mid", a, NOW_MS - 2 * DAY_MS),
                item("orphan", gone, NOW_MS),
            ),
        )
        assertThat(db.feed().observeHome(10).first().map { it.videoId }).containsExactly("new", "mid", "old").inOrder()
        assertThat(db.feed().observeHome(2).first().map { it.videoId }).containsExactly("new", "mid").inOrder()
    }

    @Test
    fun feed_keepNewest_prunesOneChannelOnly() = runTest {
        val a = channelId(1)
        val b = channelId(2)
        db.feed().upsert((1..5).map { item("a$it", a, NOW_MS - it * DAY_MS) } + (1..5).map { item("b$it", b, NOW_MS - it * DAY_MS) })

        assertThat(db.feed().keepNewest(a, 2)).isEqualTo(3)

        assertThat(db.feed().forChannel(a).map { it.videoId }).containsExactly("a1", "a2").inOrder()
        assertThat(db.feed().forChannel(b)).hasSize(5)
    }

    @Test
    fun feed_deleteOlderThan_andUpsertReplacesByVideoId() = runTest {
        val a = channelId(1)
        db.feed().upsert(listOf(item("x", a, NOW_MS - 100 * DAY_MS), item("y", a, NOW_MS)))
        db.feed().upsert(listOf(item("y", a, NOW_MS).copy(title = "updated")))

        assertThat(db.feed().deleteOlderThan(NOW_MS - 90 * DAY_MS)).isEqualTo(1)
        assertThat(db.feed().forChannel(a).map { it.title }).containsExactly("updated")
    }

    @Test
    fun progress_isPerProfile_andObservedByVideoIds() = runTest {
        val dao = db.progress()
        dao.upsert(WatchProgressEntity("adult", "v1", 10_000, 60_000, NOW_MS))
        dao.upsert(WatchProgressEntity("kid", "v1", 50_000, 60_000, NOW_MS))
        dao.upsert(WatchProgressEntity("adult", "v1", 20_000, 60_000, NOW_MS + 1))

        assertThat(dao.get("adult", "v1")!!.positionMs).isEqualTo(20_000)
        assertThat(dao.observe("kid", listOf("v1", "v2")).first().map { it.positionMs }).containsExactly(50_000L)
        dao.delete("adult", "v1")
        assertThat(dao.get("adult", "v1")).isNull()
        assertThat(dao.get("kid", "v1")).isNotNull()
    }

    @Test
    fun history_keepsOneRowPerVideoAtItsLatestTime() = runTest {
        val dao = db.history()
        fun entry(id: String, at: Long, profile: String = "adult") = HistoryEntity(profile, id, "t", null, null, null, null, at)
        dao.upsert(entry("v1", NOW_MS))
        dao.upsert(entry("v2", NOW_MS + 1))
        dao.upsert(entry("v1", NOW_MS + 2))
        dao.upsert(entry("v3", NOW_MS, profile = "kid"))

        assertThat(dao.observe("adult", 10).first().map { it.videoId }).containsExactly("v1", "v2").inOrder()
        dao.clear("adult")
        assertThat(dao.observe("adult", 10).first()).isEmpty()
        assertThat(dao.observe("kid", 10).first()).hasSize(1)
    }

    @Test
    fun watchLater_addingAgainKeepsThePlace() = runTest {
        val dao = db.watchLater()
        fun entry(id: String, at: Long) = WatchLaterEntity("adult", id, "t", null, null, null, null, at)
        dao.insert(entry("v1", NOW_MS))
        dao.insert(entry("v2", NOW_MS + 1))
        dao.insert(entry("v1", NOW_MS + 2))

        assertThat(dao.observe("adult").first().map { it.videoId }).containsExactly("v2", "v1").inOrder()
        assertThat(dao.observeIsSaved("adult", "v1").first()).isTrue()
        assertThat(dao.observeIsSaved("kid", "v1").first()).isFalse()
        dao.delete("adult", "v1")
        assertThat(dao.observeIsSaved("adult", "v1").first()).isFalse()
    }
}
