package com.ahmadnayfeh.shasha.mobile.update

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Which asset of a release is the phone's. A release holds both apps, tagged with the TV app's version; the phone's file is
 * `shasha-mobile-<versionName>-<versionCode>.apk` and its version is read from that name.
 */
class ReleaseParserTest {

    private fun asset(id: Long, name: String, state: String = "uploaded", size: Long = 5_000_000) =
        """{"id":$id,"name":"$name","size":$size,"state":"$state","url":"https://api.example/repos/o/r/releases/assets/$id"}"""

    private fun release(vararg assets: String, tag: String = "v1.1.1-1042", draft: Boolean = false, prerelease: Boolean = false, body: String = "") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":${"\"" + body.replace("\n", "\\n") + "\""},"assets":[${assets.joinToString(",")}]}"""

    @Test
    fun thePhonesApkIsPickedAmongBoth() {
        val info = ReleaseParser.parse(
            release(asset(1, "shasha-1.1.1-1042.apk"), asset(2, "shasha-mobile-1.2.0-1042.apk")),
        )!!

        assertThat(info.apk.name).isEqualTo("shasha-mobile-1.2.0-1042.apk")
        assertThat(info.apk.id).isEqualTo(2)
        assertThat(info.apk.apiUrl).isEqualTo("https://api.example/repos/o/r/releases/assets/2")
        // The version is the phone's own, not the TV's from the tag.
        assertThat(info.versionName).isEqualTo("1.2.0")
        assertThat(info.versionCode).isEqualTo(1042)
        assertThat(info.tag).isEqualTo("v1.1.1-1042")
    }

    @Test
    fun theTvsApkAloneIsNothingForThePhone() {
        assertThat(ReleaseParser.parse(release(asset(1, "shasha-1.1.1-1042.apk")))).isNull()
    }

    @Test
    fun aReleaseWithoutAnyApkIsNothingToOffer() {
        assertThat(ReleaseParser.parse(release())).isNull()
        assertThat(ReleaseParser.parse(release(asset(1, "notes.txt")))).isNull()
    }

    @Test
    fun aDebugBuildIsNeverAnUpdateOfAReleaseSignedApp() {
        assertThat(ReleaseParser.parse(release(asset(1, "shasha-mobile-1.2.0-1042-debug.apk")))).isNull()
    }

    @Test
    fun anUnfinishedUploadIsNotOffered() {
        assertThat(ReleaseParser.parse(release(asset(1, "shasha-mobile-1.2.0-1042.apk", state = "new")))).isNull()
    }

    @Test
    fun aVersionNameMayHaveDashes() {
        val info = ReleaseParser.parse(release(asset(1, "shasha-mobile-1.3.0-rc1-2001.apk")))!!

        assertThat(info.versionName).isEqualTo("1.3.0-rc1")
        assertThat(info.versionCode).isEqualTo(2001)
    }

    @Test
    fun theNewestOfSeveralPhoneApksWins() {
        val info = ReleaseParser.parse(
            release(asset(1, "shasha-mobile-1.2.0-1042.apk"), asset(2, "shasha-mobile-1.2.1-1100.apk")),
        )!!

        assertThat(info.versionCode).isEqualTo(1100)
    }

    @Test
    fun draftsAndPrereleasesAreNotOffered() {
        assertThat(ReleaseParser.parse(release(asset(1, "shasha-mobile-1.2.0-1042.apk"), draft = true))).isNull()
        assertThat(ReleaseParser.parse(release(asset(1, "shasha-mobile-1.2.0-1042.apk"), prerelease = true))).isNull()
    }

    @Test
    fun aBodyThatIsNotAReleaseIsABadAnswer() {
        assertThat(assertThrows(UpdateException::class.java) { ReleaseParser.parse("<html>") }.kind).isEqualTo(UpdateErrorKind.BAD_ANSWER)
        assertThat(assertThrows(UpdateException::class.java) { ReleaseParser.parse("""{"draft":false,"assets":[]}""") }.kind)
            .isEqualTo(UpdateErrorKind.BAD_ANSWER)
    }

    @Test
    fun onlyNewerReleasesAreOffered() {
        val info = ReleaseParser.parse(release(asset(1, "shasha-mobile-1.2.0-1042.apk")))!!

        assertThat(info.isNewerThan(1041)).isTrue()
        assertThat(info.isNewerThan(1042)).isFalse()
        assertThat(info.isNewerThan(2000)).isFalse()
    }

    @Test
    fun theNotesAreMadePlain() {
        val info = ReleaseParser.parse(
            release(asset(1, "shasha-mobile-1.2.0-1042.apk"), body = "## Shasha 1.2.0\n\n- **New:** [details](https://x.example) `code`"),
        )!!

        assertThat(info.notes).isEqualTo("Shasha 1.2.0\n\n• New: details code")
    }

    @Test
    fun assetNamesAreReadAndWrittenTheSameWay() {
        assertThat(MobileAssetName.parse("shasha-mobile-1.2.0-1042.apk")).isEqualTo(MobileAssetName("1.2.0", 1042))
        assertThat(MobileAssetName.of("1.2.0", 1042)).isEqualTo("shasha-mobile-1.2.0-1042.apk")
        assertThat(MobileAssetName.parse("shasha-1.1.1-1042.apk")).isNull()
        assertThat(MobileAssetName.parse("shasha-mobile-1.2.0.apk")).isNull()
        assertThat(MobileAssetName.parse("shasha-mobile-1.2.0-0.apk")).isNull()
        assertThat(MobileAssetName.parse("shasha-mobile-1.2.0-1042.apk.zip")).isNull()
        assertThat(MobileAssetName.parse("../shasha-mobile-1.2.0-1042.apk")).isNull()
    }
}
