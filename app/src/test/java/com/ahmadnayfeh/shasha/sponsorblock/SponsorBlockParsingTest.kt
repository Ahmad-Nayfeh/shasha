package com.ahmadnayfeh.shasha.sponsorblock

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** The privacy-preserving request and the local filtering of SponsorBlock's answer (plain JVM). */
class SponsorBlockParsingTest {

    @Test
    fun theHashPrefix_isTheFirstFourHexCharactersOfSha256() {
        assertThat(SponsorBlockClient.hashPrefix("lC3h3Lm3q54")).isEqualTo("a16c")
        // sha256("dQw4w9WgXcQ") = 5f7c3d…: lower case, always 4 characters.
        assertThat(SponsorBlockClient.hashPrefix("dQw4w9WgXcQ")).hasLength(4)
        assertThat(SponsorBlockClient.hashPrefix("dQw4w9WgXcQ")).matches("[0-9a-f]{4}")
    }

    @Test
    fun theRequest_namesOnlyThePrefix_everyCategory_andSkipAndMute() {
        val url = SponsorBlockClient(OkHttpClient()).requestUrl("lC3h3Lm3q54")
        assertThat(url.toString()).doesNotContain("lC3h3Lm3q54")
        assertThat(url.scheme).isEqualTo("https")
        assertThat(url.host).isEqualTo("sponsor.ajay.app")
        assertThat(url.encodedPath).isEqualTo("/api/skipSegments/a16c")
        assertThat(url.queryParameter("categories"))
            .isEqualTo("[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"preview\",\"hook\",\"filler\",\"music_offtopic\"]")
        assertThat(url.queryParameter("actionTypes")).isEqualTo("[\"skip\",\"mute\"]")
        assertThat(url.querySize).isEqualTo(2)
        // The brackets and quotes travel encoded.
        assertThat(url.encodedQuery).contains("%5B%22sponsor%22")

        val local = SponsorBlockClient(OkHttpClient(), "http://127.0.0.1:8080/".toHttpUrl()).requestUrl("lC3h3Lm3q54")
        assertThat(local.toString()).startsWith("http://127.0.0.1:8080/api/skipSegments/a16c?")
    }

    @Test
    fun theAnswerForThePrefix_isFilteredToThisVideo() {
        val segments = SponsorBlockClient.parseSegments(ANSWER, "lC3h3Lm3q54")
        assertThat(segments).containsExactly(
            SponsorSegment(
                id = "607daf4500d5b550943dc6858ca88c3859e160fad9bb6a26e88eee5825041007",
                category = SponsorCategory.SPONSOR,
                action = SegmentAction.SKIP,
                startMs = 119_513,
                endMs = 162_837,
                videoDurationMs = 465_000,
            ),
        )
    }

    @Test
    fun anotherVideoOfTheSamePrefix_hasItsOwnSegments_sortedByStart() {
        val segments = SponsorBlockClient.parseSegments(ANSWER, "_5YXQirYxUo")
        assertThat(segments.map { it.category }).containsExactly(SponsorCategory.INTRO, SponsorCategory.SELF_PROMO, SponsorCategory.FILLER).inOrder()
        assertThat(segments.map { it.startMs to it.endMs }).containsExactly(0L to 10_308L, 18_073L to 52_982L, 53_522L to 71_587L).inOrder()
        assertThat(segments.first().videoDurationMs).isEqualTo(446_561)
        assertThat(SponsorBlockClient.parseSegments(ANSWER, "notInTheAnswer")).isEmpty()
        // Video ids are case-sensitive.
        assertThat(SponsorBlockClient.parseSegments(ANSWER, "LC3H3LM3Q54")).isEmpty()
    }

    @Test
    fun entriesThisAppCannotUse_areLeftOutOneByOne() {
        val segments = SponsorBlockClient.parseSegments(BROKEN_ENTRIES, "abcdefghijk")
        assertThat(segments.map { it.id }).containsExactly("mute-ok", "no-duration", "skip-ok").inOrder()
        val mute = segments.first { it.id == "mute-ok" }
        assertThat(mute.action).isEqualTo(SegmentAction.MUTE)
        assertThat(segments.first { it.id == "no-duration" }.videoDurationMs).isEqualTo(0)
    }

    @Test
    fun anAnswerThatIsNotAList_isAFailure() {
        assertThrows(IOException::class.java) { SponsorBlockClient.parseSegments("<html>busy</html>", "lC3h3Lm3q54") }
        assertThrows(IOException::class.java) { SponsorBlockClient.parseSegments("{\"message\":\"x\"}", "lC3h3Lm3q54") }
        assertThat(SponsorBlockClient.parseSegments("[]", "lC3h3Lm3q54")).isEmpty()
    }

