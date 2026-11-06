package com.ahmadnayfeh.shasha.mobile.ui.comments

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.OnNearEnd
import com.ahmadnayfeh.shasha.mobile.ui.components.PagedFooter
import com.ahmadnayfeh.shasha.youtube.Comment
import com.ahmadnayfeh.shasha.youtube.CommentSort

/** The comments' parts, for the tests. */
internal const val COMMENTS_TEASER_TAG = "comments_teaser"
internal const val COMMENTS_LIST_TAG = "comments_list"

/**
 * The comments' place under a video (the watch page): their title and count, and the first comment, or what there is instead
 * (comments off, none yet, loading, a failure with «Try again»). A tap opens them all ([onOpen]: [CommentsSheet]). The first page
 * is asked for when this shows.
 */
@Composable
internal fun CommentsTeaser(vm: CommentsViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.start() }
    Column(
        modifier.fillMaxWidth().testTag(COMMENTS_TEASER_TAG).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !ui.disabled, onClickLabel = stringResource(R.string.comments_open), onClick = onOpen)
            .padding(12.dp),
    ) {
        CommentsTitle(ui)
        Spacer(Modifier.height(8.dp))
        val first = ui.comments.firstOrNull()
        when {
            ui.disabled -> Muted(stringResource(R.string.comments_disabled))
            first != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(first.authorAvatarUrl, size = 24, name = first.authorName)
                Text(first.text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ui.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Muted(stringResource(R.string.comments_error), Modifier.weight(1f))
                TextButton(onClick = vm::retry) { Text(stringResource(R.string.action_retry)) }
            }
            ui.isEmpty -> Muted(stringResource(R.string.comments_empty))
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** All the comments, in a sheet over the page (the watch page's, or the Shorts viewer's). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentsSheet(vm: CommentsViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CommentsList(vm, Modifier.fillMaxHeight(0.85f).navigationBarsPadding())
    }
}

/**
 * The comments, page after page as the list nears its end, in the order the chips at the top say (YouTube's «Top» and «Newest
 * first»: it has no other), each with its author, time, text, likes and marks (pinned, a heart from the creator, the creator's
 * own), and its replies under it once opened.
 */
@Composable
internal fun CommentsList(vm: CommentsViewModel, modifier: Modifier = Modifier) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val replies by vm.replies.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.start() }
    Column(modifier.fillMaxWidth()) {
        CommentsTitle(ui, Modifier.padding(horizontal = 16.dp))
        if (!ui.disabled) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sort in CommentSort.entries) {
                    FilterChip(selected = ui.sort == sort, onClick = { vm.selectSort(sort) }, label = { Text(stringResource(sortLabel(sort))) })
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when {
                ui.disabled -> Muted(stringResource(R.string.comments_disabled), Modifier.align(Alignment.Center).padding(24.dp))
                ui.comments.isEmpty() && ui.error != null -> ErrorState(ui.error!!, onRetry = vm::retry)
                ui.isEmpty -> Muted(stringResource(R.string.comments_empty), Modifier.align(Alignment.Center).padding(24.dp))
                ui.comments.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val listState = rememberLazyListState()
                    listState.OnNearEnd { vm.loadMore() }
                    LazyColumn(Modifier.fillMaxSize().testTag(COMMENTS_LIST_TAG), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                        for (comment in ui.comments) {
                            item(key = "c:${comment.id}") {
                                val open = replies[comment.id]
                                CommentRow(comment, isReply = false, repliesOpen = open?.expanded == true, onToggleReplies = { vm.toggleReplies(comment) })
                            }
                            val open = replies[comment.id]
                            if (open != null && open.expanded) {
                                items(open.replies, key = { "r:${comment.id}:${it.id}" }) { reply -> CommentRow(reply, isReply = true) }
                                if (open.loading || open.error != null || open.next != null) {
                                    item(key = "rf:${comment.id}") {
                                        when {
                                            open.loading -> Box(Modifier.fillMaxWidth().padding(start = 60.dp, top = 4.dp, bottom = 4.dp)) {
                                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                            }
                                            else -> TextButton(onClick = { vm.loadMoreReplies(comment) }, Modifier.padding(start = 52.dp)) {
                                                Text(stringResource(if (open.error != null) R.string.action_retry else R.string.comments_more_replies))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "footer") {
                            if (!ui.end || ui.error != null) PagedFooter(loading = ui.loading, error = ui.error, onRetry = vm::retry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentsTitle(ui: CommentsUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val count = ui.totalCount?.takeIf { it > 0 }
    Text(
        if (count != null) stringResource(R.string.comments_count, Formatters.compactCount(context, count)) else stringResource(R.string.comments_title),
        modifier,
        style = MaterialTheme.typography.titleSmall,
    )
}

/** One comment (or reply): avatar, author and time, the text (all of it on a tap), likes, the creator's heart, and its replies' button. */
@Composable
internal fun CommentRow(comment: Comment, isReply: Boolean, repliesOpen: Boolean = false, onToggleReplies: () -> Unit = {}) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = if (isReply) 56.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)) {
        Avatar(comment.authorAvatarUrl, size = if (isReply) 24 else 36, name = comment.authorName)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            if (comment.isPinned) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Muted(stringResource(R.string.comments_pinned), Modifier.padding(start = 4.dp), small = true)
                }
            }
            val time = comment.publishedAtEpochMs?.let { Formatters.relativeTime(context, it, System.currentTimeMillis()) } ?: comment.publishedText
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.authorName,
                    Modifier.weight(1f, fill = false).then(
                        if (comment.isByUploader) {
                            Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp)
                        } else {
                            Modifier
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val tail = listOfNotNull(time, stringResource(R.string.comments_edited).takeIf { comment.isEdited }).joinToString(" ")
                if (tail.isNotEmpty()) Muted(" · $tail", small = true, maxLines = 1)
            }
            Text(
                comment.text,
                Modifier.padding(top = 2.dp).animateContentSize().clickable { expanded = !expanded },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ThumbUp, contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                comment.likeCount?.takeIf { it > 0 }?.let { likes ->
                    val text = Formatters.compactCount(context, likes)
                    Muted(text, Modifier.padding(start = 4.dp), small = true, description = stringResource(R.string.comments_likes, text))
                }
                if (comment.isHearted) {
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.comments_hearted), Modifier.size(14.dp), tint = Color(0xFFE53935))
                }
            }
            if (!isReply && comment.replyCount > 0 && comment.replies != null) {
                TextButton(onClick = onToggleReplies, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text(
                        when {
                            repliesOpen -> stringResource(R.string.comments_hide_replies)
                            comment.replyCount == 1 -> stringResource(R.string.comments_show_reply)
                            else -> stringResource(R.string.comments_show_replies, Formatters.compactCount(context, comment.replyCount.toLong()))
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = false, maxLines: Int = Int.MAX_VALUE, description: String? = null) {
    Text(
        text,
        modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun sortLabel(sort: CommentSort): Int = when (sort) {
    CommentSort.TOP -> R.string.comments_sort_top
    CommentSort.NEWEST -> R.string.comments_sort_newest
}
