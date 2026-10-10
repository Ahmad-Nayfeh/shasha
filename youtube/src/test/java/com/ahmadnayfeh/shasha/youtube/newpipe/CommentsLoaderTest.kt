package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.CommentSort
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description

/**
 * Comments: the tokens read from YouTube's answers (the comments section, the sort menu, the count), the extractor's comment as
 * ours, and the loader's orders and pages over a scripted extractor and a scripted InnerTube (no network).
 */
class CommentsLoaderTest {

    // --- what YouTube's answers hold -------------------------------------------------------------------------------

    /** The watch page's `next` answer: the related items, then the comments section with its token. */
    private val watchAnswer = """
        {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
          {"videoPrimaryInfoRenderer":{}},
          {"itemSectionRenderer":{"targetId":"watch-next-feed","contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"RELATED"}}}}]}},
          {"itemSectionRenderer":{"targetId":"comments-section","contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"COMMENTS"}}}}]}}
        ]}}}}}
    """.trimIndent()

    /** The first comments page's header: the count and the sort menu. */
    private fun headerAnswer(count: String = "1,234 Comments") = """
        {"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[{"commentsHeaderRenderer":{
          "countText":{"runs":[{"text":"$count"}]},
          "sortMenu":{"sortFilterSubMenuRenderer":{"subMenuItems":[
            {"title":"Top","selected":true,"serviceEndpoint":{"continuationCommand":{"token":"TOP_TOKEN"}}},
            {"title":"Newest","selected":false,"serviceEndpoint":{"continuationCommand":{"token":"NEWEST_TOKEN"}}}
          ]}}}}]}}]}
    """.trimIndent()

    @Test
    fun theCommentsSectionsTokenIsTheOneOfThatSection_notTheRelatedVideos() {
        assertThat(CommentsTokens.initialToken(watchAnswer)).isEqualTo("COMMENTS")
    }

    @Test
    fun aWatchPageWithoutACommentsSectionHasNoToken() {
        assertThat(CommentsTokens.initialToken("""{"contents":{"itemSectionRenderer":{"targetId":"watch-next-feed"}}}""")).isNull()
        assertThat(CommentsTokens.initialToken("not json")).isNull()
        assertThat(CommentsTokens.initialToken(null)).isNull()
    }

    @Test
    fun theSortMenuGivesTopThenNewest() {
        assertThat(CommentsTokens.sortTokens(headerAnswer())).containsExactly("TOP_TOKEN", "NEWEST_TOKEN").inOrder()
        assertThat(CommentsTokens.sortTokens("""{"onResponseReceivedEndpoints":[]}""")).isEmpty()
    }

    @Test
    fun theCountIsReadInBothLanguages() {
        assertThat(CommentsTokens.count(headerAnswer("1,234 Comments"))).isEqualTo(1234)
        assertThat(CommentsTokens.count(headerAnswer("١٬٢٣٤ تعليقًا"))).isEqualTo(1234)
        assertThat(CommentsTokens.count(headerAnswer("No comments"))).isNull()
    }

    // --- the extractor's comment as ours ---------------------------------------------------------------------------

    private fun item(id: String, html: String = "Hello", likes: Int = 5, replies: Page? = null) =
        CommentsInfoItem(ServiceList.YouTube.serviceId, "https://www.youtube.com/watch?v=abcdefghijk", "c").apply {
            commentId = id
            uploaderName = " Someone "
            uploaderUrl = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"
            uploaderAvatars = listOf(Image("https://yt3.ggpht.com/a=s88", 88, 88, Image.ResolutionLevel.LOW))
            commentText = Description(html, Description.HTML)
            textualUploadDate = "3 days ago"
            likeCount = likes
            replyCount = if (replies != null) 2 else 0
            setReplies(replies)
            isPinned = id == "pinned"
            isHeartedByUploader = id == "pinned"
            setChannelOwner(id == "pinned")
        }

    @Test
    fun aCommentsHtmlBecomesPlainText_itsLinesKept() {
        val text = CommentsLoader.plainText(Description("First line<br>see <a href=\"https://x.y\">https://x.y</a> &amp; <b>more</b>", Description.HTML))
        assertThat(text).isEqualTo("First line\nsee https://x.y & more")
        assertThat(CommentsLoader.plainText(Description("  plain  ", Description.PLAIN_TEXT))).isEqualTo("plain")
        assertThat(CommentsLoader.plainText(null)).isEmpty()
    }

    @Test
    fun aCommentKeepsItsAuthorCountsAndMarks() {
        val comment = CommentsLoader.toComment(item("pinned", likes = 12), replies = PageKey("r"))

        assertThat(comment.id).isEqualTo("pinned")
        assertThat(comment.authorName).isEqualTo("Someone")
        assertThat(comment.authorChannelId).isEqualTo("UCabcdefghijklmnopqrstuv")
        assertThat(comment.authorAvatarUrl).isEqualTo("https://yt3.ggpht.com/a=s88")
        assertThat(comment.publishedText).isEqualTo("3 days ago")
        assertThat(comment.likeCount).isEqualTo(12)
        assertThat(comment.replies).isEqualTo(PageKey("r"))
        assertThat(comment.isPinned).isTrue()
        assertThat(comment.isHearted).isTrue()
        assertThat(comment.isByUploader).isTrue()
    }

