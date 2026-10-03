package com.ahmadnayfeh.shasha.update

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.ahmadnayfeh.shasha.FloorAwareRobolectricRunner
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File

/**
 * The installed app and a downloaded APK are read the way each Android version offers: the signing certificates and the
 * long version code arrived with API 28, so on Fire OS 6 / Android 7.1 (API 25) the old `signatures` and `versionCode`
 * are used. Both read the same facts, so [ApkVerifier] gives the same verdicts on every version. API 25 runs as soon as the
 * app's minSdk is 25 ([FloorAwareRobolectricRunner]); API 35 always.
 */
@RunWith(FloorAwareRobolectricRunner::class)
@Config(sdk = [25, 35], application = Application::class)
class ApkInspectorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packages = shadowOf(context.packageManager)
    private val owner = "the owner's release certificate".toByteArray()
    private val other = "another key".toByteArray()

    /** A package as the PackageManager of this API level reports it: [certificates] as signatures, and from API 28 as signing info too. */
    @Suppress("DEPRECATION") // versionCode and signatures are what API 25 reads
    private fun info(packageName: String, code: Int, vararg certificates: ByteArray) = PackageInfo().apply {
        this.packageName = packageName
        versionCode = code
        signatures = certificates.map { Signature(it) }.toTypedArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = Shadow.newInstanceOf(SigningInfo::class.java)
            Shadow.extract<ShadowSigningInfo>(signing).setSignatures(signatures)
            signingInfo = signing
        }
    }

    private fun digests(vararg certificates: ByteArray) = certificates.mapTo(HashSet()) { ApkVerifier.digest(it) }

    @Test
    fun theInstalledApp_isReadWithItsVersionCodeAndItsSigningCertificate() {
        packages.installPackage(info(context.packageName, 1057, owner))
        val installed = ApkInspector(context).installed()
        assertThat(installed.packageName).isEqualTo(context.packageName)
        assertThat(installed.versionCode).isEqualTo(1057L)
        assertThat(installed.certificateDigests).isEqualTo(digests(owner))
    }

    /**
     * Robolectric answers `getPackageArchiveInfo` from what a test registers only through its `int` overload, the one
     * below API 33; from API 33 the app calls the `PackageInfoFlags` one, which would need a real signed APK to parse.
     */
    private fun archivesCanBeRegistered() =
        assumeTrue("archive info is registered only for the int overload (below API 33)", Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)

    @Test
    fun aDownloadedApk_isReadTheSameWay() {
        archivesCanBeRegistered()
        val apk = File("downloads/shasha-1.2.0-1070.apk")
        packages.setPackageArchiveInfo(apk.path, info(context.packageName, 1070, owner))
        val archive = ApkInspector(context).archive(apk)!!
        assertThat(archive.packageName).isEqualTo(context.packageName)
        assertThat(archive.versionCode).isEqualTo(1070L)
        assertThat(archive.certificateDigests).isEqualTo(digests(owner))
    }

    @Test
    fun aFileThatIsNoApp_isNothing() {
        assertThat(ApkInspector(context).archive(File("downloads/not-an-apk.apk"))).isNull()
    }

    @Test
    fun theVerdictsAreTheSameOnEveryAndroid() {
        archivesCanBeRegistered()
        packages.installPackage(info(context.packageName, 1057, owner))
        val inspector = ApkInspector(context)
        val installed = inspector.installed()
        fun verdict(path: String, code: Int, certificate: ByteArray): UpdateErrorKind? {
            val apk = File(path)
            packages.setPackageArchiveInfo(apk.path, info(context.packageName, code, certificate))
            return ApkVerifier.refusal(installed, inspector.archive(apk))
        }
        // Newer and signed with the same key: may replace the installed app.
        assertThat(verdict("downloads/a.apk", 1070, owner)).isNull()
        // Another key, an older build, the same build: refused, before the system installer ever sees them.
        assertThat(verdict("downloads/b.apk", 1070, other)).isEqualTo(UpdateErrorKind.SIGNATURE_MISMATCH)
        assertThat(verdict("downloads/c.apk", 1000, owner)).isEqualTo(UpdateErrorKind.NOT_NEWER)
        assertThat(verdict("downloads/d.apk", 1057, owner)).isEqualTo(UpdateErrorKind.NOT_NEWER)
    }
}
