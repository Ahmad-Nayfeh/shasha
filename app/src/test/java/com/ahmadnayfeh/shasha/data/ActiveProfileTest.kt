package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.Profile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ActiveProfileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var repo: ProfileRepository
    private lateinit var stores: TestDataStores
    private val scopes = mutableListOf<CoroutineScope>()

    private val kid = Profile.newChild("سارة", id = "kid")

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = ProfileRepository(db)
        stores = TestDataStores(tmp.root)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancelAndWait() }
        stores.closeAll()
        db.close()
    }

    private fun active(store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>): ActiveProfile {
        val scope = testScope().also { scopes += it }
        return ActiveProfile.create(repo, store, scope, ownerName = "الكبار")
    }

    private suspend fun ActiveProfile.loaded(id: String): Profile =
        withTimeout(TIMEOUT_MS) { profile.first { it?.id == id }!! }

    @Test
    fun firstStart_isTheOwner_createdOnTheSpot() = runBlocking<Unit> {
        val active = active(stores.open())
        assertThat(active.id.value).isEqualTo(ADULT_PROFILE_ID)
        val owner = active.loaded(ADULT_PROFILE_ID)
        assertThat(owner.isAdult).isTrue()
        assertThat(owner.name).isEqualTo("الكبار")
        assertThat(repo.all()).containsExactly(owner)
    }

    @Test
    fun aRestart_comesBackToTheChild_fromTheFirstRead() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        val first = active(stores.open())
        first.loaded(ADULT_PROFILE_ID)
        assertThat(first.switchTo("kid")).isTrue()
        assertThat(first.id.value).isEqualTo("kid")
        assertThat(first.profile.value).isEqualTo(kid)
        assertThat(first.switchTo("nobody")).isFalse()
        assertThat(first.id.value).isEqualTo("kid")

        // A new process: the id is the child's before anything is loaded, so no list shows the owner's things.
        val second = active(stores.restart())
        assertThat(second.id.value).isEqualTo("kid")
        assertThat(second.loaded("kid")).isEqualTo(kid)
    }

    @Test
    fun editsOfTheCurrentProfile_applyAtOnce() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        val active = active(stores.open())
        active.switchTo("kid")
        repo.update("kid") { it.copy(allowlist = it.allowlist.withChannel(ListedChannel("UCnew", "جديدة"))) }
        val edited = withTimeout(TIMEOUT_MS) { active.profile.first { it?.allowlist?.hasChannel("UCnew") == true } }
        assertThat(edited!!.id).isEqualTo("kid")
    }

    @Test
    fun deletingTheCurrentProfile_fallsBackToTheOwner_andRemembersIt() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        val active = active(stores.open())
        active.switchTo("kid")
        assertThat(repo.delete("kid")).isEqualTo(DeleteProfileResult.Deleted)

        active.loaded(ADULT_PROFILE_ID)
        // The fallback sets the profile, then the id, on the follower's thread: wait for the id itself (reading
        // id.value right after the profile arrived raced with it on a loaded machine).
        withTimeout(TIMEOUT_MS) { active.id.first { it == ADULT_PROFILE_ID } }
        assertThat(active(stores.restart()).id.value).isEqualTo(ADULT_PROFILE_ID)
    }

    @Test
    fun theAdultsHistory_isNotVisibleInTheChildProfile() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        val active = active(stores.open())
        active.loaded(ADULT_PROFILE_ID)
        val watch = WatchRepository(db, active)
        // A title of its own: "Video adult-video" (the helper's default) reads as the phrase the built-in content filter holds back.
        watch.addToHistory(video("adult-video", channelId(1), NOW_MS, title = "Cooking show"))
        watch.addToWatchLater(video("adult-later", channelId(1), NOW_MS))
        watch.saveProgress("adult-video", 120_000, 600_000)

        active.switchTo("kid")
        assertThat(watch.observeHistory().first()).isEmpty()
        assertThat(watch.observeWatchLater().first()).isEmpty()
        assertThat(watch.observeProgress(listOf("adult-video")).first()).isEmpty()
        assertThat(watch.resumePositionMs("adult-video")).isEqualTo(0)
        watch.addToHistory(video("kid-video", channelId(2), NOW_MS))

        active.switchTo(ADULT_PROFILE_ID)
        assertThat(watch.observeHistory().first().map { it.videoId }).containsExactly("adult-video")
        assertThat(watch.resumePositionMs("adult-video")).isEqualTo(120_000)
    }

    private companion object {
        /** Generous: the profile follower runs on another thread, and CI machines can be slow. */
        const val TIMEOUT_MS = 30_000L
    }
}
