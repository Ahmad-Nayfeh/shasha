package com.ahmadnayfeh.shasha.mobile.ui.comments

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.logLine
import com.ahmadnayfeh.shasha.youtube.Comment
import com.ahmadnayfeh.shasha.youtube.CommentSort
import com.ahmadnayfeh.shasha.youtube.CommentsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The comments of one video as the screen shows them: the [comments] loaded so far in the order [sort], whether more are
 * [loading], the [error] of the last page asked for, the [disabled] state (the uploader turned comments off), the [end] of the
 * list, and the [totalCount] YouTube gives. [started]: the first page has answered (with comments or not).
 */
@Immutable
data class CommentsUi(
    val sort: CommentSort = CommentSort.TOP,
    val comments: List<Comment> = emptyList(),
    val loading: Boolean = false,
    val error: UiError? = null,
    val disabled: Boolean = false,
    val end: Boolean = false,
    val totalCount: Long? = null,
    val started: Boolean = false,
) {
    val isEmpty: Boolean get() = started && comments.isEmpty() && !disabled && error == null && end
}

/** The replies of one comment, once the viewer opened them: those loaded so far, and whether more come. */
@Immutable
data class RepliesUi(
    val replies: List<Comment> = emptyList(),
    val loading: Boolean = false,
    val error: UiError? = null,
    val next: PageKey? = null,
    val expanded: Boolean = true,
)

/**
 * One video's comments ([VideoSource.comments]): page after page as the viewer scrolls ([loadMore]), in YouTube's «Top» or
 * «Newest first» order ([selectSort]: the list starts again, and the choice is kept for the next videos through [onSortChosen]),
 * and the replies of a comment when the viewer opens them ([toggleReplies], [loadMoreReplies]). Nothing is asked of YouTube until
 * the screen asks for the first page ([start]): the comments of a video the viewer never scrolls to cost nothing.
 */
class CommentsViewModel(
    private val source: VideoSource,
    val videoId: String,
    initialSort: CommentSort,
    private val onSortChosen: (CommentSort) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(CommentsUi(sort = initialSort))
    val state: StateFlow<CommentsUi> = _state

    private val _replies = MutableStateFlow<Map<String, RepliesUi>>(emptyMap())

    /** Opened replies, by the comment's id. */
    val replies: StateFlow<Map<String, RepliesUi>> = _replies

    private var next: PageKey? = null
    private var job: Job? = null

    /** Asks for the first page, once. */
    fun start() {
        if (_state.value.started || _state.value.loading) return
        loadMore()
    }

    /** The next page (the first one when nothing was asked yet); nothing at the end, while a page is coming, or with comments off. */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.end || current.disabled) return
        val sort = current.sort
        val page = next
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val answer = source.comments(videoId, sort, page)
                if (_state.value.sort != sort) return@launch
                applyPage(answer, first = page == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$videoId: comments failed: ${e.logLine()}")
                _state.update { it.copy(loading = false, error = UiError.of(e), started = true) }
            }
        }
    }

    private fun applyPage(answer: CommentsPage, first: Boolean) {
        next = answer.next
        _state.update { now ->
            // YouTube repeats a comment now and then from one page to the next: shown once.
            val known = now.comments.mapTo(HashSet()) { it.id }
            now.copy(
                comments = now.comments + answer.comments.filter { known.add(it.id) },
                loading = false,
                error = null,
                disabled = answer.disabled,
                end = answer.disabled || answer.next == null,
                totalCount = if (first) answer.totalCount ?: now.totalCount else now.totalCount,
                started = true,
            )
        }
    }

    /** «Try again» after a failed page. */
    fun retry() = loadMore()

    /** Another order: the list starts again from its first page, and the order is kept for the next videos. */
    fun selectSort(sort: CommentSort) {
        if (_state.value.sort == sort) return
        onSortChosen(sort)
        job?.cancel()
        next = null
        _replies.value = emptyMap()
        _state.value = CommentsUi(sort = sort, totalCount = _state.value.totalCount)
        loadMore()
    }

    /** Opens a comment's replies (asking for their first page the first time), or closes them. */
    fun toggleReplies(comment: Comment) {
        val key = comment.replies ?: return
        val now = _replies.value[comment.id]
        if (now != null) {
            _replies.update { it + (comment.id to now.copy(expanded = !now.expanded)) }
            return
        }
        loadReplies(comment.id, key, RepliesUi(loading = true))
    }

    /** The next page of a comment's replies. */
    fun loadMoreReplies(comment: Comment) {
        val now = _replies.value[comment.id] ?: return
        if (now.loading) return
        // After a failure the same page is asked again: the first one when nothing came yet.
        val key = now.next ?: if (now.replies.isEmpty()) comment.replies else null
        key ?: return
        loadReplies(comment.id, key, now.copy(loading = true, error = null))
    }

    private fun loadReplies(id: String, key: PageKey, loadingState: RepliesUi) {
        _replies.update { it + (id to loadingState) }
        viewModelScope.launch {
            try {
                val answer = source.commentReplies(videoId, key)
                _replies.update { all ->
                    val now = all[id] ?: return@update all
                    all + (id to now.copy(replies = now.replies + answer.comments, loading = false, next = answer.next, error = null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$videoId: replies failed: ${e.logLine()}")
                _replies.update { all -> all[id]?.let { all + (id to it.copy(loading = false, error = UiError.of(e))) } ?: all }
            }
        }
    }

    companion object {
        private const val TAG = "CommentsViewModel"

        fun factory(container: AppContainer, videoId: String) = viewModelFactory {
            initializer {
                CommentsViewModel(
                    source = container.videoSource,
                    videoId = videoId,
                    initialSort = container.latestSettings().commentSort,
                    onSortChosen = { sort -> container.saveCommentSort(sort) },
                )
            }
        }
    }
}
