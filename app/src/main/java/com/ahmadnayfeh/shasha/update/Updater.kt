package com.ahmadnayfeh.shasha.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** The installed app: BuildConfig.VERSION_NAME and VERSION_CODE. */
data class InstalledVersion(val name: String, val code: Long)

/** The updater's steps, as the updater screen drives them ([com.ahmadnayfeh.shasha.ui.screens.updates.UpdatesViewModel]). */
interface UpdateSteps {
    val installed: InstalledVersion

    /** False: this build has no read token, so it cannot read the private repository's releases at all. */
    val hasToken: Boolean

    val installEvents: Flow<InstallEvent>

    /** @throws UpdateException */
    suspend fun latest(): ReleaseInfo?

    /** Downloads [release]'s APK (or finds it already downloaded) and checks it ([ApkVerifier]). @throws UpdateException */
    suspend fun download(release: ReleaseInfo, onProgress: (Long, Long?) -> Unit): File

    /** False: Android does not let this app install anything yet (the kit grants the permission). */
    fun installAllowed(): Boolean

    /** @return the installer session's id. */
    suspend fun install(apk: File): Int

    fun abandon(sessionId: Int)
}

/**
 * SPEC §6 «فحص التحديثات»: the latest release ([releases]), its APK downloaded into [downloads] (cacheDir/updates)
 * and checked against the installed app ([inspector], [ApkVerifier]: the same app, newer, the same signing key), then
 * the system installer ([installer]: silent where Android allows it, else with the system's own confirmation).
 */
class Updater(
    private val releases: GitHubReleases,
    private val inspector: ApkInspector,
    private val installer: UpdateInstaller,
    override val installed: InstalledVersion,
    private val downloads: File,
    events: InstallEvents,
) : UpdateSteps {
    override val hasToken: Boolean get() = releases.hasToken
    override val installEvents: Flow<InstallEvent> = events.events

    override suspend fun latest(): ReleaseInfo? = releases.latest()

    override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long?) -> Unit): File {
        val target = File(downloads, "shasha-${release.versionName}-${release.versionCode}.apk")
        withContext(Dispatchers.IO) {
            // Only this release's file is kept (a TV's storage is small).
            downloads.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        }
        val ready = withContext(Dispatchers.IO) { target.isFile && target.length() == release.apk.size && verify(target) == null }
        if (!ready) {
            releases.download(release.apk, target, onProgress)
            val refusal = withContext(Dispatchers.IO) { verify(target) }
            if (refusal != null) {
                target.delete()
                throw UpdateException(refusal, refusalDetail(refusal))
            }
        }
        return target
    }

    override fun installAllowed(): Boolean = installer.installAllowed()

    override suspend fun install(apk: File): Int = installer.install(apk)

    override fun abandon(sessionId: Int) = installer.abandon(sessionId)

    private fun verify(apk: File): UpdateErrorKind? = ApkVerifier.refusal(inspector.installed(), inspector.archive(apk))

    private fun refusalDetail(kind: UpdateErrorKind): String? = when (kind) {
        UpdateErrorKind.NOT_NEWER -> "installed ${installed.code}"
        else -> null
    }
}
