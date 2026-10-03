package com.ahmadnayfeh.shasha.mobile.sponsorblock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.data.DAY_MS
import com.ahmadnayfeh.shasha.mobile.data.HOUR_MS
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.db.SponsorSegmentsEntity
import com.ahmadnayfeh.shasha.mobile.data.inMemoryDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/** The 24-hour Room cache in front of SponsorBlock (SPEC §1: fetched once when the video starts, kept in Room). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SponsorBlockRepositoryTest {
    private val db = inMemoryDatabase()
    private val dao = db.sponsorSegments()
    private var now = NOW_MS
    private val asked = mutableListOf<String>()
    private var answer: (String) -> List<SponsorSegment> = { listOf(SPONSOR) }
    private val repository = SponsorBlockRepository(
        fetcher = { videoId ->
            asked += videoId
            answer(videoId)
        },
        dao = dao,
        now = { now },
    )

    @After
    fun tearDown() = db.close()

    private fun lookup(videoId: String = VIDEO) = runBlocking { repository.lookup(videoId) }

    @Test
    fun theFirstStartAsks_andTheNext24HoursDoNot() {
        assertThat(lookup()).isEqualTo(SponsorBlockRepository.Lookup.Fetched(listOf(SPONSOR)))
        now += 23 * HOUR_MS
        val again = lookup()
        assertThat(again).isEqualTo(SponsorBlockRepository.Lookup.Cached(listOf(SPONSOR), ageMs = 23 * HOUR_MS))
        assertThat(asked).containsExactly(VIDEO)

        // A day later it is asked again (segments get voted, added, removed).
        now += HOUR_MS
        answer = { emptyList() }
        assertThat(lookup()).isEqualTo(SponsorBlockRepository.Lookup.Fetched(emptyList()))
        assertThat(asked).containsExactly(VIDEO, VIDEO)
    }

    @Test
    fun noSegments_isAnAnswerAndIsKeptToo_butOnlyForAnHour() {
        answer = { emptyList() } // HTTP 404
        assertThat(lookup().segments).isEmpty()
        now += 50 * MINUTE_MS
        assertThat(lookup()).isEqualTo(SponsorBlockRepository.Lookup.Cached(emptyList(), ageMs = 50 * MINUTE_MS))
        assertThat(asked).hasSize(1)
        assertThat(runBlocking { dao.get(VIDEO) }?.segments).isEqualTo("[]")

        // A new upload opened in its first minutes, resumed hours later: the sponsor submitted meanwhile is found.
        now += 3 * HOUR_MS
        answer = { listOf(SPONSOR) }
        assertThat(lookup()).isEqualTo(SponsorBlockRepository.Lookup.Fetched(listOf(SPONSOR)))
        assertThat(asked).hasSize(2)
        // Segments, once found, are kept the whole day.
        now += 23 * HOUR_MS
        assertThat(lookup()).isInstanceOf(SponsorBlockRepository.Lookup.Cached::class.java)
        assertThat(asked).hasSize(2)
    }

    @Test
    fun aFailure_isNotKept_andTheNextStartAsksAgain() {
        answer = { throw SocketTimeoutException("timeout") }
        val failed = lookup()
        assertThat(failed).isInstanceOf(SponsorBlockRepository.Lookup.Failed::class.java)
        assertThat(failed.segments).isEmpty()
        assertThat(runBlocking { dao.get(VIDEO) }).isNull()

        answer = { listOf(SPONSOR) }
        assertThat(lookup()).isEqualTo(SponsorBlockRepository.Lookup.Fetched(listOf(SPONSOR)))
        assertThat(asked).hasSize(2)
    }

    @Test
    fun whenSponsorBlockIsDown_anOlderAnswerStillSkips() {
        lookup()
        now += 30 * HOUR_MS
        answer = { throw IOException("HTTP 503") }
        val failed = lookup() as SponsorBlockRepository.Lookup.Failed
        assertThat(failed.segments).containsExactly(SPONSOR)
        // …but not one older than a week.
        now += 7 * DAY_MS
        assertThat(lookup().segments).isEmpty()
    }

    @Test
    fun aClockSetBack_doesNotMakeAnOldAnswerLookFresh() {
        lookup()
        now -= 2 * DAY_MS // the answer now seems to come from the future
        answer = { emptyList() }
        assertThat(lookup()).isEqualTo(SponsorBlockRepository.Lookup.Fetched(emptyList()))
        assertThat(asked).hasSize(2)
    }

    @Test
    fun aDamagedCacheRow_isAskedAgain() {
        runBlocking { dao.put(SponsorSegmentsEntity(VIDEO, now, "{broken")) }
        assertThat(lookup()).isEqualTo(SponsorBlockRepository.Lookup.Fetched(listOf(SPONSOR)))
    }

    @Test
    fun eachVideoHasItsOwnRow_andRowsOlderThanAWeekAreDeleted() {
        lookup("aaaaaaaaaaa")
        now += 8 * DAY_MS
        lookup("bbbbbbbbbbb")
        assertThat(runBlocking { dao.get("aaaaaaaaaaa") }).isNull()
        assertThat(runBlocking { dao.get("bbbbbbbbbbb") }).isNotNull()
        assertThat(runBlocking { dao.count() }).isEqualTo(1)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val VIDEO = "lC3h3Lm3q54"
        val SPONSOR = SponsorSegment("607daf45", SponsorCategory.SPONSOR, SegmentAction.SKIP, 119_513, 162_837, 465_000)
    }
}
