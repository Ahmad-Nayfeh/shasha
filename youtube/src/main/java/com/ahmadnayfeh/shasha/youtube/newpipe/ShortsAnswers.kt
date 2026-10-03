package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.YouTubeLinkParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The Shorts in YouTube's answers to a search, which NewPipeExtractor does not read. Everything here is pure text in, text
 * out; nothing fails: an answer that holds no Shorts (or does not look as expected) gives none.
 *
 * What YouTube sends. A search's answer lists its results in `itemSectionRenderer`s, and a Short comes in one of three shapes:
 * - a `shortsLockupViewModel` in a shelf (`gridShelfViewModel`, headed «Shorts»: two on each page of the plain search, between
 *   its videos; and, when the search asks for Shorts alone with the type filter [TYPE_FILTER], the whole page: two shelves of
 *   about 25), which carries the id, the title and views (`overlayMetadata`: "23k views") and vertical pictures, no more;
 * - the same in an older shape, a `reelItemRenderer`;
 * - a `videoRenderer` that points to the Shorts player (`reelWatchEndpoint`: what a search for Shorts alone answers to some
 *   queries, Arabic ones among them), which has the rest too: the channel, the length, the exact views.
 * Where a page continues is a `continuationItemRenderer` with a token. All of it is found by looking for the Shorts and the
 * token wherever they are in the answer, not at a fixed path: YouTube moves its shelves around far more often than it renames
 * what is in them. A `videoRenderer` that is a plain video is not a Short and is left out (the plain search has plenty).
 */
internal object ShortsAnswers {

    /**
     * The search filter «Type: Shorts» (`sp`): InnerTube's protobuf for "filter 2, type 9", as the page's own filter menu
     * sends it. Without it the answer is the plain search.
     */
    const val TYPE_FILTER = "EgIQCQ%3D%3D"

    /**
     * What an answer holds: its [shorts] in YouTube's order (a Short listed twice once), the [next] token (null at the
     * end), and whether it [looksLikeASearch] at all (an error page, or a layout nobody expected, does not).
     */
    class Parsed(val shorts: List<VideoItem>, val next: String?, val looksLikeASearch: Boolean)

    private val NOT_A_SEARCH = Parsed(emptyList(), null, looksLikeASearch = false)

    fun parse(answer: String?): Parsed {
        val root = parse(answer ?: return NOT_A_SEARCH) as? JsonObject ?: return NOT_A_SEARCH
        // The first page holds its results under `contents`, a next page under `onResponseReceivedCommands`.
        val search = root.has("onResponseReceivedCommands") || root.obj("contents").has("twoColumnSearchResultsRenderer")
        if (!search) return NOT_A_SEARCH
        val shorts = LinkedHashMap<String, VideoItem>()
        var next: String? = null
        walk(root) { key, value ->
            when (key) {
                "shortsLockupViewModel" -> (value as? JsonObject)?.let(::shortOf)?.let { shorts.putIfAbsent(it.videoId, it) }
                "reelItemRenderer" -> (value as? JsonObject)?.let(::reelOf)?.let { shorts.putIfAbsent(it.videoId, it) }
                "videoRenderer" -> (value as? JsonObject)?.let(::videoOf)?.let { shorts.putIfAbsent(it.videoId, it) }
                // The last one is the page's own: nothing inside a shelf continues the page.
                "continuationItemRenderer" -> (value as? JsonObject)?.let(::tokenOf)?.let { next = it }
            }
        }
        return Parsed(shorts.values.toList(), next, looksLikeASearch = true)
    }

    /** The length in a text such as "0:19" or "1:02:03", in seconds; null when it is not one. */
    fun secondsOf(text: String?): Long? {
        val parts = text?.trim()?.split(':').orEmpty()
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        return numbers.fold(0L) { total, part -> total * 60 + part }
    }

    /** The views in a text such as "23k views", "1.2M views", "1,234 views" or "No views"; null when it holds no number. */
    fun viewsOf(text: String?): Long? {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return null
        if (t.startsWith("no ")) return 0L
        // "✪" marks a members' Short: YouTube gives no count for it.
        val match = VIEWS.find(t) ?: return null
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val times = when (match.groupValues[2]) {
            "k" -> 1e3
            "m" -> 1e6
            "b" -> 1e9
            else -> 1.0
        }
        return (number * times + 0.5).toLong()
    }

    private val VIEWS = Regex("""(\d[\d,]*(?:\.\d+)?)\s*([kmb])?""")

    // ---- the two kinds of item -------------------------------------------------------------------------------------------

