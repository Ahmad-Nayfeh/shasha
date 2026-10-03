package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Shorts in YouTube's answers to a search, read from trimmed copies of real answers (captured 2026-10, in
 * `src/test/resources/shorts`): the search for Shorts alone (two shelves of Shorts, the next page's token), its next page (which
 * repeats a Short of the first), a search that finds none, the plain search, whose Shorts shelf sits between its videos, and the
 * search for Shorts alone as it answers some queries (Arabic ones): not shelves but video results that open the Shorts player.
 */
class ShortsAnswersTest {

    private fun answer(name: String): String =
        checkNotNull(javaClass.getResource("/shorts/$name")) { "no fixture $name" }.readText(Charsets.UTF_8)

    @Test
    fun aSearchForShortsAloneGivesItsShortsInOrderFromBothShelves() {
        val parsed = ShortsAnswers.parse(answer("typed_first.json"))

        assertThat(parsed.looksLikeASearch).isTrue()
        assertThat(parsed.shorts.map { it.videoId }).containsExactly(
            "COFRdtIcLzc", "aBR8xrg2YXs", "-UEw591X_RY", "4y9gMpJ3R1c", "kf94_VF0ZeQ", "e96oPMyZcfY",
        ).inOrder()
        assertThat(parsed.next).isEqualTo("EjESCW1pbmVjcmFmdBokRWdJUUNVZ1VzZ0VHQ2dRSUFoQUM2")
    }

    @Test
    fun aShortHasWhatTheSearchSaysOfIt_andNothingMore() {
        val short = ShortsAnswers.parse(answer("typed_first.json")).shorts.first()

        assertThat(short.title).isEqualTo("Minecraft, But You Have to Break One Color…")
        assertThat(short.url).isEqualTo("https://www.youtube.com/shorts/COFRdtIcLzc")
        assertThat(short.viewCount).isEqualTo(24_000L)
        assertThat(short.thumbnailUrl).startsWith("https://i.ytimg.com/vi/COFRdtIcLzc/hq720.jpg")
        assertThat(short.isShortForm).isTrue()
        assertThat(short.isLive).isFalse()
        // YouTube does not say who made it, how long it is or when.
        assertThat(short.channelId).isNull()
        assertThat(short.channelName).isNull()
        assertThat(short.durationSeconds).isNull()
        assertThat(short.publishedAtEpochMs).isNull()
        // A title that is long and has hashtags stays whole.
        assertThat(ShortsAnswers.parse(answer("typed_first.json")).shorts[2].title)
            .isEqualTo("How to make a water lift elevator in Minecraft super easy and simple #minecraft #elevator #system")
    }

    @Test
    fun aNextPageIsReadFromItsAppendedItems_andIsHeldToTheSameShortsOnce() {
        val next = ShortsAnswers.parse(answer("typed_next.json"))

        assertThat(next.looksLikeASearch).isTrue()
        // The Short that came again (it was on the first page too) is listed once here: a list drops it by its id.
        assertThat(next.shorts.map { it.videoId }).containsExactly("CtkbksrylwY", "R7HjcKK1G_o", "COFRdtIcLzc", "StRpPVS2IwA", "TAoMPnHfhbQ").inOrder()
        assertThat(next.shorts.single { it.videoId == "CtkbksrylwY" }.viewCount).isEqualTo(120_000_000L)
        assertThat(next.next).isNotNull()
    }

