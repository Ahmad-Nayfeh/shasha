package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.ShortsPage
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * The Shorts of a search ([com.ahmadnayfeh.shasha.youtube.VideoSource.searchShorts]). Blocking: call inside
 * [NewPipeVideoSource]'s `io`.
 *
 * NewPipeExtractor cannot ask YouTube for Shorts alone and skips the Shorts shelves of the plain search, so this posts the
 * search itself, to the same InnerTube endpoint, with the same client and headers the extractor uses ([post]), with the type
 * filter «Shorts» ([ShortsAnswers.TYPE_FILTER]); [ShortsAnswers] reads what comes back. A next page is the token of the page's
 * `continuationItemRenderer`, posted as it is, and kept behind a [PageKey] like every other list's.
 *
 * An answer that is not a search at all (an error page, a layout nobody expected) fails like any extraction that cannot read
 * YouTube; one that is a search without Shorts is an empty page.
 */
internal class ShortsSearch(
    private val service: StreamingService,
    private val pages: PageStore,
    /** Posts a body to InnerTube's `search` and gives the answer's text; tests replace the HTTP. */
    private val post: (body: ByteArray) -> String = { body -> postToSearch(body) },
) {

    fun load(query: String, page: PageKey?): ShortsPage {
        val q = query.trim()
        if (q.isEmpty()) return ShortsPage(emptyList(), null)
        val owner = owner(q)
        val language = service.localization.localizationCode
        val country = service.contentCountry.countryCode
        val clientVersion = YoutubeParsingHelper.getClientVersion()
        val body = if (page == null) {
            InnerTubeBody.build(language, country, clientVersion) {
                put("query", q)
                put("params", ShortsAnswers.TYPE_FILTER)
            }
        } else {
            // An unknown or expired key ends the list instead of repeating the first page.
            val (next, _) = pages.get(owner, page) ?: return ShortsPage(emptyList(), null)
            InnerTubeBody.build(language, country, clientVersion) { put("continuation", checkNotNull(next.id)) }
        }
        val parsed = ShortsAnswers.parse(post(body))
        if (!parsed.looksLikeASearch) throw ParsingException("YouTube's answer to the Shorts search is not a search's")
        return ShortsPage(parsed.shorts, parsed.next?.let { pages.put(owner, Page(SEARCH_URL, it)) })
    }

    private fun owner(query: String) = "shorts:$query"

    internal companion object {
        private const val SEARCH_URL = YoutubeParsingHelper.YOUTUBEI_V1_URL + "search?" + YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER

        /** What the extractor's own search request is: the same URL, headers and localization. */
        fun postToSearch(body: ByteArray): String {
            val response = NewPipe.getDownloader().postWithContentTypeJson(
                SEARCH_URL,
                YoutubeParsingHelper.getYouTubeHeaders(),
                body,
                NewPipe.getPreferredLocalization(),
            )
            return YoutubeParsingHelper.getValidJsonResponseBody(response)
        }
    }
}