    @Test
    fun anUnknownLikeCountIsNull_notZero() {
        assertThat(CommentsLoader.toComment(item("a", likes = -1), replies = null).likeCount).isNull()
    }

    // --- the loader ------------------------------------------------------------------------------------------------

    private val asked = ArrayList<String>()
    private val posted = ArrayList<String>()
    private var disabled = false
    private var answers = ArrayDeque<String>()

    private fun loader(store: PageStore = PageStore()) = CommentsLoader(
        service = ServiceList.YouTube,
        pages = store,
        firstPage = { url ->
            asked += "first:$url"
            if (disabled) {
                CommentsLoader.FirstPage(InfoItemsPage.emptyPage(), disabled = true, count = null)
            } else {
                CommentsLoader.FirstPage(InfoItemsPage(listOf(item("t1"), item("t2", replies = Page("u", "REPLIES"))), Page("u", "TOP_NEXT"), emptyList()), false, 40)
            }
        },
        morePage = { _, page ->
            asked += "more:${page.id}"
            when (page.id) {
                "NEWEST_TOKEN", "COMMENTS" -> InfoItemsPage(listOf(item("n1"), item("n2")), Page("u", "NEWEST_NEXT"), emptyList())
                "REPLIES" -> InfoItemsPage(listOf(item("r1")), null, emptyList())
                else -> InfoItemsPage(listOf(item("x-${page.id}")), null, emptyList())
            }
        },
        post = { body ->
            posted += String(body)
            answers.removeFirst()
        },
        clientVersion = { "2.20260101.00.00" },
    )

    @Test
    fun topIsTheExtractorsOwnFirstPage_withTheCount_andItsNextPageGoesOn() {
        val loader = loader()

        val first = loader.load("abcdefghijk", CommentSort.TOP, null)

        assertThat(first.comments.map { it.id }).containsExactly("t1", "t2").inOrder()
        assertThat(first.totalCount).isEqualTo(40)
        assertThat(first.disabled).isFalse()
        assertThat(posted).isEmpty() // no request of our own for YouTube's default order
        val second = loader.load("abcdefghijk", CommentSort.TOP, first.next)
        assertThat(second.comments.map { it.id }).containsExactly("x-TOP_NEXT")
        assertThat(second.next).isNull()
    }

    @Test
    fun newestTakesTheMenusNewestTokenFromYouTubesOwnAnswer() {
        answers = ArrayDeque(listOf(watchAnswer, headerAnswer()))
        val loader = loader()

        val page = loader.load("abcdefghijk", CommentSort.NEWEST, null)

        assertThat(page.comments.map { it.id }).containsExactly("n1", "n2").inOrder()
        assertThat(page.totalCount).isEqualTo(1234)
        assertThat(asked).containsExactly("more:NEWEST_TOKEN")
        // The watch page's comments section for the video, then that section's first page.
        assertThat(posted[0]).contains("\"videoId\":\"abcdefghijk\"")
        assertThat(posted[1]).contains("\"continuation\":\"COMMENTS\"")
        val next = loader.load("abcdefghijk", CommentSort.NEWEST, page.next)
        assertThat(next.comments.map { it.id }).containsExactly("x-NEWEST_NEXT")
    }

    @Test
    fun newestWithoutACommentsSectionIsCommentsOff() {
        answers = ArrayDeque(listOf("""{"contents":{}}"""))

        val page = loader().load("abcdefghijk", CommentSort.NEWEST, null)

        assertThat(page.disabled).isTrue()
        assertThat(page.comments).isEmpty()
    }

    @Test
    fun newestWithoutASortMenuIsTheOnlyOrderThereIs() {
        answers = ArrayDeque(listOf(watchAnswer, """{"onResponseReceivedEndpoints":[]}"""))

        val page = loader().load("abcdefghijk", CommentSort.NEWEST, null)

        assertThat(page.comments.map { it.id }).containsExactly("n1", "n2")
        assertThat(asked).containsExactly("more:COMMENTS")
    }

    @Test
    fun commentsOffAreSaid() {
        disabled = true

        val page = loader().load("abcdefghijk", CommentSort.TOP, null)

        assertThat(page.disabled).isTrue()
        assertThat(page.next).isNull()
    }

    @Test
    fun aKeyBelongsToItsVideoAndOrder_anotherGetsAnEmptyLastPage() {
        val loader = loader()
        val first = loader.load("abcdefghijk", CommentSort.TOP, null)

        assertThat(loader.load("abcdefghijk", CommentSort.NEWEST, first.next).comments).isEmpty()
        assertThat(loader.load("otherVideo1", CommentSort.TOP, first.next).comments).isEmpty()
        assertThat(loader.load("abcdefghijk", CommentSort.TOP, PageKey("unknown")).next).isNull()
    }

    @Test
    fun aCommentWithRepliesOpensThem() {
        val loader = loader()
        val withReplies = loader.load("abcdefghijk", CommentSort.TOP, null).comments.single { it.id == "t2" }
        assertThat(withReplies.replyCount).isEqualTo(2)

        val replies = loader.replies("abcdefghijk", checkNotNull(withReplies.replies))

        assertThat(replies.comments.map { it.id }).containsExactly("r1")
        assertThat(replies.next).isNull()
        // A comment without replies has no key.
        assertThat(loader.load("abcdefghijk", CommentSort.TOP, null).comments.single { it.id == "t1" }.replies).isNull()
    }
}
