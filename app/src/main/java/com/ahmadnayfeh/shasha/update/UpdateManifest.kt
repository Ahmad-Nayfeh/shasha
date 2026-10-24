package com.ahmadnayfeh.shasha.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl

/** A published APK: its address, its size in bytes (0 = unknown) and its SHA-256 (64 lowercase hex digits). */
data class ReleaseApk(val url: String, val size: Long, val sha256: String)

/** The latest published version of this app (SPEC §6), as the update manifest describes it ([UpdateManifest]). */
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val apk: ReleaseApk,
) {
    /** Offered only when it is newer than what is installed ([installedVersionCode] = BuildConfig.VERSION_CODE). */
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode
}

/**
 * The public update manifest (`latest.json` on the download site, docs/dev/MAINTENANCE.md), one entry per app:
 *
 * ```
 * {"tv":     {"versionCode": 1100, "versionName": "1.8.0", "url": "https://…/shasha-tv.apk?v=1100",
 *             "sha256": "<64 hex>", "size": 18874368, "notes": "…"},
 *  "mobile": {…the same fields…}}
 * ```
 *
 * This app reads [ENTRY] only. `url` may be relative to the manifest; `size` and `notes` are optional; unknown fields
 * are ignored, so the format can grow.
 */
object UpdateManifest {
    /** The TV app's entry. */
    const val ENTRY = "tv"

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    // It becomes part of the downloaded file's name.
    private val versionNamePattern = Regex("[0-9A-Za-z._-]{1,64}")

    /**
     * This app's entry of [body], fetched from [manifestUrl] (relative APK addresses resolve against it; an https
     * manifest never sends the download to plain http).
     *
     * @throws UpdateException NO_APK (no entry for this app), BAD_ANSWER (not a manifest, or an unusable entry).
     */
    fun parse(body: String, manifestUrl: HttpUrl): ReleaseInfo {
        val entry = try {
            val root = json.parseToJsonElement(body).jsonObject
            val element = root[ENTRY] ?: throw UpdateException(UpdateErrorKind.NO_APK, "no \"$ENTRY\" entry")
            json.decodeFromJsonElement(EntryJson.serializer(), element as? JsonObject ?: throw bad("\"$ENTRY\" is not an object"))
        } catch (e: SerializationException) {
            throw bad(e.message)
        } catch (e: IllegalArgumentException) {
            // Not JSON, or not an object at the top.
            throw bad(e.message)
        }
        if (entry.versionCode !in 1..Int.MAX_VALUE) throw bad("versionCode ${entry.versionCode}")
        if (!versionNamePattern.matches(entry.versionName)) throw bad("versionName \"${entry.versionName}\"")
        val url = manifestUrl.resolve(entry.url.trim())?.takeIf { entry.url.isNotBlank() } ?: throw bad("url \"${entry.url}\"")
        if (manifestUrl.isHttps && !url.isHttps) throw bad("insecure url")
        val sha256 = entry.sha256.trim().lowercase()
        if (!sha256Pattern.matches(sha256)) throw bad("sha256")
        if (entry.size < 0) throw bad("size ${entry.size}")
        return ReleaseInfo(entry.versionName, entry.versionCode, ReleaseApk(url.toString(), entry.size, sha256))
    }

    private fun bad(detail: String?) = UpdateException(UpdateErrorKind.BAD_ANSWER, detail)

    @Serializable
    private data class EntryJson(
        val versionCode: Long = 0,
        val versionName: String = "",
        val url: String = "",
        val sha256: String = "",
        val size: Long = 0,
    )
}
