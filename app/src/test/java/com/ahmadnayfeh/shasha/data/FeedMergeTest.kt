package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedMergeTest {
    private val id = channelId(1)
    private val channel = subscription(id, name = "Old name")
    private val cutoff = NOW_MS - 90 * DAY_MS

    private fun stored(
        videoId: String,
        publishedAtMs: Long,
        short: Boolean = false,
        live: Boolean = false,
        duration: Long? = null,
        approximate: Boolean = false,
    ) = FeedItemEntity(
        videoId = videoId, channelId = id, channelName = "Old name", title = "old title", publishedAtMs = publishedAtMs,
        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/old.jpg", durationSeconds = duration, isShort = short, isLive = live,
        url = "https://www.youtube.com/watch?v=$videoId", fetchedAtMs = NOW_MS - DAY_MS, publishedAtApproximate = approximate,
    )

    /** As the Videos-tab fallback lists it: a rounded date ("3 days ago"). */
    private fun fromTab(video: VideoItem) = video.copy(publishedAtIsApproximate = true)

    private fun merge(fetched: List<VideoItem>, existing: List<FeedItemEntity> = emptyList()) =
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
        // e.g. an RSS item (no duration) for an upload whose date and duration are known already.
        val outcome = merge(listOf(video("v1", id, publishedAtMs = NOW_MS - 2 * DAY_MS, title = "New title", durationSeconds = null)), existing)
        val item = outcome.items.single()
        assertThat(item.publishedAtMs).isEqualTo(NOW_MS - 3 * DAY_MS)
        assertThat(item.durationSeconds).isEqualTo(610)
        assertThat(item.title).isEqualTo("New title")
        assertThat(item.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/v1/hqdefault.jpg")
    }

    @Test
    fun aLiveStreamWithoutADate_isDatedNow() {
        val live = merge(listOf(video("live1", id, publishedAtMs = null, live = true))).items.single()
        assertThat(live.publishedAtMs).isEqualTo(NOW_MS)
        assertThat(live.publishedAtApproximate).isTrue() // RSS's exact date replaces it later
    }

    @Test
    fun anUndatedUpload_isLeftOut_ratherThanHeadingTheFeedAsNew() {
        val outcome = merge(listOf(fromTab(video("undated", id, publishedAtMs = null)), fromTab(video("dated", id, NOW_MS - DAY_MS))))
        assertThat(outcome.items.map { it.videoId }).containsExactly("dated")
    }

    @Test
    fun anExactDate_correctsAnApproximateOne_neverTheOtherWayRound() {
        // The tab said "2 weeks ago" (at least 14 days); RSS knows the upload is 17 days old.
        val approximate = stored("v1", NOW_MS - 14 * DAY_MS, approximate = true)
        val corrected = merge(listOf(video("v1", id, NOW_MS - 17 * DAY_MS)), listOf(approximate)).items.single()
        assertThat(corrected.publishedAtMs).isEqualTo(NOW_MS - 17 * DAY_MS)
        assertThat(corrected.publishedAtApproximate).isFalse()

        val exact = stored("v2", NOW_MS - 17 * DAY_MS)
        val kept = merge(listOf(fromTab(video("v2", id, NOW_MS - 14 * DAY_MS))), listOf(exact)).items.single()
        assertThat(kept.publishedAtMs).isEqualTo(NOW_MS - 17 * DAY_MS)
        assertThat(kept.publishedAtApproximate).isFalse()

        // Two rounded dates: the first one seen stays (it was read when the upload was younger, so rounded less).
        val again = merge(listOf(fromTab(video("v1", id, NOW_MS - 21 * DAY_MS))), listOf(approximate)).items.single()
        assertThat(again.publishedAtMs).isEqualTo(NOW_MS - 14 * DAY_MS)
    }

    @Test
    fun theVideosTabFallback_removesNothing() {
        // It lists neither shorts nor past streams (RSS does), and its dates are rounded.
        val existing = listOf(stored("pastStream", NOW_MS - 2 * DAY_MS), stored("gone", NOW_MS - 3 * DAY_MS))
        val fetched = listOf(fromTab(video("a", id, NOW_MS - DAY_MS)), fromTab(video("b", id, NOW_MS - 5 * DAY_MS)))
        assertThat(merge(fetched, existing).removed).isEmpty()
    }

    @Test
    fun rowsWithRoundedDates_areNotTakenForDeleted() {
        // Stored from the tab as "1 month ago" (30 days) though 50 days old: older than RSS's window (its oldest
        // upload is 40 days old), so RSS rightly does not list it. Only the exact row is really gone.
        val existing = listOf(stored("long50d", NOW_MS - 30 * DAY_MS, approximate = true), stored("deleted", NOW_MS - 10 * DAY_MS))
        val rss = listOf(video("new", id, NOW_MS - DAY_MS), video("old", id, NOW_MS - 40 * DAY_MS))
        assertThat(merge(rss, existing).removed).containsExactly("deleted")
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
