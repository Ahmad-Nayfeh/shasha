package com.ahmadnayfeh.shasha.ui.parental

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.BLOCKED_CHANNEL
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.OTHER_CHANNEL
import com.ahmadnayfeh.shasha.policy.OTHER_PLAYLIST
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The parent's list edits: one child's allowlist at a time; the block list for the whole household. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ParentalListsTest {
    private lateinit var db: ShashaDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var lists: ParentalLists

    private val sara = kid("sara", "سارة")
    private val omar = kid("omar", "عمر")

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        profiles = ProfileRepository(db)
        profiles.ensureOwner(owner().name)
        profiles.upsert(sara)
        profiles.upsert(omar)
        lists = ParentalLists(profiles)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theAllowlistIsEachChilds_own() = runBlocking<Unit> {
        lists.allowChannel(sara.id, ListedChannel(OTHER_CHANNEL, "قناة أخرى"))
        lists.allowPlaylist(sara.id, ListedPlaylist(OTHER_PLAYLIST, "قائمة أخرى"))
        assertThat(profiles.get(sara.id)!!.allowlist.channels.map { it.channelId }).containsExactly(ALLOWED_CHANNEL, OTHER_CHANNEL)
        assertThat(profiles.get(sara.id)!!.allowlist.playlists.map { it.playlistId }).containsExactly(ALLOWED_PLAYLIST, OTHER_PLAYLIST)
        assertThat(profiles.get(omar.id)!!.allowlist.channels.map { it.channelId }).containsExactly(ALLOWED_CHANNEL)

        lists.disallow(sara.id, ALLOWED_CHANNEL)
        lists.disallow(sara.id, OTHER_PLAYLIST)
        assertThat(profiles.get(sara.id)!!.allowlist.channels.map { it.channelId }).containsExactly(OTHER_CHANNEL)
        assertThat(profiles.get(sara.id)!!.allowlist.playlists.map { it.playlistId }).containsExactly(ALLOWED_PLAYLIST)
        assertThat(lists.allowChannel("gone", ListedChannel(OTHER_CHANNEL, "x"))).isNull()
    }

    @Test
    fun aBlockIsForEveryone_andTheScreenShowsItOnce() = runBlocking<Unit> {
        lists.blockChannel(ListedChannel(BLOCKED_CHANNEL, "قناة محظورة"))
        profiles.all().forEach { assertThat(it.blocklist.channels.map { c -> c.channelId }).containsExactly(BLOCKED_CHANNEL) }
        assertThat(ParentalLists.householdBlocklist(profiles.all()).channels).hasSize(1)

        lists.unblockChannel(BLOCKED_CHANNEL)
        profiles.all().forEach { assertThat(it.blocklist.channels).isEmpty() }
    }

    @Test
    fun keywords_blankAndRepeatedOnesAreRefused() = runBlocking<Unit> {
        assertThat(lists.addKeyword("  ")).isFalse()
        assertThat(lists.addKeyword("رعب")).isTrue()
        // The same word spelled with a different form or case is the same keyword.
        assertThat(lists.addKeyword(" رعب ")).isFalse()
        assertThat(lists.addKeyword("Horror")).isTrue()
        assertThat(lists.addKeyword("HORROR")).isFalse()
        profiles.all().forEach { assertThat(it.blocklist.titleKeywords).hasSize(2) }

        lists.removeKeyword("رعب")
        assertThat(ParentalLists.householdBlocklist(profiles.all()).titleKeywords).containsExactly("Horror")
    }
}
