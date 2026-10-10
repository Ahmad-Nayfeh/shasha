package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.comment
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.ui.comments.CommentsViewModel
import com.ahmadnayfeh.shasha.youtube.CommentSort
import com.ahmadnayfeh.shasha.youtube.CommentsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** The comments of a video: pages, the two orders, comments off, failures and replies, over a scripted source. */
class CommentsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVideoSource()
    private val chosen = ArrayList<CommentSort>()

    private fun vm(sort: CommentSort = CommentSort.TOP) = CommentsViewModel(source, "abcdefghijk", sort, onSortChosen = { chosen += it })

    @Test
    fun nothingIsAskedUntilTheScreenStarts_thenTheFirstPageInTheRememberedOrder() {
        source.onComments = { _, _, _ -> CommentsPage(listOf(comment("a"), comment("b")), PageKey("2"), totalCount = 12) }
        val vm = vm(CommentSort.NEWEST)
        assertThat(source.commentRequests).isEmpty()

        vm.start()
        vm.start() // once

        assertThat(source.commentRequests).containsExactly(Triple("abcdefghijk", CommentSort.NEWEST, null))
        val ui = vm.state.value
        assertThat(ui.comments.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(ui.totalCount).isEqualTo(12)
        assertThat(ui.end).isFalse()
    }

    @Test
    fun pagesFollowEachOther_aRepeatedCommentIsShownOnce_andTheLastPageEndsTheList() {
        source.onComments = { _, _, page ->
            when (page?.id) {
                null -> CommentsPage(listOf(comment("a"), comment("b")), PageKey("2"))
                "2" -> CommentsPage(listOf(comment("b"), comment("c")), null)
                else -> error("no more")
            }
        }
        val vm = vm()
        vm.start()

        vm.loadMore()
        vm.loadMore() // at the end: nothing more is asked

        assertThat(vm.state.value.comments.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(vm.state.value.end).isTrue()
        assertThat(source.commentRequests.map { it.third?.id }).containsExactly(null, "2").inOrder()
    }

    @Test
    fun commentsOffAreSaid_andNothingMoreIsAsked() {
        source.onComments = { _, _, _ -> CommentsPage(emptyList(), null, disabled = true) }
        val vm = vm()

        vm.start()
        vm.loadMore()

        assertThat(vm.state.value.disabled).isTrue()
        assertThat(vm.state.value.isEmpty).isFalse() // «comments are off», not «no comments yet»
        assertThat(source.commentRequests).hasSize(1)
    }

    @Test
    fun noCommentsAtAllIsEmpty() {
        val vm = vm()
        vm.start()
        assertThat(vm.state.value.isEmpty).isTrue()
    }

    @Test
    fun aFailureIsShownAndTryAgainAsksForTheSamePage() {
        var fail = true
        source.onComments = { _, _, _ -> if (fail) throw failure() else CommentsPage(listOf(comment("a")), null) }
        val vm = vm()

        vm.start()
        assertThat(vm.state.value.error).isNotNull()
        assertThat(vm.state.value.comments).isEmpty()

        fail = false
        vm.retry()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.comments.map { it.id }).containsExactly("a")
        assertThat(source.commentRequests.map { it.third }).containsExactly(null, null)
    }

    @Test
    fun anotherOrderStartsTheListAgain_andIsKeptForTheNextVideos() {
        source.onComments = { _, sort, _ -> CommentsPage(listOf(comment(sort.name)), PageKey("next")) }
        val vm = vm()
        vm.start()

        vm.selectSort(CommentSort.NEWEST)
        vm.selectSort(CommentSort.NEWEST) // the same again: nothing

        assertThat(vm.state.value.sort).isEqualTo(CommentSort.NEWEST)
        assertThat(vm.state.value.comments.map { it.id }).containsExactly("NEWEST")
        assertThat(chosen).containsExactly(CommentSort.NEWEST)
        assertThat(source.commentRequests.map { it.second to it.third }).containsExactly(CommentSort.TOP to null, CommentSort.NEWEST to null).inOrder()
    }

    @Test
    fun repliesOpenOnATap_pageOn_andCloseAgain() {
        val parent = comment("p", replies = 3)
        source.onComments = { _, _, _ -> CommentsPage(listOf(parent), null) }
        source.onReplies = { _, page ->
            when (page.id) {
                "replies:p" -> CommentsPage(listOf(comment("r1"), comment("r2")), PageKey("more"))
                "more" -> CommentsPage(listOf(comment("r3")), null)
                else -> error("unknown")
            }
        }
        val vm = vm()
        vm.start()

        vm.toggleReplies(parent)
        assertThat(vm.replies.value["p"]!!.replies.map { it.id }).containsExactly("r1", "r2").inOrder()
        vm.loadMoreReplies(parent)
        assertThat(vm.replies.value["p"]!!.replies.map { it.id }).containsExactly("r1", "r2", "r3").inOrder()
        assertThat(vm.replies.value["p"]!!.next).isNull()

        vm.toggleReplies(parent)
        assertThat(vm.replies.value["p"]!!.expanded).isFalse()
        vm.toggleReplies(parent) // opened again: what was loaded, without asking again
        assertThat(vm.replies.value["p"]!!.expanded).isTrue()
        assertThat(vm.replies.value["p"]!!.replies).hasSize(3)
    }

    @Test
    fun aCommentWithoutRepliesHasNothingToOpen() {
        val vm = vm()
        vm.toggleReplies(comment("lonely"))
        assertThat(vm.replies.value).isEmpty()
    }
}
