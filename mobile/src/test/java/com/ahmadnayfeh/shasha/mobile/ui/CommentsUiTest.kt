package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.comment
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.ui.comments.COMMENTS_LIST_TAG
import com.ahmadnayfeh.shasha.mobile.ui.comments.COMMENTS_TEASER_TAG
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.CommentSort
import com.ahmadnayfeh.shasha.youtube.CommentsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Comments in the real app on Robolectric: the section under a video with its first comment, the sheet with all of them (pinned
 * and hearted marks, likes, replies, the two orders, paging), comments off, a failure with «Try again», the Shorts viewer's
 * button, and the «Show comments» setting that takes them all away.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ShortsApp::class, qualifiers = "en-$PHONE")
class CommentsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<Context>().container
    private val source get() = container.videoSource as ShortsSource

    private fun openVideo() {
        container.launch.value = LaunchRequest.Video("vid00000001", null, null)
        compose.startAppOffline(container)
        compose.awaitCondition { container.player.state.value.video?.videoId == "vid00000001" }
    }

    private fun stored() = runBlocking { container.settings.settings.first() }

    @Test
    fun theWatchPageShowsTheFirstComment_andATapOpensThemAll_withTheirMarksAndReplies() {
        source.onComments = { _, _, _ ->
            CommentsPage(
                listOf(
                    comment("c1", text = "Pinned by the creator", pinned = true, hearted = true, likes = 1_500),
                    comment("c2", text = "A comment with replies", replies = 2),
                ),
                null,
                totalCount = 42,
            )
        }
        source.onReplies = { _, _ -> CommentsPage(listOf(comment("r1", text = "The first reply"), comment("r2", text = "The second reply")), null) }
        openVideo()

        compose.awaitText("Pinned by the creator")
        compose.onNodeWithTag(COMMENTS_TEASER_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Comments · 42").assertIsDisplayed()

        compose.onNodeWithTag(COMMENTS_TEASER_TAG).performClick()

        compose.awaitText("A comment with replies")
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hearted by the creator").assertIsDisplayed()
        compose.onNodeWithText("1.5K").assertIsDisplayed()
        compose.onNodeWithText("2 replies").performClick()
        compose.awaitText("The second reply")
        compose.onNodeWithText("Hide replies").assertIsDisplayed()
    }

    @Test
    fun theOrderChipsAskYouTubeForTheOtherOrder_andTheChoiceIsKept() {
        source.onComments = { _, sort, _ -> CommentsPage(listOf(comment("c-$sort", text = "In order $sort")), null) }
        openVideo()
        compose.awaitText("In order TOP")
        compose.onNodeWithTag(COMMENTS_TEASER_TAG).performClick()
        compose.awaitText("Newest first")

        compose.onNodeWithText("Newest first").performClick()

        compose.awaitText("In order NEWEST")
        assertThat(source.commentRequests.map { it.second }).contains(CommentSort.NEWEST)
        compose.awaitCondition { stored().commentSort == CommentSort.NEWEST }
    }

    @Test
    fun theSheetPagesOnAsTheViewerScrolls() {
        source.onComments = { _, _, page ->
            when (page?.id) {
                null -> CommentsPage((1..20).map { comment("a$it", text = "First page $it") }, PageKey("2"))
                else -> CommentsPage(listOf(comment("b1", text = "Second page one")), null)
            }
        }
        openVideo()
        compose.awaitText("First page 1")
        compose.onNodeWithTag(COMMENTS_TEASER_TAG).performClick()
        compose.awaitText("First page 2")

        compose.onNodeWithTag(COMMENTS_LIST_TAG).performScrollToIndex(20)

        compose.awaitCondition { source.commentRequests.any { it.third == PageKey("2") } }
        compose.awaitText("Second page one")
    }

    @Test
    fun commentsTurnedOffAreSaid() {
        source.onComments = { _, _, _ -> CommentsPage(emptyList(), null, disabled = true) }
        openVideo()

        compose.awaitText("Comments are turned off for this video.")
    }

    @Test
    fun noCommentsYetIsSaid() {
        openVideo()
        compose.awaitText("No comments yet.")
    }

    @Test
    fun aFailureOffersTryAgain() {
        var fail = true
        source.onComments = { _, _, _ -> if (fail) throw failure() else CommentsPage(listOf(comment("c1", text = "Here at last")), null) }
        openVideo()
        compose.awaitText("The comments could not be loaded.")

        fail = false
        // The player's own error has a «Try again» too: the one beside the comments' message.
        compose.onNodeWithTag(COMMENTS_TEASER_TAG).performScrollTo()
        compose.onAllNodesWithText("Try again").fetchSemanticsNodes().also { assertThat(it.size).isAtLeast(1) }
        val teaserTry = compose.onAllNodesWithText("Try again")
        // The last «Try again» on the page is the comments' (the player's is above).
        teaserTry[teaserTry.fetchSemanticsNodes().size - 1].performClick()

        compose.awaitText("Here at last")
    }

    @Test
    fun withTheSettingOffThereAreNoComments_onTheWatchPageOrInTheShortsViewer() {
        runBlocking { container.settings.setShowComments(false) }
        source.onComments = { _, _, _ -> CommentsPage(listOf(comment("c1", text = "Never shown")), null) }
        openVideo()
        compose.awaitText("Share")
        compose.waitForIdle()

        compose.onNodeWithTag(COMMENTS_TEASER_TAG).assertDoesNotExist()
        assertThat(compose.onAllNodesWithText("Never shown").fetchSemanticsNodes()).isEmpty()
        assertThat(source.commentRequests).isEmpty()
    }

    @Test
    fun theShortsViewerHasACommentsButton_whenTheSettingShowsThem() {
        val channelId = "UCshortschannel0000000A"
        runBlocking {
            container.settings.setFeedRefreshedAt(System.currentTimeMillis() - 60_000)
            container.database.subscriptions().upsert(SubscriptionEntity(channelId, "Shorts channel", avatarUrl = null, subscribedAt = NOW_MS))
            val uploads = listOf(
                video("vid00000001", title = "A long video", channelId = channelId, publishedAt = 4_000),
                video("sho00000001", title = "Short one", channelId = channelId).copy(isShortForm = true, publishedAtEpochMs = 3_000),
            )
            container.database.feed().upsertAll(uploads.map { it.toFeedEntity(channelId) })
        }
        source.onComments = { id, _, _ -> CommentsPage(listOf(comment("c1", text = "About $id")), null) }
        compose.startAppOffline(container)
        compose.awaitText("Short one")
        compose.onNodeWithText("Short one").performClick()
        compose.awaitCondition { container.player.state.value.video?.videoId == "sho00000001" }

        compose.onNodeWithText("Comments").performClick()

        compose.awaitText("About sho00000001")
    }

    @Test
    fun theSettingsPageTurnsCommentsOffAndOn() {
        compose.startAppOffline(container)
        compose.openSettings("You", "Settings")

        compose.onNodeWithText("Show comments").performScrollTo().performClick()
        compose.awaitCondition { !stored().showComments }
        compose.onNodeWithText("Show comments").performClick()
        compose.awaitCondition { stored().showComments }
    }
}
