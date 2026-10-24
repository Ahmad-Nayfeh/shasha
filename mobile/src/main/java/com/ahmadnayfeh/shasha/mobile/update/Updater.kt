package com.ahmadnayfeh.shasha.mobile.update

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** The installed app: BuildConfig.VERSION_NAME and VERSION_CODE. */
data class InstalledVersion(val name: String, val code: Long)

/** The updater's steps, as the update screen drives them ([com.ahmadnayfeh.shasha.mobile.ui.update.UpdateViewModel]). */
interface UpdateSteps {
    val installed: InstalledVersion

    val installEvents: Flow<InstallEvent>

    /** @throws UpdateException */
    suspend fun latest(): ReleaseInfo

    /** Downloads [release]'s APK (or finds it already downloaded) and checks it ([ApkVerifier]). @throws UpdateException */
    suspend fun download(release: ReleaseInfo, onProgress: (Long, Long?) -> Unit): File

    /** Whether the viewer allowed installing from this app. */
    fun installAllowed(): Boolean

    /** The system's page where the viewer allows it. */
    fun permissionSettings(): Intent

    /** @return the installer session's id. */
    suspend fun install(apk: File): Int

    fun abandon(sessionId: Int)
}

/**
 * The latest version ([feed]), its APK downloaded into [downloads] (cacheDir/updates), its SHA-256 matched against the
 * manifest's and checked against the installed app ([inspector], [ApkVerifier]), then the system installer ([installer]).
 */
class Updater(
    private val feed: UpdateFeed,
    private val inspector: ApkInspector,
    private val installer: UpdateInstaller,
    override val installed: InstalledVersion,
    private val downloads: File,
    events: InstallEvents,
) : UpdateSteps {
    override val installEvents: Flow<InstallEvent> = events.events

    override suspend fun latest(): ReleaseInfo = feed.latest()

    override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long?) -> Unit): File {
        val target = File(downloads, "shasha-mobile-${release.versionName}-${release.versionCode}.apk")
        withContext(Dispatchers.IO) {
            // Only this release's file is kept.
            downloads.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        }
        val ready = withContext(Dispatchers.IO) { target.isFile && UpdateFeed.sha256(target) == release.apk.sha256 && verify(target) == null }
        if (!ready) {
            feed.download(release.apk, target, onProgress)
            val refusal = withContext(Dispatchers.IO) { verify(target) }
            if (refusal != null) {
                target.delete()
                throw UpdateException(refusal, if (refusal == UpdateErrorKind.NOT_NEWER) "installed ${installed.code}" else null)
            }
        }
        return target
    }

    override fun installAllowed(): Boolean = installer.installAllowed()

    override fun permissionSettings(): Intent = installer.permissionSettings()

    override suspend fun install(apk: File): Int = installer.install(apk)

    override fun abandon(sessionId: Int) = installer.abandon(sessionId)

    private fun verify(apk: File): UpdateErrorKind? = ApkVerifier.refusal(inspector.installed(), inspector.archive(apk))
}