    @Test
    fun theCache_keepsSegmentsAsTheyWere_andForgetsKindsItDoesNotKnow() {
        val segments = SponsorBlockClient.parseSegments(ANSWER, "_5YXQirYxUo") +
            SponsorSegment("m", SponsorCategory.MUSIC_OFFTOPIC, SegmentAction.MUTE, 1, 2)
        val text = SegmentCache.encode(segments)
        assertThat(SegmentCache.decode(text)).isEqualTo(segments)
        assertThat(SegmentCache.decode("[]")).isEmpty()
        assertThat(SegmentCache.decode("not json")).isNull()
        assertThat(
            SegmentCache.decode(
                """[{"id":"x","category":"chapter","action":"skip","startMs":1,"endMs":2},""" +
                    """{"id":"y","category":"intro","action":"poi","startMs":1,"endMs":2},""" +
                    """{"id":"z","category":"intro","action":"skip","startMs":5,"endMs":9}]""",
            ),
        ).containsExactly(SponsorSegment("z", SponsorCategory.INTRO, SegmentAction.SKIP, 5, 9))
    }

    @Test
    fun theDefaultCategories_areTheSpecsFive() {
        assertThat(SponsorCategory.DEFAULTS).containsExactly(
            SponsorCategory.SPONSOR,
            SponsorCategory.SELF_PROMO,
            SponsorCategory.INTERACTION,
            SponsorCategory.INTRO,
            SponsorCategory.OUTRO,
        )
        assertThat(SponsorCategory.fromApiKey("music_offtopic")).isEqualTo(SponsorCategory.MUSIC_OFFTOPIC)
        assertThat(SponsorCategory.fromApiKey("poi_highlight")).isNull()
        assertThat(SponsorCategory.fromApiKey("exclusive_access")).isNull()
        assertThat(SponsorCategory.fromApiKey("chapter")).isNull()
    }

    private companion object {
        /** Part of the real answer for prefix a16c (2026-09-29): three of its ~140 videos. */
        val ANSWER = """
            [{"videoID":"WRKuBIJykIs","segments":[{"category":"intro","actionType":"skip","segment":[0,30.12],
              "UUID":"0adf84f921d30d29551845fc8da48ad9d47f3ad1ea22374b2682630c6847be287","videoDuration":2405.161,"locked":0,"votes":0,"description":""}]},
             {"videoID":"lC3h3Lm3q54","segments":[{"category":"sponsor","actionType":"skip","segment":[119.513,162.837],
              "UUID":"607daf4500d5b550943dc6858ca88c3859e160fad9bb6a26e88eee5825041007","videoDuration":465,"locked":1,"votes":22,"description":""}]},
             {"videoID":"_5YXQirYxUo","segments":[
              {"category":"filler","actionType":"skip","segment":[53.522,71.587],"UUID":"008aaf6dec1fc1c4dd83e1e35fdd50174b8b0b652f3552dddfe3c8ae0b30cc047","videoDuration":446.561,"locked":0,"votes":0,"description":""},
              {"category":"intro","actionType":"skip","segment":[0,10.308],"UUID":"5cfb3b9a24b2cb557c411b2fd7c68d6d368a63463a7bc3c6fef4095fabfbf59c7","videoDuration":446.561,"locked":0,"votes":0,"description":""},
              {"category":"selfpromo","actionType":"skip","segment":[18.073,52.982],"UUID":"fde70a4d34ea775a5e618b211cdf1917bd9467847cc4fd57ffbc9cbd92ac7d577","videoDuration":446.561,"locked":0,"votes":0,"description":""}]}]
        """.trimIndent()

        /** One usable entry of each kind, and every way an entry can be unusable. */
        val BROKEN_ENTRIES = """
            [{"videoID":"abcdefghijk","segments":[
              {"category":"sponsor","actionType":"skip","segment":[30,40],"UUID":"skip-ok","videoDuration":600},
              {"category":"sponsor","actionType":"mute","segment":[5,8.5],"UUID":"mute-ok","videoDuration":600},
              {"category":"selfpromo","actionType":"skip","segment":[12,20],"UUID":"no-duration","videoDuration":0},
              {"category":"poi_highlight","actionType":"poi","segment":[50,50],"UUID":"highlight"},
              {"category":"exclusive_access","actionType":"full","segment":[0,0],"UUID":"full"},
              {"category":"chapter","actionType":"chapter","segment":[0,100],"UUID":"chapter"},
              {"category":"somethingNew","actionType":"skip","segment":[1,2],"UUID":"new-category"},
              {"category":"sponsor","actionType":"skip","segment":[90],"UUID":"one-number"},
              {"category":"sponsor","actionType":"skip","segment":[90,80],"UUID":"backwards"},
              {"category":"sponsor","actionType":"skip","segment":[-5,3],"UUID":"negative"},
              {"category":"sponsor","actionType":"skip","segment":["1","2"],"UUID":"strings"},
              {"category":"sponsor","actionType":"skip","UUID":"no-range"},
              "not an object",
              {"category":"sponsor","actionType":"skip","segment":[30,40],"UUID":"skip-ok","videoDuration":600}
            ]}]
        """.trimIndent()
    }
}
