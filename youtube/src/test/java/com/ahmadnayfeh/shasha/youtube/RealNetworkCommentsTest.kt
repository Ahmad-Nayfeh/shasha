package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * A video's comments against the real YouTube: both orders, a next page, replies. Skipped unless run with:
 *   ./gradlew :youtube:testDebugUnitTest -Dyoutube.realNetwork=true --tests '*RealNetworkCommentsTest*'
 * Every check prints a "REAL-RESULT" line.
 */
class RealNetworkCommentsTest {

    companion object {
        private lateinit var source: VideoSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            source = NewPipeVideoSource(OkHttpClient())
        }

        private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")

        /** «Me at the zoo»: the first YouTube video, with comments that never stop. */
        private const val VIDEO = "jNQXAC9IVRw"
    }

    @Test
    fun topAndNewestAreBothYouTubesOwnOrders_andTheyPageOn() = runBlocking {
        val top = source.comments(VIDEO, CommentSort.TOP)
        delay(3000)
        val newest = source.comments(VIDEO, CommentSort.NEWEST)
        report("top", top.comments.take(5).joinToString { "${it.publishedText}/${it.likeCount}" })
        report("newest", newest.comments.take(5).joinToString { "${it.publishedText}/${it.likeCount}" })
        report("comments", "top=${top.comments.size} (total ${top.totalCount}) newest=${newest.comments.size} first top: ${top.comments.firstOrNull()?.let { "${it.authorName} ${it.likeCount} ${it.publishedText}" }} first newest: ${newest.comments.firstOrNull()?.publishedText}")
        assertThat(top.comments).isNotEmpty()
        assertThat(newest.comments).isNotEmpty()
        assertThat(top.disabled).isFalse()
        // Newest first is not Top: its first comments are hours or minutes old, Top's are the liked ones.
        assertThat(newest.comments.map { it.id }).isNotEqualTo(top.comments.map { it.id })
        delay(3000)
        val more = source.comments(VIDEO, CommentSort.NEWEST, checkNotNull(newest.next))
        report("comments.next", "newest page 2: ${more.comments.size}")
        assertThat(more.comments).isNotEmpty()
        val withReplies = top.comments.firstOrNull { it.replies != null }
        if (withReplies != null) {
            delay(3000)
            val replies = source.commentReplies(VIDEO, withReplies.replies!!)
            report("comments.replies", "${withReplies.replyCount} said, ${replies.comments.size} read")
            assertThat(replies.comments).isNotEmpty()
        }
    }
}
