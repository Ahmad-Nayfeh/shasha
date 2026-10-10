package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.Comment
import com.ahmadnayfeh.shasha.youtube.CommentSort
import com.ahmadnayfeh.shasha.youtube.CommentsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.YouTubeLinkParser
import com.ahmadnayfeh.shasha.youtube.newpipe.ItemMapper.epochMs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.Description

/**
 * A video's comments ([com.ahmadnayfeh.shasha.youtube.VideoSource.comments]) and their replies. Blocking: call inside
 * [NewPipeVideoSource]'s `io`.
 *
 * «Top comments» is what NewPipeExtractor reads by itself ([firstPage]: the watch page's comments section, then its first page).
 * The extractor has no other order, but YouTube's own answer has: the header of the first comments page carries a sort menu
 * («Top comments», «Newest first»), each item a continuation token. For «Newest first» this asks for the watch page's comments
 * section and that first page itself ([post], the same InnerTube endpoint, client and headers as the extractor's), takes the
 * menu's «Newest first» token ([CommentsTokens]), and lets the extractor read the page that token answers ([morePage]), so the
 * comments are read by the extractor's own parser in both orders. Every next page (and every page of replies) is the extractor's
 * own continuation, kept behind a [PageKey] that belongs to the video and the order.
 */
internal class CommentsLoader(
    private val service: StreamingService,
    private val pages: PageStore,
    /** The extractor's first page of «Top comments» for a watch URL. */
    private val firstPage: (url: String) -> FirstPage = { url -> extractorFirstPage(service, url) },
    /** A page the extractor reads from a continuation. */
    private val morePage: (url: String, page: Page) -> InfoItemsPage<CommentsInfoItem> = { url, page -> extractorMorePage(service, url, page) },
    /** Posts a body to InnerTube's `next` and gives the answer's text; tests replace the HTTP. */
    private val post: (body: ByteArray) -> String = ::postToNext,
    /** The WEB client's version the extractor uses (read from YouTube once); tests give their own. */
    private val clientVersion: () -> String = { YoutubeParsingHelper.getClientVersion() },
) {
    /** What the extractor read first: the page, whether comments are off, and how many there are (null: not said). */
    class FirstPage(val page: InfoItemsPage<CommentsInfoItem>, val disabled: Boolean, val count: Long?)

    fun load(videoId: String, sort: CommentSort, page: PageKey?): CommentsPage {
        val url = watchUrl(videoId)
        val owner = owner(videoId, sort)
        if (page != null) {
            // An unknown or expired key ends the list instead of starting it again.
            val (next, _) = pages.get(owner, page) ?: return CommentsPage(emptyList(), null)
            return pageOf(videoId, morePage(url, next), owner)
        }
        return when (sort) {
            CommentSort.TOP -> {
                val first = firstPage(url)
                if (first.disabled) CommentsPage(emptyList(), null, disabled = true) else pageOf(videoId, first.page, owner).copy(totalCount = first.count)
            }
            CommentSort.NEWEST -> {
                // The watch page's comments section, then its first page, whose header has the sort menu.
                val initial = CommentsTokens.initialToken(post(body { put("videoId", videoId) }))
                    ?: return CommentsPage(emptyList(), null, disabled = true) // no comments section: comments are off
                val header = post(body { put("continuation", initial) })
                val newest = CommentsTokens.sortTokens(header).getOrNull(NEWEST_INDEX)
                    // No menu (a video with very few comments has none): its only order is the one YouTube gave.
                    ?: return pageOf(videoId, morePage(url, Page(url, initial)), owner)
                pageOf(videoId, morePage(url, Page(url, newest)), owner).copy(totalCount = CommentsTokens.count(header))
            }
        }
    }

    fun replies(videoId: String, page: PageKey): CommentsPage {
        val owner = repliesOwner(videoId)
        val (next, _) = pages.get(owner, page) ?: return CommentsPage(emptyList(), null)
        return pageOf(videoId, morePage(watchUrl(videoId), next), owner)
    }

    private fun pageOf(videoId: String, page: InfoItemsPage<CommentsInfoItem>, owner: String): CommentsPage {
        val comments = page.items.map { item -> toComment(item, replies = pages.put(repliesOwner(videoId), item.replies)) }
        return CommentsPage(comments.distinctBy { it.id }, pages.put(owner, page.nextPage))
    }

    private fun body(fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray =
        InnerTubeBody.build(service.localization.localizationCode, service.contentCountry.countryCode, clientVersion(), fields)

    internal companion object {
        /** «Top comments» is the menu's first item, «Newest first» its second (their titles are in the viewer's language). */
        const val NEWEST_INDEX = 1

        private const val NEXT_URL = YoutubeParsingHelper.YOUTUBEI_V1_URL + "next?" + YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER

        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

        fun owner(videoId: String, sort: CommentSort) = "comments:$videoId:$sort"

        fun repliesOwner(videoId: String) = "replies:$videoId"

        /** The extractor's comment → ours. Its text is HTML (links, emphasis): it becomes plain text, its line breaks kept. */
        fun toComment(item: CommentsInfoItem, replies: PageKey?): Comment = Comment(
            id = item.commentId?.takeIf { it.isNotBlank() } ?: "${item.uploaderName}:${item.textualUploadDate}:${item.commentText?.content?.hashCode()}",
            authorName = item.uploaderName.orEmpty().trim(),
            authorAvatarUrl = ItemMapper.avatar(item.uploaderAvatars),
            authorChannelId = YouTubeLinkParser.channelIdFromUrl(item.uploaderUrl),
            text = plainText(item.commentText),
            publishedText = item.textualUploadDate?.takeIf { it.isNotBlank() },
            publishedAtEpochMs = item.uploadDate.epochMs(),
            likeCount = item.likeCount.takeIf { it >= 0 }?.toLong(),
            replyCount = item.replyCount.coerceAtLeast(0),
            replies = replies,
            isPinned = item.isPinned,
            isHearted = item.isHeartedByUploader,
            isByUploader = item.isChannelOwner,
            isAuthorVerified = item.isUploaderVerified,
            isEdited = item.isEdited,
        )

        fun plainText(description: Description?): String {
            val content = description?.content ?: return ""
            if (description.type != Description.HTML) return content.trim()
            val text = content.replace(BREAK, "\n").replace(TAG, "")
            return ENTITY.replace(text) { match -> entity(match.groupValues[1]) ?: match.value }.trim()
        }

        private val BREAK = Regex("(?i)<br\\s*/?>")
        private val TAG = Regex("<[^>]*>")
        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

        /** An HTML entity's character: the named ones YouTube uses, and every numbered one. Null: left as it is. */
        private fun entity(name: String): String? = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> when (name) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> "\u00A0"
                else -> null
            }
        }

        fun extractorFirstPage(service: StreamingService, url: String): FirstPage {
            val extractor = service.getCommentsExtractor(url)
            extractor.fetchPage()
            if (extractor.isCommentsDisabled) return FirstPage(InfoItemsPage.emptyPage(), disabled = true, count = null)
            val count = runCatching { extractor.commentsCount.toLong() }.getOrNull()?.takeIf { it >= 0 }
            return FirstPage(extractor.initialPage, disabled = false, count = count)
        }

        fun extractorMorePage(service: StreamingService, url: String, page: Page): InfoItemsPage<CommentsInfoItem> =
            service.getCommentsExtractor(url).getPage(page)

        /** What the extractor's own requests to `next` are: the same URL, headers and localization. */
        fun postToNext(body: ByteArray): String {
            val response = NewPipe.getDownloader().postWithContentTypeJson(
                NEXT_URL,
                YoutubeParsingHelper.getYouTubeHeaders(),
                body,
                NewPipe.getPreferredLocalization(),
            )
            return YoutubeParsingHelper.getValidJsonResponseBody(response)
        }
    }
}

