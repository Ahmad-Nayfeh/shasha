package com.ahmadnayfeh.shasha.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** An APK attached to a release; [apiUrl] is GitHub's API address for it (the download goes through it). */
data class ReleaseAsset(val id: Long, val name: String, val size: Long, val apiUrl: String)

/**
 * A published release of Shasha (SPEC §6), as CI names it (.github/workflows/android.yml): tag
 * `v<versionName>-<versionCode>`, the TV's APK `shasha-<versionName>-<versionCode>.apk`, and, from v1.2, the phone
 * app's `shasha-mobile-<versionName>-<versionCode>.apk` ([ReleaseParser] never takes that one).
 */
data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val apk: ReleaseAsset,
) {
    /** Offered only when it is newer than what is installed ([installedVersionCode] = BuildConfig.VERSION_CODE). */
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode
}

/** The tag CI gives a release: `v1.0.0-1042` = versionName "1.0.0", versionCode 1042. */
data class ReleaseTag(val versionName: String, val versionCode: Long) {
    companion object {
        // The last "-<digits>" is the code: a versionName may contain dashes itself ("1.0.0-rc1").
        private val pattern = Regex("""v([0-9A-Za-z._-]+)-(\d{1,10})""")

        fun parse(tag: String): ReleaseTag? {
            val match = pattern.matchEntire(tag.trim()) ?: return null
            val code = match.groupValues[2].toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
            return ReleaseTag(match.groupValues[1], code)
        }
    }
}

/** GitHub's answer for `GET /repos/{repo}/releases/latest`, read into a [ReleaseInfo]. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The release, or null when it must not be offered: a draft or a prerelease (`/releases/latest` never
     * returns those, this makes sure of it).
     *
     * @throws UpdateException BAD_ANSWER (not a release), BAD_TAG (not `v<name>-<code>`), NO_APK (no .apk to install).
     */
    fun parse(body: String): ReleaseInfo? {
        val release = try {
            json.decodeFromString(ReleaseJson.serializer(), body)
        } catch (e: SerializationException) {
            throw UpdateException(UpdateErrorKind.BAD_ANSWER, e.message)
        } catch (e: IllegalArgumentException) {
            throw UpdateException(UpdateErrorKind.BAD_ANSWER, e.message)
        }
        if (release.draft || release.prerelease) return null
        val tagName = release.tagName?.takeIf { it.isNotBlank() } ?: throw UpdateException(UpdateErrorKind.BAD_ANSWER, "no tag_name")
        val tag = ReleaseTag.parse(tagName) ?: throw UpdateException(UpdateErrorKind.BAD_TAG, tagName)
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.state == "uploaded" && it.url.isNotBlank() }
        // The TV's APK, under CI's own name. The phone app's APK (shasha-mobile-…) rides in the same release and is
        // never this app's update, whatever the order of the assets; neither is a debug build (never an update of a
        // release-signed app). A release made by hand under another name still works, by the same two exclusions.
        val expected = "shasha-${tag.versionName}-${tag.versionCode}.apk"
        val candidates = apks.filterNot { isPhoneApp(it.name) }
        val apk = candidates.firstOrNull { it.name == expected }
            ?: candidates.firstOrNull { !it.name.endsWith("-debug.apk", ignoreCase = true) }
            ?: throw UpdateException(UpdateErrorKind.NO_APK, tagName)
        return ReleaseInfo(
            tag = tagName,
            versionName = tag.versionName,
            versionCode = tag.versionCode,
            apk = ReleaseAsset(apk.id, apk.name, apk.size, apk.url),
        )
    }

    /** The phone app's APK: `shasha-mobile-<versionName>-<versionCode>.apk` (any asset naming the mobile app counts). */
    private fun isPhoneApp(name: String): Boolean = name.contains("mobile", ignoreCase = true)

    @Serializable
    private data class ReleaseJson(
        @SerialName("tag_name") val tagName: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<AssetJson> = emptyList(),
    )

    @Serializable
    private data class AssetJson(
        val id: Long = 0,
        val name: String = "",
        val size: Long = 0,
        /** The API address (`…/releases/assets/<id>`), not `browser_download_url` (which needs a browser login). */
        val url: String = "",
        /** "uploaded" once the file is complete ("new" while it is still being uploaded). */
        val state: String = "uploaded",
    )
}
