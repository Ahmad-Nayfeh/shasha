package com.ahmadnayfeh.shasha.update

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * GitHub's "latest release" answer, as CI publishes Shasha (android.yml): tag v<name>-<code>, the TV's APK
 * shasha-<name>-<code>.apk and, from v1.2, the phone app's shasha-mobile-<name>-<code>.apk beside it.
 */
class ReleaseParserTest {

    @Test
    fun aReleaseAsCiPublishesIt() {
        val release = ReleaseParser.parse(release())!!
        assertThat(release.tag).isEqualTo("v1.0.0-1042")
        assertThat(release.versionName).isEqualTo("1.0.0")
        assertThat(release.versionCode).isEqualTo(1042)
        assertThat(release.apk).isEqualTo(
            ReleaseAsset(id = 7, name = "shasha-1.0.0-1042.apk", size = 18_874_368, apiUrl = "https://api.github.com/repos/Ahmad-Nayfeh/shasha/releases/assets/7"),
        )
    }

    @Test
    fun tags() {
        assertThat(ReleaseTag.parse("v1.0.0-1042")).isEqualTo(ReleaseTag("1.0.0", 1042))
        assertThat(ReleaseTag.parse(" v2.3-7 ")).isEqualTo(ReleaseTag("2.3", 7))
        // A versionName with a dash: the last "-<digits>" is the build number.
        assertThat(ReleaseTag.parse("v1.1.0-rc1-1050")).isEqualTo(ReleaseTag("1.1.0-rc1", 1050))
        for (bad in listOf("1.0.0-1042", "v1.0.0", "v1.0.0-", "v-12", "latest", "v1.0.0-12a", "v1.0.0-0", "v1.0.0-99999999999", "")) {
            assertThat(ReleaseTag.parse(bad)).isNull()
        }
        val e = assertThrows(UpdateException::class.java) { ReleaseParser.parse(release(tag = "nightly")) }
        assertThat(e.kind).isEqualTo(UpdateErrorKind.BAD_TAG)
        assertThat(e.detail).isEqualTo("nightly")
    }

    @Test
    fun theApkIsFoundAmongTheAssets() {
        val assets = listOf(
            asset(1, "release-notes.txt"),
            asset(2, "shasha-1.0.0-1042-debug.apk"),
            asset(3, "shasha-1.0.0-1042.apk"),
        )
        assertThat(ReleaseParser.parse(release(assets = assets))!!.apk.id).isEqualTo(3)
        // A release made by hand under another name still counts, a debug build never does.
        assertThat(ReleaseParser.parse(release(assets = listOf(asset(4, "Shasha.APK"))))!!.apk.id).isEqualTo(4)
    }

    @Test
    fun thePhoneAppsApk_isNeverTheTvUpdate_inWhateverOrder() {
        val phone = asset(5, "shasha-mobile-1.0.0-1042.apk")
        val tv = asset(6, "shasha-1.0.0-1042.apk")
        assertThat(ReleaseParser.parse(release(assets = listOf(phone, tv)))!!.apk.name).isEqualTo("shasha-1.0.0-1042.apk")
        assertThat(ReleaseParser.parse(release(assets = listOf(tv, phone)))!!.apk.name).isEqualTo("shasha-1.0.0-1042.apk")
        assertThat(ReleaseParser.parse(release(assets = listOf(phone, tv)))!!.apk.id).isEqualTo(6)
        // Not the phone's own version either, nor in capitals.
        val laterPhone = asset(7, "shasha-mobile-1.1.0-1050.apk")
        val shouting = asset(8, "Shasha-Mobile-1.0.0-1042.APK")
        assertThat(ReleaseParser.parse(release(assets = listOf(laterPhone, shouting, tv)))!!.apk.id).isEqualTo(6)
    }

    @Test
    fun withoutTheTvsApk_thePhoneAppsApkIsNotAFallback() {
        val phone = asset(5, "shasha-mobile-1.0.0-1042.apk")
        val notThere = listOf(
            listOf(phone),
            // The TV's APK is still being uploaded, or only a debug build came.
            listOf(phone, asset(6, "shasha-1.0.0-1042.apk", state = "new")),
            listOf(phone, asset(7, "shasha-1.0.0-1042-debug.apk")),
            listOf(asset(8, "shasha-mobile-1.0.0-1042-debug.apk")),
        )
        for (assets in notThere) {
            val e = assertThrows(UpdateException::class.java) { ReleaseParser.parse(release(assets = assets)) }
            assertThat(e.kind).isEqualTo(UpdateErrorKind.NO_APK)
        }
    }

