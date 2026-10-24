package com.ahmadnayfeh.shasha.update

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test

/** The public update manifest (latest.json on the download site): the TV app reads its own "tv" entry only. */
class UpdateManifestTest {

    private fun parse(body: String, from: String = MANIFEST) = UpdateManifest.parse(body, from.toHttpUrl())

    private fun refusal(body: String, from: String = MANIFEST): UpdateException =
        assertThrows(UpdateException::class.java) { parse(body, from) }

    @Test
    fun theTvEntry_asTheSiteScriptWritesIt() {
        val release = parse(manifest())
        assertThat(release).isEqualTo(
            ReleaseInfo(
                versionName = "1.8.0",
                versionCode = 1100,
                apk = ReleaseApk("https://ahmad-nayfeh.github.io/shasha/shasha-tv.apk?v=1100", 18_874_368, TV_SHA),
            ),
        )
    }

    @Test
    fun onlyItsOwnEntry_neverThePhones() {
        val phoneFirst = """{"mobile": ${entry(code = 2000, url = "shasha-mobile.apk", sha = PHONE_SHA)}, "tv": ${entry()}}"""
        val release = parse(phoneFirst)
        assertThat(release.versionCode).isEqualTo(1100)
        assertThat(release.apk.sha256).isEqualTo(TV_SHA)
    }

    @Test
    fun aRelativeAddress_isTheManifestsNeighbour_andTheHashIsReadInLowerCase() {
        val release = parse("""{"tv": ${entry(url = "shasha-tv.apk", sha = TV_SHA.uppercase())}}""")
        assertThat(release.apk.url).isEqualTo("https://ahmad-nayfeh.github.io/shasha/shasha-tv.apk")
        assertThat(release.apk.sha256).isEqualTo(TV_SHA)
    }

    @Test
    fun theSizeAndNotesAreOptional_andUnknownFieldsAreIgnored() {
        val release = parse(
            """{"schema": 1, "tv": {"versionCode": 1100, "versionName": "1.8.0", "url": "shasha-tv.apk", "sha256": "$TV_SHA",
               "minSdk": 25, "published": "2026-10-24"}}""",
        )
        assertThat(release.apk.size).isEqualTo(0)
    }

    @Test
    fun aManifestWithoutThisAppsEntry_isAVersionNotPublishedYet() {
        for (body in listOf("{}", """{"mobile": ${entry(sha = PHONE_SHA)}}""", """{"TV": ${entry()}}""")) {
            val e = refusal(body)
            assertThat(e.kind).isEqualTo(UpdateErrorKind.NO_APK)
        }
    }

    @Test
    fun brokenJson_isAnAnswerWeDoNotUnderstand() {
        for (body in listOf(
            "",
            "<html>404</html>",
            "{\"tv\": ",
            "[]",
            "\"tv\"",
            """{"tv": null}""",
            """{"tv": "1.8.0"}""",
            """{"tv": {"versionCode": "eleven hundred", "versionName": "1.8.0", "url": "a.apk", "sha256": "$TV_SHA"}}""",
        )) {
            val e = refusal(body)
            assertThat(e.kind).isEqualTo(UpdateErrorKind.BAD_ANSWER)
        }
    }

    @Test
    fun anEntryThatCannotBeUsed_isRefused() {
        val unusable = listOf(
            entry(code = 0),
            entry(code = -5),
            entry(code = 99_999_999_999),
            entry(name = ""),
            entry(name = "1.8.0/../../x"),
            entry(url = ""),
            entry(url = "http://ahmad-nayfeh.github.io/shasha/shasha-tv.apk"), // the manifest is https: never plain http
            entry(sha = ""),
            entry(sha = "abc123"),
            entry(sha = "z".repeat(64)),
            entry(size = -1),
            """{"versionName": "1.8.0", "url": "shasha-tv.apk", "sha256": "$TV_SHA"}""", // no versionCode
            """{"versionCode": 1100, "versionName": "1.8.0", "url": "shasha-tv.apk"}""", // no sha256
        )
        for (bad in unusable) {
            val e = refusal("""{"tv": $bad}""")
            assertThat(e.kind).isEqualTo(UpdateErrorKind.BAD_ANSWER)
        }
    }

    @Test
    fun onlyAHigherVersionCode_isNewer() {
        val release = parse(manifest())
        assertThat(release.isNewerThan(1088)).isTrue()
        assertThat(release.isNewerThan(1099)).isTrue()
        assertThat(release.isNewerThan(1100)).isFalse()
        assertThat(release.isNewerThan(1200)).isFalse()
    }

    private fun entry(
        code: Long = 1100,
        name: String = "1.8.0",
        url: String = "https://ahmad-nayfeh.github.io/shasha/shasha-tv.apk?v=1100",
        sha: String = TV_SHA,
        size: Long = 18_874_368,
    ) = """{"versionCode": $code, "versionName": "$name", "url": "$url", "sha256": "$sha", "size": $size, "notes": "شاشة $name"}"""

    private fun manifest() = """
        {"tv": ${entry()},
         "mobile": ${entry(code = 1100, url = "https://ahmad-nayfeh.github.io/shasha/shasha-mobile.apk?v=1100", sha = PHONE_SHA, size = 15_000_000)}}
    """.trimIndent()

    private companion object {
        const val MANIFEST = "https://ahmad-nayfeh.github.io/shasha/latest.json"
        val TV_SHA = "0123456789abcdef".repeat(4)
        val PHONE_SHA = "fedcba9876543210".repeat(4)
    }
}
