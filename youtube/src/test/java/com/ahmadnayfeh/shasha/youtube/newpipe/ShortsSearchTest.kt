package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.PageKey
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * The Shorts of a search, request by request, offline: what is posted to YouTube (the query with the Shorts type filter; a
 * page's token), what comes of its answer (the Shorts, and a key for the next page), and what happens to a key that is not known,
 * an empty query and an answer that is not a search. YouTube's answers are copies of the real ones (`src/test/resources/shorts`);
 * what the real YouTube answers is checked by RealNetworkShortsSearchTest.
 */
class ShortsSearchTest {

    private val posted = ArrayList<kotlinx.serialization.json.JsonObject>()
    private var answers = ArrayDeque<String>()
    private val pages = PageStore()
    private val search = ShortsSearch(ServiceList.YouTube, pages) { body ->
        posted += Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        answers.removeFirst()
    }

    private var previousDownloader: Downloader? = null
    private var previousLocalization: Localization? = null
    private var previousCountry: ContentCountry? = null

    @Before
    fun fakeYouTube() {
        // NewPipe's state is global to the process: borrow it, and give it back. All it must answer is the client version.
        previousDownloader = NewPipe.getDownloader()
        previousLocalization = runCatching { NewPipe.getPreferredLocalization() }.getOrNull()
        previousCountry = runCatching { NewPipe.getPreferredContentCountry() }.getOrNull()
        NewPipe.init(
            object : Downloader() {
                override fun execute(request: Request): Response {
                    val url = request.url()
                    check(url.endsWith("/sw.js")) { "This fake YouTube does not know $url" }
                    return Response(200, "OK", emptyMap(), """"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20261002.01.00"""", url)
                }
            },
            Localization("ar", "SA"),
            ContentCountry("SA"),
        )
    }

    @After
    fun giveNewPipeBack() {
        val downloader = previousDownloader ?: return
        NewPipe.init(downloader, previousLocalization ?: Localization("ar", "SA"), previousCountry ?: ContentCountry("SA"))
    }

    private fun answer(name: String): String = checkNotNull(javaClass.getResource("/shorts/$name")).readText(Charsets.UTF_8)

    @Test
    fun theFirstPageAsksForShortsAlone_withTheWebClientAndTheQuery() {
        answers += answer("typed_first.json")

        val page = search.load("  minecraft ", null)

        assertThat(page.shorts).hasSize(6)
        assertThat(page.next).isNotNull()
        val request = posted.single()
        assertThat(request.getValue("query").jsonPrimitive.content).isEqualTo("minecraft")
        assertThat(request.getValue("params").jsonPrimitive.content).isEqualTo(ShortsAnswers.TYPE_FILTER)
        val client = request.getValue("context").jsonObject.getValue("client").jsonObject
        assertThat(client.getValue("clientName").jsonPrimitive.content).isEqualTo("WEB")
        assertThat(client.getValue("clientVersion").jsonPrimitive.content).isEqualTo("2.20261002.01.00")
        assertThat(request).doesNotContainKey("continuation")
    }

    @Test
    fun theNextPagePostsThePagesToken_andItsEndHasNoKey() {
        answers += answer("typed_first.json")
        answers += answer("typed_none.json") // no more to say: a search without a next token
        val first = search.load("minecraft", null)

        val second = search.load("minecraft", first.next)

        assertThat(posted).hasSize(2)
        val continuation = posted[1]
        assertThat(continuation.getValue("continuation").jsonPrimitive.content).isEqualTo("EjESCW1pbmVjcmFmdBokRWdJUUNVZ1VzZ0VHQ2dRSUFoQUM2")
        assertThat(continuation).doesNotContainKey("query")
        assertThat(second.shorts).isEmpty()
        assertThat(second.next).isNull()
    }

    @Test
    fun aKeyBelongsToTheQueryThatReturnedIt_anUnknownOneEndsTheList() {
        answers += answer("typed_first.json")
        val first = search.load("minecraft", null)
        posted.clear()

        val other = search.load("roblox", first.next)
        val unknown = search.load("minecraft", PageKey("never-issued"))

        assertThat(other.shorts).isEmpty()
        assertThat(other.next).isNull()
        assertThat(unknown.shorts).isEmpty()
        assertThat(unknown.next).isNull()
        // YouTube is not asked for either: the list simply ends instead of repeating the first page.
        assertThat(posted).isEmpty()
    }

    @Test
    fun anEmptyQueryAsksNothing() {
        val page = search.load("   ", null)

        assertThat(page.shorts).isEmpty()
        assertThat(page.next).isNull()
        assertThat(posted).isEmpty()
    }

    @Test
    fun aSearchWithoutShortsIsAnEmptyPage_whileAnAnswerThatIsNotASearchFails() {
        answers += answer("typed_none.json")
        assertThat(search.load("qzkxjvbnwyq", null).shorts).isEmpty()

        answers += """{"error":{"code":400,"message":"Request contains an invalid argument."}}"""
        assertThrows(ParsingException::class.java) { search.load("minecraft", null) }
    }

    @Test
    fun theKeysOfDifferentPagesAreNotTheSame() {
        answers += answer("typed_first.json")
        answers += answer("typed_next.json")
        val first = search.load("minecraft", null)

        val second = search.load("minecraft", first.next)

        assertThat(second.next).isNotNull()
        assertThat(second.next).isNotEqualTo(first.next)
        assertThat(second.shorts.map { it.videoId }).contains("CtkbksrylwY")
    }
}
