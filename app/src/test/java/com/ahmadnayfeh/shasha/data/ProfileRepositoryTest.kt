package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.ProfileEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.SurrenderEventEntity
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.ProfileKind
import com.ahmadnayfeh.shasha.policy.Schedule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileRepositoryTest {
    private lateinit var db: ShashaDatabase
    private lateinit var repo: ProfileRepository

    private val kid = Profile.newChild("سارة", id = "kid").copy(
        dailyQuotaMinutes = 45,
        schedule = Schedule.of(8, 0, 20, 30),
        allowlist = Allowlist(listOf(ListedChannel("UCa", "قناة")), listOf(ListedPlaylist("PLa", "قائمة"))),
        blocklist = Blocklist(listOf(ListedChannel("UCb", "محظورة")), listOf("رعب")),
    )

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = ProfileRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun ensureOwner_createsTheAdultOnce_underThePhase2Id() = runBlocking<Unit> {
        val owner = repo.ensureOwner("الكبار")
        assertThat(owner.id).isEqualTo(ADULT_PROFILE_ID)
        assertThat(owner.kind).isEqualTo(ProfileKind.ADULT)
        assertThat(owner.dailyQuotaMinutes).isNull()

        assertThat(repo.ensureOwner("اسم آخر")).isEqualTo(owner)
        assertThat(repo.all()).containsExactly(owner)
    }

    @Test
    fun upsert_newProfilesGoLast_editsKeepTheirPlace_andEveryFieldRoundTrips() = runBlocking<Unit> {
        val owner = repo.ensureOwner("الكبار")
        val second = Profile.newChild("عمر", id = "kid2")
        repo.upsert(kid)
        repo.upsert(second)
        val renamed = kid.copy(name = "سارة الصغيرة", avatar = "rocket")
        repo.upsert(renamed)

        assertThat(repo.all().map { it.id }).containsExactly(owner.id, "kid", "kid2").inOrder()
        assertThat(repo.observeAll().first().map { it.id }).containsExactly(owner.id, "kid", "kid2").inOrder()
        assertThat(repo.get("kid")).isEqualTo(renamed)
        assertThat(repo.observe("kid2").first()).isEqualTo(second)
        assertThat(repo.get("nobody")).isNull()
    }

    @Test
    fun update_readsAndWritesInOneTransaction() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        val updated = repo.update("kid") { it.copy(allowlist = it.allowlist.withChannel(ListedChannel("UCc", "جديدة"))) }

        assertThat(updated!!.allowlist.channels.map { it.channelId }).containsExactly("UCa", "UCc").inOrder()
        assertThat(repo.get("kid")).isEqualTo(updated)
        assertThat(repo.update("nobody") { it }).isNull()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.update("kid") { it.copy(id = "other") } } }
    }

    @Test
    fun theLastAdult_cannotBeDeleted_norTurnedIntoAChild() = runBlocking<Unit> {
        val owner = repo.ensureOwner("الكبار")
        repo.upsert(kid)
        assertThat(repo.delete(owner.id)).isEqualTo(DeleteProfileResult.LastAdult)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.upsert(owner.copy(kind = ProfileKind.CHILD)) } }

        val second = Profile.newAdult("أحمد", id = "ahmad")
        repo.upsert(second)
        assertThat(repo.delete(owner.id)).isEqualTo(DeleteProfileResult.Deleted)
        assertThat(repo.all().map { it.id }).containsExactly("kid", "ahmad").inOrder()
        assertThat(repo.delete("ahmad")).isEqualTo(DeleteProfileResult.LastAdult)
        assertThat(repo.delete("nobody")).isEqualTo(DeleteProfileResult.NotFound)
    }

    @Test
    fun delete_removesEverythingThatBelongsToTheProfile_andNothingElse() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        val kidWatch = WatchRepository(db, FixedProfile("kid"))
        val ownerWatch = WatchRepository(db, FixedProfile(ADULT_PROFILE_ID))
        val usage = UsageRepository(db)
        for ((watch, profileId) in listOf(kidWatch to "kid", ownerWatch to ADULT_PROFILE_ID)) {
            watch.addToHistory(video("v-$profileId", channelId(1), NOW_MS))
            watch.addToWatchLater(video("w-$profileId", channelId(1), NOW_MS))
            watch.saveProgress("v-$profileId", 60_000, 600_000)
            usage.addForeground(profileId, epochDay = 20_000, seconds = 120)
            db.surrenders().insert(SurrenderEventEntity(profileId = profileId, epochMs = NOW_MS, videoId = "v"))
        }

        assertThat(repo.delete("kid")).isEqualTo(DeleteProfileResult.Deleted)

        assertThat(repo.get("kid")).isNull()
        assertThat(kidWatch.observeHistory().first()).isEmpty()
        assertThat(kidWatch.observeWatchLater().first()).isEmpty()
        assertThat(kidWatch.progress("v-kid")).isNull()
        assertThat(usage.foregroundSeconds("kid", 20_000)).isEqualTo(0)
        assertThat(db.surrenders().since("kid", 0)).isEmpty()

        assertThat(ownerWatch.observeHistory().first()).hasSize(1)
        assertThat(ownerWatch.observeWatchLater().first()).hasSize(1)
        assertThat(ownerWatch.progress("v-$ADULT_PROFILE_ID")).isNotNull()
        assertThat(usage.foregroundSeconds(ADULT_PROFILE_ID, 20_000)).isEqualTo(120)
        assertThat(db.surrenders().since(ADULT_PROFILE_ID, 0)).hasSize(1)
    }

    @Test
    fun rowsThatNoLongerDecode_areSkipped_notFatal() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        // A quota out of range (e.g. written by a newer version), broken JSON, and a row whose JSON names another id.
        db.profiles().upsert(ProfileEntity("bad-quota", 5, repoJson(kid.copy(id = "bad-quota")).replace("\"dailyQuotaMinutes\":45", "\"dailyQuotaMinutes\":5000")))
        db.profiles().upsert(ProfileEntity("broken", 6, "{not json"))
        db.profiles().upsert(ProfileEntity("mismatch", 7, repoJson(kid)))

        assertThat(repo.all().map { it.id }).containsExactly(ADULT_PROFILE_ID, "kid").inOrder()
        assertThat(repo.observeAll().first().map { it.id }).containsExactly(ADULT_PROFILE_ID, "kid").inOrder()
        assertThat(repo.get("broken")).isNull()
        assertThat(repo.get("mismatch")).isNull()
    }

    @Test
    fun reorder_followsTheGivenIds_unlistedOnesKeepTheirOrderAfterThem() = runBlocking<Unit> {
        repo.ensureOwner("الكبار")
        repo.upsert(kid)
        repo.upsert(Profile.newChild("عمر", id = "kid2"))
        repo.upsert(Profile.newChild("ليلى", id = "kid3"))

        repo.reorder(listOf("kid3", "kid"))
        assertThat(repo.all().map { it.id }).containsExactly("kid3", "kid", ADULT_PROFILE_ID, "kid2").inOrder()
        repo.upsert(Profile.newChild("زيد", id = "kid4"))
        assertThat(repo.all().last().id).isEqualTo("kid4")
    }

    private fun repoJson(profile: Profile) =
        com.ahmadnayfeh.shasha.policy.PolicyJson.json.encodeToString(Profile.serializer(), profile)
}
