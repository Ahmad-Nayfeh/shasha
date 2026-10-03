package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.ChannelTabPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.YouTubeLinkParser
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs as ExtractorTabs

/**
 * A channel's sections ([ChannelTab]) in the orders YouTube offers ([ChannelSort]). Blocking: call inside
 * [NewPipeVideoSource]'s `io`.
 *
 * YouTube's own order is what NewPipeExtractor reads, tab by tab, page by page ([ChannelTabInfo]). The same answer holds the
 * order chips; [ChannelChips] reads them from it (through [BrowseTaps]), so which sorts a section
 * offers is known with its first page and costs no request of its own. The other orders are that chip's token posted to
 * browse; the answer is handed to the extractor as a next page of the list ([ChannelChips.reloadAsAppend]), and its
 * pages go on like any other. A sort is only ever asked for with a chip's token: where a section has no such chip it is not
 * offered, and nothing is guessed.
 */
internal class ChannelTabLoader(
    private val service: StreamingService,
    private val taps: BrowseTaps,
    private val pages: PageStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** What a page's key remembers: the orders its section offers, the same on every page. */
    private class Context(val offered: List<ChannelSort>)

    /** The chips of a section's first page, kept a little while: choosing a sort uses their token without asking again. */
    private class Kept(val sorts: ChannelChips.Sorts, val channelName: String?, val atMs: Long)

    private class First(val items: List<InfoItem>, val next: Page?, val kept: Kept)

    private val keptSorts = object : LinkedHashMap<String, Kept>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > MAX_KEPT
    }

    fun load(channelId: String, tab: ChannelTab, sort: ChannelSort, page: PageKey?): ChannelTabPage {
        if (sort != ChannelSort.LATEST && !tab.listsVideos) throw sortUnavailable(tab, sort)
        val owner = owner(channelId, tab, sort)
        if (page != null) {
            // An unknown or expired key ends the list instead of repeating the first page.
            val (next, context) = pages.get(owner, page) ?: return ChannelTabPage(emptyList(), null)
            return later(channelId, tab, owner, next, context as? Context ?: Context(listOf(ChannelSort.LATEST)))
        }
        return if (sort == ChannelSort.LATEST) firstLatest(channelId, tab, owner) else firstSorted(channelId, tab, sort, owner)
    }

    private fun firstLatest(channelId: String, tab: ChannelTab, owner: String): ChannelTabPage {
        val first = fetchFirst(channelId, tab)
        val offered = first.kept.sorts.offered
        return ChannelTabPage(items(tab, channelId, first.items), pages.put(owner, first.next, Context(offered)), offered)
    }

    /** The first page in YouTube's own order, and the chips that came with it (kept). Blocking. */
    private fun fetchFirst(channelId: String, tab: ChannelTab): First {
        val tap = BrowseTap()
        val info = taps.watch(tap) { ChannelTabInfo.getInfo(service, handler(channelId, tab)) }
        NewPipeVideoSource.failIfBroken(info.relatedItems, info.errors)
        // The playlists are not sorted: nothing to look for in their answer.
        val sorts = if (tab.listsVideos) ChannelChips.sortsOf(tap.lastAnswer) else ChannelChips.Sorts.NONE
        val kept = Kept(sorts, channelNameOf(info.relatedItems), nowMs())
        synchronized(keptSorts) { keptSorts[keptKey(channelId, tab)] = kept }
        return First(info.relatedItems, info.nextPage, kept)
    }

    private fun firstSorted(channelId: String, tab: ChannelTab, sort: ChannelSort, owner: String): ChannelTabPage {
        val kept = synchronized(keptSorts) { keptSorts[keptKey(channelId, tab)] }?.takeIf { nowMs() - it.atMs < KEEP_MS }
        if (kept != null) {
            try {
                return sorted(channelId, tab, sort, owner, kept)
            } catch (e: SourceException) {
                throw e
            } catch (_: Exception) {
                // The token may have gone stale: ask for the chips again, once.
            }
        }
        return sorted(channelId, tab, sort, owner, fetchFirst(channelId, tab).kept)
    }

    private fun sorted(channelId: String, tab: ChannelTab, sort: ChannelSort, owner: String, kept: Kept): ChannelTabPage {
        val token = kept.sorts.tokens[sort] ?: throw sortUnavailable(tab, sort)
        val body = ChannelChips.continuationBody(
            token,
            language = service.localization.localizationCode,
            country = service.contentCountry.countryCode,
            clientVersion = YoutubeParsingHelper.getClientVersion(),
        )
        // What the extractor's own next pages carry: the channel's name and url (a channel's items do not say who made them).
        val request = Page(
            YoutubeParsingHelper.YOUTUBEI_V1_URL + "browse?" + YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER,
            null,
            listOf(kept.channelName.orEmpty(), NewPipeVideoSource.channelUrl(channelId), UNKNOWN_VERIFIED),
            null,
            body,
        )
        val tap = BrowseTap(reshape = ChannelChips::reloadAsAppend)
        val result = taps.watch(tap) { ChannelTabInfo.getMoreItems(service, handler(channelId, tab), request) }
        checkAnswer(tap.lastAnswer, sort)
        NewPipeVideoSource.failIfBroken(result.items, result.errors)
        return ChannelTabPage(items(tab, channelId, result.items), pages.put(owner, result.nextPage, Context(kept.sorts.offered)), kept.sorts.offered)
    }

    private fun later(channelId: String, tab: ChannelTab, owner: String, next: Page, context: Context): ChannelTabPage {
        val more = ChannelTabInfo.getMoreItems(service, handler(channelId, tab), next)
        NewPipeVideoSource.failIfBroken(more.items, more.errors)
        return ChannelTabPage(items(tab, channelId, more.items), pages.put(owner, more.nextPage, context), context.offered)
    }

    /**
     * Never hand out a list in the wrong order: the answer must be a sorted section (the body slot) and, when YouTube says
     * which chip is selected now, it must be the one asked for.
     */
    private fun checkAnswer(answer: String?, sort: ChannelSort) {
        val said = ChannelChips.inspectSorted(answer)
        if (!said.hasList) throw ParsingException("YouTube's answer for the $sort order holds no list")
        if (said.selected != null && said.selected != sort) throw ParsingException("YouTube answered in the ${said.selected} order, not $sort")
    }

    private fun handler(channelId: String, tab: ChannelTab): ListLinkHandler =
        service.channelTabLHFactory.fromQuery(NewPipeVideoSource.channelPath(channelId), listOf(tab.extractorName), "")

    /** What the section's items are for the app: videos (Shorts marked as such) or playlists (mixes left out). */
    private fun items(tab: ChannelTab, channelId: String, raw: List<InfoItem>): List<SourceItem> = when (tab) {
        ChannelTab.PLAYLISTS -> raw.filterIsInstance<PlaylistInfoItem>().mapNotNull(ItemMapper::toSourceItem)
        else -> {
            val owner = channelId.takeIf(YouTubeLinkParser::isChannelId)
            val videos = raw.filterIsInstance<StreamInfoItem>().mapNotNull { ItemMapper.toVideo(it, owner) }
            // Everything on the Shorts tab is a short, whatever each item says about itself.
            if (tab == ChannelTab.SHORTS) videos.map { it.copy(isShortForm = true) } else videos
        }
    }

    private fun channelNameOf(items: List<InfoItem>): String? =
        items.asSequence().filterIsInstance<StreamInfoItem>().mapNotNull { it.uploaderName?.takeIf(String::isNotBlank) }.firstOrNull()

    private fun sortUnavailable(tab: ChannelTab, sort: ChannelSort) =
        SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "SortUnavailable", "The $tab section does not offer the $sort order", null)

    private fun owner(channelId: String, tab: ChannelTab, sort: ChannelSort) = "channeltab:$channelId:${tab.name}:${sort.name}"

    private fun keptKey(channelId: String, tab: ChannelTab) = "$channelId:${tab.name}"

    internal companion object {
        /** NewPipeExtractor's own next pages carry the channel's verified state like this when they do not know it. */
        private const val UNKNOWN_VERIFIED = "UNKNOWN"

        /** Chips are kept this long (a section's first page was read this recently): the token of a sort is as good as new. */
        const val KEEP_MS = 15 * 60_000L

        private const val MAX_KEPT = 64

        /** The channel's sections as the extractor lists them ([ChannelTab.entries] order), at least the videos. */
        fun tabsOf(handlers: List<ListLinkHandler>?): List<ChannelTab> {
            val names = handlers.orEmpty().mapNotNull { it.contentFilters.firstOrNull() }.toSet()
            return ChannelTab.entries.filter { it.extractorName in names }.ifEmpty { listOf(ChannelTab.VIDEOS) }
        }
    }
}

/** The name NewPipeExtractor gives the tab. */
private val ChannelTab.extractorName: String
    get() = when (this) {
        ChannelTab.VIDEOS -> ExtractorTabs.VIDEOS
        ChannelTab.SHORTS -> ExtractorTabs.SHORTS
        ChannelTab.LIVE -> ExtractorTabs.LIVESTREAMS
        ChannelTab.PLAYLISTS -> ExtractorTabs.PLAYLISTS
    }
