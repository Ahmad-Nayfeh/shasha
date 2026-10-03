package com.ahmadnayfeh.shasha.mobile.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** An APK attached to a release; [apiUrl] is the server's API address for it (the download goes through it). */
data class ReleaseAsset(val id: Long, val name: String, val size: Long, val apiUrl: String)

/**
 * The phone app's part of a published release (.github/workflows/android.yml): a release holds both apps, and the phone's
 * asset is `shasha-mobile-<versionName>-<versionCode>.apk`. The release's tag follows the TV app's version, so the phone's
 * version is read from the asset's name.
 */
data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val notes: String,
    val apk: ReleaseAsset,
) {
    /** Offered only when it is newer than what is installed ([installedVersionCode] = BuildConfig.VERSION_CODE). */
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode
}

/** The phone app's version as its APK's file name says: `shasha-mobile-1.2.0-1042.apk` is "1.2.0", code 1042. */
data class MobileAssetName(val versionName: String, val versionCode: Long) {
    companion object {
        const val PREFIX = "shasha-mobile-"

        // The last "-<digits>" is the code: a versionName may contain dashes itself ("1.2.0-rc1").
        private val pattern = Regex("""shasha-mobile-([0-9A-Za-z._-]+)-(\d{1,10})\.apk""")

        /** Null for any other name: the TV app's APK, a debug build, a file of another kind. */
        fun parse(name: String): MobileAssetName? {
            val match = pattern.matchEntire(name.trim()) ?: return null
            val code = match.groupValues[2].toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
            return MobileAssetName(match.groupValues[1], code)
        }

        fun of(versionName: String, versionCode: Long) = "$PREFIX$versionName-$versionCode.apk"
    }
}

/** The server's answer for the latest release, read into a [ReleaseInfo]. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The phone's release, or null when there is nothing to offer: a draft or prerelease, or a release without a phone APK
     * (published before the phone app existed).
     *
     * @throws UpdateException BAD_ANSWER (not a release).
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
        val (name, asset) = pickAsset(release.assets) ?: return null
        return ReleaseInfo(
            tag = tagName,
            versionName = name.versionName,
            versionCode = name.versionCode,
            notes = ReleaseNotes.plain(release.body.orEmpty()),
            apk = ReleaseAsset(asset.id, asset.name, asset.size, asset.url),
        )
    }

    /** The phone's APK among a release's assets: complete, named like the phone's, the newest if there were several. */
    private fun pickAsset(assets: List<AssetJson>): Pair<MobileAssetName, AssetJson>? = assets
        .filter { it.state == "uploaded" && it.url.isNotBlank() }
        .mapNotNull { asset -> MobileAssetName.parse(asset.name)?.let { it to asset } }
        .maxByOrNull { (name, _) -> name.versionCode }

    @Serializable
    private data class ReleaseJson(
        @SerialName("tag_name") val tagName: String? = null,
        val body: String? = null,
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

/** A release's Markdown (CI writes it) as plain lines: no `**`, backticks or link syntax. */
object ReleaseNotes {
    private val link = Regex("""\[([^\]]*)]\(([^)]*)\)""")
    private val heading = Regex("""^#{1,6}\s*""")
    private val bullet = Regex("""^\s*[-*+]\s+""")
    private val blankRun = Regex("""\n{3,}""")

    fun plain(markdown: String): String = markdown
        .replace("\r\n", "\n")
        .lines()
        .joinToString("\n") { raw ->
            var line = raw.trimEnd()
            line = line.replace(heading, "")
            line = line.replace(bullet, "• ")
            line = line.replace(link) { it.groupValues[1] }
            line.replace("**", "").replace("__", "").replace("`", "")
        }
        .replace(blankRun, "\n\n")
        .trim()
}