    @Test
    fun aHandNamedTvApk_besideThePhoneAppsApk_isTheOneTaken() {
        val phone = asset(5, "shasha-mobile-1.0.0-1042.apk")
        assertThat(ReleaseParser.parse(release(assets = listOf(phone, asset(9, "Shasha.apk"))))!!.apk.id).isEqualTo(9)
        assertThat(ReleaseParser.parse(release(assets = listOf(asset(9, "Shasha.apk"), phone)))!!.apk.id).isEqualTo(9)
    }

    @Test
    fun aReleaseWithoutAnApk_isNoApk() {
        for (assets in listOf(emptyList(), listOf(asset(1, "notes.txt")), listOf(asset(2, "shasha-1.0.0-1042-debug.apk")), listOf(asset(3, "shasha-1.0.0-1042.apk", state = "new")))) {
            val e = assertThrows(UpdateException::class.java) { ReleaseParser.parse(release(assets = assets)) }
            assertThat(e.kind).isEqualTo(UpdateErrorKind.NO_APK)
        }
    }

    @Test
    fun draftsAndPrereleasesAreIgnored() {
        assertThat(ReleaseParser.parse(release(prerelease = true))).isNull()
        assertThat(ReleaseParser.parse(release(draft = true))).isNull()
    }

    @Test
    fun whatIsNotAReleaseIsABadAnswer() {
        for (body in listOf("", "<html>rate limited</html>", "[]", """{"tag_name": 12, "assets": "none"}""", """{"assets": []}""")) {
            val e = assertThrows(UpdateException::class.java) { ReleaseParser.parse(body) }
            assertThat(e.kind).isEqualTo(UpdateErrorKind.BAD_ANSWER)
        }
    }

    @Test
    fun onlyAHigherVersionCodeIsAnUpdate() {
        val release = ReleaseParser.parse(release())!!
        assertThat(release.isNewerThan(1041)).isTrue()
        assertThat(release.isNewerThan(1)).isTrue() // a local build (versionCode 1)
        assertThat(release.isNewerThan(1042)).isFalse()
        assertThat(release.isNewerThan(1043)).isFalse() // never a downgrade
    }

    private data class Asset(val id: Long, val name: String, val state: String)

    private fun asset(id: Long, name: String, state: String = "uploaded") = Asset(id, name, state)

    private fun release(
        tag: String = "v1.0.0-1042",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<Asset> = listOf(Asset(7, "shasha-1.0.0-1042.apk", "uploaded")),
    ): String {
        val assetJson = assets.joinToString(",") { a ->
            """{"id": ${a.id}, "name": "${a.name}", "size": 18874368, "state": "${a.state}", "content_type": "application/vnd.android.package-archive",
               "url": "https://api.github.com/repos/Ahmad-Nayfeh/shasha/releases/assets/${a.id}",
               "browser_download_url": "https://github.com/Ahmad-Nayfeh/shasha/releases/download/$tag/${a.name}"}"""
        }
        return """{"url": "https://api.github.com/repos/Ahmad-Nayfeh/shasha/releases/1", "tag_name": "$tag", "name": "شاشة",
            "draft": $draft, "prerelease": $prerelease, "published_at": "2026-09-29T20:00:00Z", "author": {"login": "github-actions[bot]"},
            "body": ${JsonPrimitive(NOTES)}, "assets": [$assetJson]}"""
    }

    private companion object {
        /** What the release job writes (android.yml). */
        val NOTES = """
            ## شاشة 1.0.0 (1042)

            - **NewPipeExtractor:** `v0.26.5`
            - **Commit:** [`3f9a2c1`](https://github.com/Ahmad-Nayfeh/shasha/commit/3f9a2c1) Final phase: updater
            - **Build:** [Android #42](https://github.com/Ahmad-Nayfeh/shasha/actions/runs/1) (push)



            التثبيت: زر «فحص التحديثات» في التطبيق.
        """.trimIndent().replace("\n", "\r\n")
    }
}