/**
 * The tokens in YouTube's answers about comments, which NewPipeExtractor keeps to itself. Pure text in, text out; nothing fails:
 * an answer without what is looked for gives null (or nothing). Looked for wherever they are in the answer, not at a fixed path.
 */
internal object CommentsTokens {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The token of a watch page's comments section (`next` for a video id): the `continuationItemRenderer` of the
     * `itemSectionRenderer` whose `targetId` is "comments-section". Null when the video has no such section (comments are off).
     */
    fun initialToken(answer: String?): String? {
        val root = parse(answer) ?: return null
        var token: String? = null
        walk(root) { key, value ->
            if (token == null && key == "itemSectionRenderer" && (value as? JsonObject)?.text("targetId") == "comments-section") {
                walk(value) { k, v -> if (token == null && k == "continuationItemRenderer") token = continuationToken(v) }
            }
        }
        return token
    }

    /**
     * The tokens of the sort menu in the header of a first comments page, in the menu's order («Top comments», «Newest first»).
     * Empty when the page has no menu.
     */
    fun sortTokens(answer: String?): List<String> {
        val root = parse(answer) ?: return emptyList()
        var tokens: List<String> = emptyList()
        walk(root) { key, value ->
            if (tokens.isEmpty() && key == "sortFilterSubMenuRenderer") {
                tokens = ((value as? JsonObject)?.get("subMenuItems") as? JsonArray).orEmpty()
                    .mapNotNull { item -> (item as? JsonObject)?.let { continuationToken(it["serviceEndpoint"] ?: it["continuation"]) } }
            }
        }
        return tokens
    }

    /** How many comments the header of a first comments page says the video has ("1,234 Comments", "١٬٢٣٤ تعليقًا"). */
    fun count(answer: String?): Long? {
        val root = parse(answer) ?: return null
        var text: String? = null
        walk(root) { key, value ->
            if (text == null && key == "commentsHeaderRenderer") {
                val count = (value as? JsonObject)?.get("countText") as? JsonObject
                text = ((count?.get("runs") as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }?.joinToString(""))
                    ?: count?.text("simpleText")
            }
        }
        val digits = text?.map { c -> if (Character.isDigit(c)) Character.getNumericValue(c).digitToChar() else c }
            ?.joinToString("")?.let { Regex("\\d[\\d,.\\u066C\\u00A0 ]*").find(it)?.value }
            ?.filter { it.isDigit() }
        return digits?.toLongOrNull()
    }

    private fun continuationToken(element: JsonElement?): String? {
        var token: String? = null
        walk(element ?: return null) { key, value ->
            if (token == null && (key == "continuationCommand" || key == "reloadContinuationData" || key == "nextContinuationData")) {
                token = (value as? JsonObject)?.text("token") ?: (value as? JsonObject)?.text("continuation")
            }
        }
        return token
    }

    private fun parse(text: String?): JsonElement? = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    private fun walk(element: JsonElement, visit: (key: String, value: JsonElement) -> Unit) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                visit(key, value)
                walk(value, visit)
            }
            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
