package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.takeout.TakeoutException
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionRepositoryTest {
    private lateinit var db: ShashaDatabase
    private val source = FakeVideoSource()
    private var syncRequests = 0
    private lateinit var subscriptions: SubscriptionRepository

    private val a = channelId(1)
    private val b = channelId(2)

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        subscriptions = SubscriptionRepository(db, source, clock = { NOW_MS }, onChannelsAdded = { syncRequests++ })
        source.channels[a] = ChannelItem(a, "قناة أ", "https://yt3/a.jpg", subscriberCount = 10)
        source.handles["@alpha"] = a
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun add_byIdLinkOrHandle_looksUpNameAndAvatar() = runBlocking<Unit> {
        val added = subscriptions.add("https://www.youtube.com/@alpha")

        assertThat(added).isEqualTo(AddResult.Added(subscription(a, "قناة أ").copy(avatarUrl = "https://yt3/a.jpg", addedAtMs = NOW_MS)))
        assertThat(syncRequests).isEqualTo(1)
        assertThat(subscriptions.add(a)).isInstanceOf(AddResult.AlreadySubscribed::class.java)
        assertThat(subscriptions.add("https://www.youtube.com/channel/$a")).isInstanceOf(AddResult.AlreadySubscribed::class.java)
        assertThat(syncRequests).isEqualTo(1)
        assertThat(subscriptions.observeIsSubscribed(a).first()).isTrue()
    }

    @Test
    fun add_somethingThatIsNotAChannel() = runBlocking<Unit> {
        assertThat(subscriptions.add("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo(AddResult.NotAChannel)
        assertThat(subscriptions.add("hello")).isEqualTo(AddResult.NotAChannel)
        assertThat(subscriptions.observe().first()).isEmpty()
    }

    @Test
    fun add_anUnknownChannel_throwsAndStoresNothing() {
        val error = assertThrows(SourceException::class.java) { runBlocking { subscriptions.add(b) } }
        assertThat(error.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(runBlocking { subscriptions.observe().first() }).isEmpty()
        assertThat(syncRequests).isEqualTo(0)
    }

    @Test
    fun add_aChannelTheAppAlreadyShows_needsNoNetwork() = runBlocking<Unit> {
        // The fake knows nothing about b: a lookup would throw.
        val result = subscriptions.add(ChannelItem(b, "  Beta  ", avatarUrl = null, subscriberCount = null))
        assertThat((result as AddResult.Added).channel.name).isEqualTo("Beta")
    }

    @Test
    fun importTakeout_countsAddedExistingAndInvalidRows() = runBlocking<Unit> {
        subscriptions.add(ChannelItem(a, "Already here", null, null))
        syncRequests = 0
        val csv = """
            Channel Id,Channel Url,Channel Title
            $a,http://www.youtube.com/channel/$a,Imported name for A
            $b,http://www.youtube.com/channel/$b,"Beta, the channel"
            ${channelId(3)},,
            broken row
        """.trimIndent()

        val result = subscriptions.importTakeout("subscriptions.csv", csv.toByteArray())

        assertThat(result).isEqualTo(ImportResult(added = 2, alreadyPresent = 1, invalid = 1))
        assertThat(syncRequests).isEqualTo(1)
        val stored = subscriptions.observe().first().associateBy { it.channelId }
        assertThat(stored.getValue(a).name).isEqualTo("Already here")
        assertThat(stored.getValue(b).name).isEqualTo("Beta, the channel")
        assertThat(stored.getValue(channelId(3)).name).isEqualTo(channelId(3)) // until the first sync names it
    }

    @Test
    fun importTakeout_fromTheZip_andNothingNewMeansNoSync() = runBlocking<Unit> {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv"))
                it.write("Channel Id,Channel Url,Channel Title\n$a,,A\n".toByteArray())
                it.closeEntry()
            }
        }.toByteArray()

        assertThat(subscriptions.importTakeout("takeout.zip", zip)).isEqualTo(ImportResult(1, 0, 0))
        assertThat(subscriptions.importTakeout("takeout.zip", zip)).isEqualTo(ImportResult(0, 1, 0))
        assertThat(syncRequests).isEqualTo(1)
    }

    @Test
    fun importTakeout_rejectsAnUnreadableFile() {
        val error = assertThrows(TakeoutException::class.java) {
            runBlocking { subscriptions.importTakeout("takeout.zip", "PK\u0003\u0004 broken".toByteArray()) }
        }
        assertThat(error.reason).isEqualTo(TakeoutException.Reason.BROKEN_ZIP)
    }

    @Test
    fun remove_dropsTheChannelsVideosToo() = runBlocking<Unit> {
        subscriptions.add(ChannelItem(a, "A", null, null))
        subscriptions.add(ChannelItem(b, "B", null, null))
        db.feed().upsert(listOf(feedItem("v1", a), feedItem("v2", b)))

        subscriptions.remove(a)

        assertThat(subscriptions.observe().first().map { it.channelId }).containsExactly(b)
        assertThat(db.feed().forChannel(a)).isEmpty()
        assertThat(db.feed().forChannel(b)).hasSize(1)
    }

    @Test
    fun updateDetails_onlyTouchesSubscribedChannels() = runBlocking<Unit> {
        subscriptions.add(ChannelItem(a, "Old", "https://yt3/old.jpg", null))
        subscriptions.updateDetails(ChannelItem(a, "New", null, null))
        subscriptions.updateDetails(ChannelItem(b, "Not subscribed", null, null))

        assertThat(subscriptions.observe().first().single()).isEqualTo(
            subscription(a, "New").copy(avatarUrl = "https://yt3/old.jpg", addedAtMs = NOW_MS),
        )
    }

    private fun feedItem(id: String, channel: String) = FeedItemEntity(
        videoId = id, channelId = channel, channelName = "n", title = "t", publishedAtMs = NOW_MS, thumbnailUrl = null,
        durationSeconds = null, isShort = false, isLive = false, url = "https://www.youtube.com/watch?v=$id", fetchedAtMs = NOW_MS,
    )
}