    private fun shortOf(model: JsonObject): VideoItem? {
        val id = model.obj("onTap").obj("innertubeCommand").obj("reelWatchEndpoint").text("videoId")
            ?: model.obj("inlinePlayerData").obj("onVisible").obj("innertubeCommand").obj("watchEndpoint").text("videoId")
            ?: model.text("entityId")?.removePrefix("shorts-shelf-item-")
        val overlay = model.obj("overlayMetadata")
        val picture = model.obj("thumbnailViewModel").obj("thumbnailViewModel").obj("image").arr("sources")
            ?: model.obj("thumbnail").arr("sources")
        return short(id, overlay.obj("primaryText").text("content") ?: titleOf(model.text("accessibilityText")), overlay.obj("secondaryText").text("content"), picture)
    }

    private fun reelOf(item: JsonObject): VideoItem? = short(
        item.text("videoId"),
        item.obj("headline").text("simpleText") ?: item.obj("headline").arr("runs")?.firstOrNull().text("text"),
        item.obj("viewCountText").text("simpleText"),
        item.obj("thumbnail").arr("thumbnails"),
    )

    /** A `videoRenderer`, when it is a Short (it opens the Shorts player): with its channel and length, which the others lack. */
    private fun videoOf(video: JsonObject): VideoItem? {
        val tap = video.obj("navigationEndpoint")
        val page = tap.obj("commandMetadata").obj("webCommandMetadata")
        val isShort = tap.has("reelWatchEndpoint") || page.text("webPageType") == "WEB_PAGE_TYPE_SHORTS" || page.text("url")?.contains("/shorts/") == true
        if (!isShort) return null
        val owner = (video.obj("ownerText") ?: video.obj("longBylineText") ?: video.obj("shortBylineText")).arr("runs")?.firstOrNull() as? JsonObject
        val title = video.obj("title").let { it.text("simpleText") ?: it.arr("runs").orEmpty().joinToString("") { run -> run.text("text").orEmpty() } }
        val frame = tap.obj("reelWatchEndpoint").obj("thumbnail").arr("thumbnails")
        return short(
            id = video.text("videoId"),
            title = title,
            views = video.obj("viewCountText").text("simpleText") ?: video.obj("shortViewCountText").text("simpleText"),
            pictures = frame ?: video.obj("thumbnail").arr("thumbnails"),
            channelId = owner.obj("navigationEndpoint").obj("browseEndpoint").text("browseId")?.takeIf(YouTubeLinkParser::isChannelId),
            channelName = owner.text("text")?.trim()?.takeIf { it.isNotEmpty() },
            seconds = secondsOf(video.obj("lengthText").text("simpleText")),
        )
    }

    private fun short(
        id: String?,
        title: String?,
        views: String?,
        pictures: JsonArray?,
        channelId: String? = null,
        channelName: String? = null,
        seconds: Long? = null,
    ): VideoItem? {
        if (id == null || !YouTubeLinkParser.isVideoId(id)) return null
        return VideoItem(
            videoId = id,
            title = title?.trim().orEmpty(),
            channelId = channelId,
            channelName = channelName,
            durationSeconds = seconds?.takeIf { it > 0 },
            publishedAtEpochMs = null,
            thumbnailUrl = pictureOf(pictures),
            viewCount = viewsOf(views),
            isLive = false,
            isShortForm = true,
            url = "https://www.youtube.com/shorts/$id",
        )
    }

    /** "Title, 23 thousand views – play Short": the title, when the overlay has none (a damaged answer). */
    private fun titleOf(accessibility: String?): String? {
        val text = accessibility?.substringBeforeLast(" – ")?.trim().orEmpty()
        return text.substringBeforeLast(", ", missingDelimiterValue = text).trim().ifEmpty { null }
    }

    /** The biggest picture: a Short's is vertical, 405 wide at most. */
    private fun pictureOf(sources: JsonArray?): String? =
        sources.orEmpty().mapNotNull { it as? JsonObject }
            .filter { !it.text("url").isNullOrEmpty() }
            .maxByOrNull { (it.int("width") ?: 0) * (it.int("height") ?: 0) }
            ?.text("url")

    private fun tokenOf(renderer: JsonObject): String? =
        renderer.obj("continuationEndpoint").obj("continuationCommand").text("token")?.takeIf { it.isNotEmpty() }

    // ---- JSON helpers: every step may be missing, so every step is nullable ---------------------------------------------------

    private fun parse(text: String): JsonElement? =
        try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }

    /** Calls [visit] for every key of every object in [element], depth first and in document order (a visited value is walked too). */
    private fun walk(element: JsonElement, visit: (key: String, value: JsonElement) -> Unit) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                visit(key, value)
                // A result's own parts (its menu is most of an answer) hold nothing else to look for.
                if (key != "shortsLockupViewModel" && key != "reelItemRenderer" && key != "videoRenderer") walk(value, visit)
            }
            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    private fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

    private fun JsonElement?.has(key: String): Boolean = (this as? JsonObject)?.containsKey(key) == true

    private fun JsonElement?.text(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
}
