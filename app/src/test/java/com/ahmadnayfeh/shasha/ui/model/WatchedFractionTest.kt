package com.ahmadnayfeh.shasha.ui.model

import com.ahmadnayfeh.shasha.data.db.WatchProgressEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The red bar under a thumbnail: how much of the video was watched, from its saved progress. */
class WatchedFractionTest {

    private fun saved(positionMs: Long, durationMs: Long) = WatchProgressEntity("p", "v", positionMs, durationMs, updatedAtMs = 0)

    @Test
    fun theShareWatched() {
        assertThat(watchedFraction(150_000, 600_000, finished = false)).isEqualTo(0.25f)
        assertThat(watchedFraction(300_000, 600_000, finished = false)).isEqualTo(0.5f)
        assertThat(saved(120_000, 600_000).watchedFraction()).isEqualTo(0.2f)
    }

    @Test
    fun aFinishedVideo_showsTheWholeBar() {
        assertThat(watchedFraction(600_000, 600_000, finished = true)).isEqualTo(1f)
        // Finished as the player counts it (from 95 %, or the last 10 s of a short one): the whole bar, not 96 % of it.
        assertThat(saved(575_000, 600_000).isFinished).isTrue()
        assertThat(saved(575_000, 600_000).watchedFraction()).isEqualTo(1f)
        assertThat(saved(560_000, 600_000).watchedFraction()).isWithin(0.0001f).of(560f / 600f)
        // Marked watched at the end (position = length).
        assertThat(saved(600_000, 600_000).watchedFraction()).isEqualTo(1f)
    }

    @Test
    fun anySavedProgressShows_aTinyOneAsASliver() {
        assertThat(watchedFraction(1_000, 3_600_000, finished = false)).isEqualTo(MIN_SHOWN_FRACTION)
        assertThat(saved(5_000, 600_000).watchedFraction()).isEqualTo(MIN_SHOWN_FRACTION)
        assertThat(watchedFraction(13_000, 600_000, finished = false)).isWithin(0.0001f).of(13f / 600f)
    }

    @Test
    fun nothingToShow_withoutALength_orAtTheVeryStart() {
        assertThat(watchedFraction(10_000, 0, finished = false)).isNull()
        assertThat(watchedFraction(10_000, -1, finished = true)).isNull()
        assertThat(watchedFraction(0, 600_000, finished = false)).isNull()
        assertThat(saved(0, 600_000).watchedFraction()).isNull()
    }

    @Test
    fun neverPastTheEnd() {
        assertThat(watchedFraction(700_000, 600_000, finished = false)).isEqualTo(1f)
    }
}