    @Test
    fun someQueriesAnswerWithVideoResultsThatAreShorts_withTheirChannelAndLength() {
        val parsed = ShortsAnswers.parse(answer("typed_videos.json"))

        assertThat(parsed.looksLikeASearch).isTrue()
        assertThat(parsed.shorts.map { it.videoId }).containsExactly("3eEUdq6uG1E", "vIp7_iYqB4I", "u8p9ch9GNLw", "WKza5VCMnmI").inOrder()
        assertThat(parsed.next).isNotNull()
        val first = parsed.shorts.first()
        assertThat(first.title).startsWith("فوضى القرد")
        assertThat(first.channelId).isEqualTo("UCJhkCFmxN_PORk2cIIdK6Og")
        assertThat(first.channelName).isEqualTo("MagiclightAI")
        assertThat(first.durationSeconds).isEqualTo(19L)
        assertThat(first.viewCount).isEqualTo(263_131_200L)
        assertThat(first.isShortForm).isTrue()
        assertThat(first.url).isEqualTo("https://www.youtube.com/shorts/3eEUdq6uG1E")
        // The Short's own vertical frame, not the 16:9 thumbnail.
        assertThat(first.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/3eEUdq6uG1E/frame0.jpg")
        assertThat(parsed.shorts[1].channelName).isEqualTo("Sha3er El3atfa شاعر العاطفة")
    }

    @Test
    fun aPlainVideoResultIsNotAShort() {
        val answer = """
            {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"videoRenderer":{"videoId":"plainvideo1","title":{"runs":[{"text":"A talk"}]},"lengthText":{"simpleText":"12:30"},
                  "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=plainvideo1","webPageType":"WEB_PAGE_TYPE_WATCH"}},"watchEndpoint":{"videoId":"plainvideo1"}}}},
                {"videoRenderer":{"videoId":"urlonly0001","title":{"simpleText":"Told by its url"},
                  "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/shorts/urlonly0001"}}}}}
              ]}}]}}}}}
        """.trimIndent()

        val parsed = ShortsAnswers.parse(answer)

        assertThat(parsed.shorts.map { it.videoId }).containsExactly("urlonly0001")
        assertThat(parsed.shorts.single().title).isEqualTo("Told by its url")
        assertThat(parsed.shorts.single().channelId).isNull()
    }

    @Test
    fun aSearchThatFindsNoShortsIsAnEmptyListNotAFailure() {
        val parsed = ShortsAnswers.parse(answer("typed_none.json"))

        assertThat(parsed.looksLikeASearch).isTrue()
        assertThat(parsed.shorts).isEmpty()
        assertThat(parsed.next).isNull()
    }

    @Test
    fun thePlainSearchsShortsShelfIsReadToo_whereverTheShelfSits() {
        val parsed = ShortsAnswers.parse(answer("plain_first.json"))

        // Its videos are the extractor's: only the Shorts of the shelf come out, and the page's own token.
        assertThat(parsed.shorts.map { it.videoId }).containsExactly("COFRdtIcLzc", "ajq2GnPQWv8", "-UEw591X_RY").inOrder()
        assertThat(parsed.next).isNotNull()
    }

    @Test
    fun anOlderShapeOfAShortIsRead_areelItemRenderer() {
        val answer = """
            {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[{"reelShelfRenderer":{"items":[
                {"reelItemRenderer":{"videoId":"reelvideo01","headline":{"simpleText":"An older Short"},
                  "viewCountText":{"simpleText":"1,234 views"},
                  "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/reelvideo01/small.jpg","width":100,"height":180},{"url":"https://i.ytimg.com/vi/reelvideo01/big.jpg","width":405,"height":720}]}}}
              ]}}]}}]}}}}}
        """.trimIndent()

        val short = ShortsAnswers.parse(answer).shorts.single()

        assertThat(short.videoId).isEqualTo("reelvideo01")
        assertThat(short.title).isEqualTo("An older Short")
        assertThat(short.viewCount).isEqualTo(1_234L)
        assertThat(short.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/reelvideo01/big.jpg")
        assertThat(short.isShortForm).isTrue()
    }

    @Test
    fun aShortWithoutAReadableId_orWithADamagedTitle_isHandledNotCrashed() {
        val answer = """
            {"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[{"itemSectionRenderer":{"contents":[{"gridShelfViewModel":{"contents":[
              {"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"bad id"}}}}},
              {"shortsLockupViewModel":{"entityId":"shorts-shelf-item-viaentity01","accessibilityText":"A title, with a comma, 2 thousand views – play Short"}},
              {"shortsLockupViewModel":{}},
              {"shortsLockupViewModel":"not an object"}
            ]}}]}}]}}]}
        """.trimIndent()

        val parsed = ShortsAnswers.parse(answer)

        // The id comes from the entity when the tap has none; the title from the spoken text, without its views.
        assertThat(parsed.shorts.map { it.videoId }).containsExactly("viaentity01")
        assertThat(parsed.shorts.single().title).isEqualTo("A title, with a comma")
        assertThat(parsed.shorts.single().thumbnailUrl).isNull()
        assertThat(parsed.shorts.single().viewCount).isNull()
    }

    @Test
    fun anAnswerThatIsNotASearchIsSaidSo() {
        for (text in listOf(null, "", "   ", "not json", "[]", "{}", """{"error":{"code":403,"message":"Forbidden"}}""", """{"contents":{"other":{}}}""")) {
            val parsed = ShortsAnswers.parse(text)
            assertThat(parsed.looksLikeASearch).isFalse()
            assertThat(parsed.shorts).isEmpty()
            assertThat(parsed.next).isNull()
        }
    }

    @Test
    fun lengthsAreReadFromYouTubesClock() {
        val cases = mapOf("0:19" to 19L, "0:06" to 6L, "1:02" to 62L, "10:00" to 600L, "1:02:03" to 3_723L, " 0:41 " to 41L)
        for ((text, seconds) in cases) assertThat(ShortsAnswers.secondsOf(text)).isEqualTo(seconds)
        for (text in listOf(null, "", "19", "LIVE", "a:b", "1:2:3:4")) assertThat(ShortsAnswers.secondsOf(text)).isNull()
    }

    @Test
    fun viewsAreReadFromYouTubesAbbreviations() {
        val cases = mapOf(
            "23k views" to 23_000L,
            "1.2M views" to 1_200_000L,
            "120m views" to 120_000_000L,
            "3.5B views" to 3_500_000_000L,
            "1,234 views" to 1_234L,
            "1,234,567 views" to 1_234_567L,
            "987 views" to 987L,
            "1 view" to 1L,
            "No views" to 0L,
            "  12K views  " to 12_000L,
        )
        for ((text, views) in cases) assertThat(ShortsAnswers.viewsOf(text)).isEqualTo(views)
        for (text in listOf(null, "", "views", "Members only ✪")) assertThat(ShortsAnswers.viewsOf(text)).isNull()
    }
}
