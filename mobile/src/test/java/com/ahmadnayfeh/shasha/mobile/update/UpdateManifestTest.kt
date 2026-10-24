package com.ahmadnayfeh.shasha.mobile.update

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test

/** The public update manifest (latest.json on the download site): the phone app reads its own "mobile" entry only. */
class UpdateManifestTest {

    private fun parse(body: String, from: String = MANIFEST) = UpdateManifest.parse(body, from.toHttpUrl())

    private fun refusal(body: String, from: String = MANIFEST): UpdateException =
        assertThrows(UpdateException::class.java) { parse(body, from) }

    @Test
    fun thePhoneEntry_asTheSiteScriptWritesIt() {
        val release = parse(manifest())
        assertThat(release).isEqualTo(
            ReleaseInfo(
                versionName = "1.8.0",
                versionCode = 1100,
                notes = "شاشة 1.8.0",
                apk = ReleaseApk("https://ahmad-nayfeh.github.io/shasha/shasha-mobile.apk?v=1100", 18_874_368, PHONE_SHA),
            ),
        )
    }

    @Test
    fun onlyItsOwnEntry_neverTheTvs() {
        val tvFirst = """{"tv": ${entry(code = 2000, url = "shasha-tv.apk", sha = TV_SHA)}, "mobile": ${entry()}}"""
        val release = parse(tvFirst)
        assertThat(release.versionCode).isEqualTo(1100)
        assertThat(release.apk.sha256).isEqualTo(PHONE_SHA)
    }

    @Test
    fun aRelativeAddress_isTheManifestsNeighbour_andTheHashIsReadInLowerCase() {
        val release = parse("""{"mobile": ${entry(url = "shasha-mobile.apk", sha = PHONE_SHA.uppercase())}}""")
        assertThat(release.apk.url).isEqualTo("https://ahmad-nayfeh.github.io/shasha/shasha-mobile.apk")
        assertThat(release.apk.sha256).isEqualTo(PHONE_SHA)
    }

    @Test
    fun theSizeAndNotesAreOptional_andUnknownFieldsAreIgnored() {
        val release = parse(
            """{"schema": 1, "mobile": {"versionCode": 1100, "versionName": "1.8.0", "url": "shasha-mobile.apk", "sha256": "$PHONE_SHA",
               "minSdk": 25, "published": "2026-10-24"}}""",
        )
        assertThat(release.apk.size).isEqualTo(0)
        assertThat(release.notes).isEmpty()
    }

    @Test
    fun aManifestWithoutThisAppsEntry_isAVersionNotPublishedYet() {
        for (body in listOf("{}", """{"tv": ${entry(sha = TV_SHA)}}""", """{"MOBILE": ${entry()}}""")) {
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
            """{"mobile": null}""",
            """{"mobile": "1.8.0"}""",
            """{"mobile": {"versionCode": "eleven hundred", "versionName": "1.8.0", "url": "a.apk", "sha256": "$PHONE_SHA"}}""",
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
            entry(url = "http://ahmad-nayfeh.github.io/shasha/shasha-mobile.apk"), // the manifest is https: never plain http
            entry(sha = ""),
            entry(sha = "abc123"),
            entry(sha = "z".repeat(64)),
            entry(size = -1),
            """{"versionName": "1.8.0", "url": "shasha-mobile.apk", "sha256": "$PHONE_SHA"}""", // no versionCode
            """{"versionCode": 1100, "versionName": "1.8.0", "url": "shasha-mobile.apk"}""", // no sha256
        )
        for (bad in unusable) {
            val e = refusal("""{"mobile": $bad}""")
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
        url: String = "https://ahmad-nayfeh.github.io/shasha/shasha-mobile.apk?v=1100",
        sha: String = PHONE_SHA,
        size: Long = 18_874_368,
    ) = """{"versionCode": $code, "versionName": "$name", "url": "$url", "sha256": "$sha", "size": $size, "notes": "شاشة $name"}"""

    private fun manifest() = """
        {"mobile": ${entry()},
         "tv": ${entry(code = 1100, url = "https://ahmad-nayfeh.github.io/shasha/shasha-tv.apk?v=1100", sha = TV_SHA, size = 15_000_000)}}
    """.trimIndent()

    private companion object {
        const val MANIFEST = "https://ahmad-nayfeh.github.io/shasha/latest.json"
        val PHONE_SHA = "0123456789abcdef".repeat(4)
        val TV_SHA = "fedcba9876543210".repeat(4)
    }
}
