package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedMergeTest {
    private val id = channelId(1)
    private val channel = subscription(id, name = "Old name")
    private val cutoff = NOW_MS - 90 * DAY_MS

    private fun stored(videoId: String, publishedAtMs: Long, short: Boolean = false, live: Boolean = false, duration: Long? = null) =
        FeedItemEntity(
            videoId = videoId, channelId = id, channelName = "Old name", title = "old title", publishedAtMs = publishedAtMs,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/old.jpg", durationSeconds = duration, isShort = short, isLive = live,
            url = "https://www.youtube.com/watch?v=$videoId", fetchedAtMs = NOW_MS - DAY_MS,
        )

    private fun merge(fetched: List<com.ahmadnayfeh.shasha.youtube.VideoItem>, existing: List<FeedItemEntity> = emptyList()) =
        FeedMerge.merge(channel, fetched, existing, NOW_MS, cutoff)

    @Test
    fun newUploads_belongToTheSubscribedChannel_andCarryItsName() {
        val outcome = merge(listOf(video("v1", channelId = "UCsomethingelse00000000000", publishedAtMs = NOW_MS - HOUR_MS, channelName = null)))
        val item = outcome.items.single()
        assertThat(item.channelId).isEqualTo(id)
        assertThat(item.channelName).isEqualTo("Old name")
        assertThat(item.publishedAtMs).isEqualTo(NOW_MS - HOUR_MS)
        assertThat(item.fetchedAtMs).isEqualTo(NOW_MS)
    }

    @Test
    fun knownUploads_keepTheirFirstDateAndDuration_butTakeTheNewTitle() {
        val existing = listOf(stored("v1", publishedAtMs = NOW_MS - 3 * DAY_MS, duration = 610))
        // e.g. an RSS item (no duration) after the Videos-tab fallback gave an approximate date and a duration.
        val outcome = merge(listOf(video("v1", id, publishedAtMs = NOW_MS - 2 * DAY_MS, title = "New title", durationSeconds = null)), existing)
        val item = outcome.items.single()
        assertThat(item.publishedAtMs).isEqualTo(NOW_MS - 3 * DAY_MS)
        assertThat(item.durationSeconds).isEqualTo(610)
        assertThat(item.title).isEqualTo("New title")
        assertThat(item.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/v1/hqdefault.jpg")
    }

    @Test
    fun anUploadWithoutADate_isDatedNow() {
        assertThat(merge(listOf(video("live1", id, publishedAtMs = null, live = true))).items.single().publishedAtMs).isEqualTo(NOW_MS)
    }

    @Test
    fun uploadsOlderThanTheCutoff_areNotStored() {
        val outcome = merge(listOf(video("new", id, NOW_MS - DAY_MS), video("ancient", id, NOW_MS - 200 * DAY_MS)))
        assertThat(outcome.items.map { it.videoId }).containsExactly("new")
    }

    @Test
    fun uploadsThatVanishedFromTheChannel_areRemoved_shortsAndLivesAreSpared() {
        val existing = listOf(
            stored("deleted", NOW_MS - 2 * DAY_MS),
            stored("short", NOW_MS - 2 * DAY_MS, short = true),
            stored("live", NOW_MS - 2 * DAY_MS, live = true),
            stored("olderThanTheList", NOW_MS - 30 * DAY_MS),
        )
        val fetched = listOf(video("a", id, NOW_MS - DAY_MS), video("b", id, NOW_MS - 5 * DAY_MS))
        assertThat(merge(fetched, existing).removed).containsExactly("deleted")
    }

    @Test
    fun anEmptyFetch_removesNothing() {
        assertThat(merge(emptyList(), listOf(stored("x", NOW_MS))).removed).isEmpty()
    }

    @Test
    fun theChannelNameMostUploadsCarry_becomesTheNewName() {
        val outcome = merge(
            listOf(
                video("a", id, NOW_MS, channelName = "Fresh name"),
                video("b", id, NOW_MS, channelName = "Fresh name"),
                video("c", id, NOW_MS, channelName = "Guest channel"),
            ),
        )
        assertThat(outcome.newName).isEqualTo("Fresh name")
        assertThat(merge(listOf(video("a", id, NOW_MS, channelName = "Old name"))).newName).isNull()
        assertThat(merge(listOf(video("a", id, NOW_MS, channelName = " "))).newName).isNull()
    }

    @Test
    fun aShortStaysAShort_evenIfALaterListingDoesNotSaySo() {
        val existing = listOf(stored("s", NOW_MS - DAY_MS, short = true))
        assertThat(merge(listOf(video("s", id, NOW_MS - DAY_MS, short = false)), existing).items.single().isShort).isTrue()
    }
}
