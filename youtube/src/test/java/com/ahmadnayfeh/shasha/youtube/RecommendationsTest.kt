package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.Recommendations.Seed
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecommendationsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private fun video(id: String, channel: String = "UC_$id", short: Boolean = false) = VideoItem(
        videoId = id,
        title = "Video $id",
        channelId = channel,
        channelName = channel,
        durationSeconds = if (short) 40 else 600,
        publishedAtEpochMs = null,
        thumbnailUrl = null,
        viewCount = null,
        isLive = false,
        isShortForm = short,
        url = "https://www.youtube.com/watch?v=$id",
    )

    @Test
    fun recency_halvesEveryHalfLife() {
        assertThat(Recommendations.recencyWeight(0)).isWithin(1e-9).of(1.0)
        assertThat(Recommendations.recencyWeight(7 * day)).isWithin(1e-9).of(0.5)
        assertThat(Recommendations.recencyWeight(14 * day)).isWithin(1e-9).of(0.25)
        // A clock that went back never makes a viewing count more than now.
        assertThat(Recommendations.recencyWeight(-day)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun aRecentViewing_outweighsAnOldOne() {
        val ranked = Recommendations.rank(
            seeds = listOf(Seed("old", now - 30 * day), Seed("new", now - day)),
            related = mapOf("old" to listOf(video("fromOld")), "new" to listOf(video("fromNew"))),
            nowMs = now,
        )
        assertThat(ranked.map { it.videoId }).containsExactly("fromNew", "fromOld").inOrder()
    }

    @Test
    fun aSuggestionBesideSeveralWatchedVideos_addsTheirWeights() {
        // "both" is only 3rd beside each of two viewings, "top" 1st beside one: two lists together win.
        val ranked = Recommendations.rank(
            seeds = listOf(Seed("a", now), Seed("b", now)),
            related = mapOf(
                "a" to listOf(video("top"), video("x1"), video("both")),
                "b" to listOf(video("y1"), video("y2"), video("both")),
            ),
            nowMs = now,
        )
        assertThat(ranked.first().videoId).isEqualTo("both")
    }

    @Test
    fun watchedVideosAndTheSeeds_neverComeBack() {
        val ranked = Recommendations.rank(
            seeds = listOf(Seed("a", now), Seed("b", now)),
            related = mapOf("a" to listOf(video("b"), video("seen"), video("fresh"))),
            nowMs = now,
            exclude = setOf("seen"),
        )
        assertThat(ranked.map { it.videoId }).containsExactly("fresh")
    }

    @Test
    fun shorts_onlyWhenTheProfileShowsThem() {
        val related = mapOf("a" to listOf(video("long"), video("short", short = true)))
        assertThat(Recommendations.rank(listOf(Seed("a", now)), related, now, shorts = false).map { it.videoId })
            .containsExactly("long")
        assertThat(Recommendations.rank(listOf(Seed("a", now)), related, now, shorts = true).map { it.videoId })
            .containsExactly("long", "short").inOrder()
    }

    @Test
    fun oneChannel_doesNotFillTheRow() {
        val related = mapOf("a" to (1..6).map { video("same$it", channel = "UCsame") } + video("other"))
        val ranked = Recommendations.rank(listOf(Seed("a", now)), related, now, perChannel = 3)
        assertThat(ranked.map { it.videoId }).containsExactly("same1", "same2", "same3", "other").inOrder()
    }

    @Test
    fun ties_keepTheOrderOfFirstAppearance_andTheLimitHolds() {
        val related = mapOf("a" to listOf(video("p")), "b" to listOf(video("q")))
        val ranked = Recommendations.rank(listOf(Seed("a", now), Seed("b", now)), related, now, limit = 1)
        assertThat(ranked.map { it.videoId }).containsExactly("p")
    }

    @Test
    fun noHistory_noSuggestions() {
        assertThat(Recommendations.rank(emptyList(), emptyMap(), now)).isEmpty()
    }
}
